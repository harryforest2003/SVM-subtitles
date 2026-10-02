package io.github.harryforest2003.svmsubtitles.server;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.audio.Segment;
import io.github.harryforest2003.svmsubtitles.audio.SpeakerTracker;
import io.github.harryforest2003.svmsubtitles.audio.SpeechSegmenter;
import io.github.harryforest2003.svmsubtitles.chat.PrivateMessageCommand;
import io.github.harryforest2003.svmsubtitles.chat.SubtitleMessage;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.network.SubtitlesStatusPayload;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import io.github.harryforest2003.svmsubtitles.util.Threads;
import io.github.harryforest2003.svmsubtitles.voice.SubtitlesVoicechatPlugin;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Server mode: decodes everyone's microphone packets, transcribes finished sentences and sends them as chat
 * messages. Players don't need the mod for this; the messages are ordinary system chat lines.
 *
 * <p>Who sees a line follows who could hear it: group voice only goes to the group, whispers only to players in
 * whisper range, and normal speech to everyone (or to players in voice range with audience = "nearby").
 */
public final class ServerSubtitles {
	private static volatile @Nullable ServerSubtitles instance;

	private final MinecraftServer server;
	private final PlayerPreferences preferences;
	private final SpeakerTracker tracker;
	/** Opus decoding and segmentation run here, off Simple Voice Chat's network thread. */
	private final ExecutorService audioThread = Threads.single("SVM Subtitles audio");
	/** Only touched on the audio thread. */
	private final Map<UUID, OpusDecoder> decoders = new HashMap<>();

	private ServerSubtitles(MinecraftServer server) {
		this.server = server;
		this.preferences = PlayerPreferences.load(SvmSubtitles.dataDir().resolve("players.json"));
		this.tracker = new SpeakerTracker(SpeechSegmenter.Settings.from(SvmSubtitles.config().segmentation), this::onSentence);
		TranscriptionService.shared();
	}

	public static @Nullable ServerSubtitles get() {
		return instance;
	}

	public static void start(MinecraftServer server) {
		if (!SvmSubtitles.config().server.enabled) {
			SvmSubtitles.LOGGER.info("Server-side voice subtitles are turned off in the config");
			return;
		}
		instance = new ServerSubtitles(server);
	}

	public static void stop(MinecraftServer server) {
		ServerSubtitles subtitles = instance;
		instance = null;
		if (subtitles != null) {
			subtitles.close();
		}
		if (server.isDedicatedServer()) {
			TranscriptionService.shutdownShared();
		}
	}

	/** Applies a reloaded config: starts, stops or retunes server mode. */
	public static void reload(MinecraftServer server) {
		ServerSubtitles subtitles = instance;
		boolean enabled = SvmSubtitles.config().server.enabled;
		if (subtitles != null && !enabled) {
			instance = null;
			subtitles.close();
			if (server.isDedicatedServer()) {
				TranscriptionService.shutdownShared();
			}
		} else if (subtitles == null && enabled) {
			start(server);
		} else if (subtitles != null) {
			subtitles.tracker.setSettings(SpeechSegmenter.Settings.from(SvmSubtitles.config().segmentation));
		}
		ServerSubtitles current = instance;
		if (current != null) {
			current.broadcastStatus();
		} else {
			server.execute(() -> server.getPlayerList().getPlayers().forEach(p -> sendStatus(p, false)));
		}
	}

	public PlayerPreferences preferences() {
		return preferences;
	}

	public boolean isTranscribing() {
		return TranscriptionService.shared().isAccepting();
	}

	/** Called by Simple Voice Chat on its network thread for every 20 ms voice packet. */
	public void onMicrophonePacket(MicrophonePacketEvent event) {
		VoicechatConnection sender = event.getSenderConnection();
		if (sender == null) {
			return;
		}
		UUID speaker = sender.getPlayer().getUuid();
		if (preferences.isOptedOut(speaker) || !TranscriptionService.shared().isAccepting()) {
			return;
		}
		MicrophonePacket packet = event.getPacket();
		byte[] opus = packet.getOpusEncodedData();
		boolean whispering = packet.isWhispering();
		VoicechatServerApi api = event.getVoicechat();
		try {
			audioThread.execute(() -> decode(api, speaker, opus, whispering));
		} catch (RejectedExecutionException ignored) {
			// shutting down
		}
	}

	private void decode(VoicechatServerApi api, UUID speaker, byte[] opus, boolean whispering) {
		if (opus.length == 0) {
			// Simple Voice Chat sends an empty packet when someone stops talking.
			OpusDecoder decoder = decoders.get(speaker);
			if (decoder != null) {
				decoder.resetState();
			}
			tracker.endOfSpeech(speaker);
			return;
		}
		OpusDecoder decoder = decoders.computeIfAbsent(speaker, id -> api.createDecoder());
		tracker.accept(speaker, decoder.decode(opus), whispering);
	}

	public void forget(UUID player) {
		try {
			audioThread.execute(() -> {
				tracker.remove(player);
				OpusDecoder decoder = decoders.remove(player);
				if (decoder != null) {
					decoder.close();
				}
			});
		} catch (RejectedExecutionException ignored) {
			// shutting down
		}
	}

	private void onSentence(UUID speaker, Segment segment) {
		TranscriptionService.shared().submit(segment, text -> server.execute(() -> deliver(speaker, segment.whispering(), text)));
	}

	private void deliver(UUID speakerId, boolean whispering, String text) {
		if (instance != this || preferences.isOptedOut(speakerId)) {
			return;
		}
		ServerPlayer speaker = server.getPlayerList().getPlayer(speakerId);
		if (speaker == null) {
			return;
		}
		SubtitlesConfig config = SvmSubtitles.config();
		String name = speaker.getGameProfile().name();
		String pmCommand = PrivateMessageCommand.build(config.chat.privateMessageCommand, name, server.getCommands().getDispatcher());
		Component message = SubtitleMessage.build(config.chat, name, text, pmCommand);

		for (ServerPlayer player : audience(speaker, whispering, config)) {
			if (!preferences.isHidden(player.getUUID())) {
				player.sendSystemMessage(message);
			}
		}
		if (config.server.logToConsole) {
			SvmSubtitles.LOGGER.info("[Voice] {}: {}", name, text);
		}
	}

	private List<ServerPlayer> audience(ServerPlayer speaker, boolean whispering, SubtitlesConfig config) {
		VoicechatServerApi api = SubtitlesVoicechatPlugin.serverApi();
		Group group = groupOf(api, speaker.getUUID());
		List<ServerPlayer> everyone = server.getPlayerList().getPlayers();

		if (group != null && group.getType() != Group.Type.OPEN) {
			// Only the group can hear this, so only the group may read it.
			if (!config.server.transcribeGroups) {
				return List.of();
			}
			return groupMembers(api, everyone, speaker, group);
		}
		if (whispering) {
			if (!config.server.transcribeWhispers) {
				return List.of();
			}
			double distance = api == null ? 24 : api.getServerConfig().getDouble("whisper_distance", 24);
			return nearby(speaker, distance);
		}
		if (config.server.audience.equals("nearby")) {
			List<ServerPlayer> result = new ArrayList<>(nearby(speaker, api == null ? 48 : api.getVoiceChatDistance()));
			if (group != null) {
				for (ServerPlayer member : groupMembers(api, everyone, speaker, group)) {
					if (!result.contains(member)) {
						result.add(member);
					}
				}
			}
			return result;
		}
		return everyone;
	}

	private static @Nullable Group groupOf(@Nullable VoicechatServerApi api, UUID player) {
		if (api == null) {
			return null;
		}
		VoicechatConnection connection = api.getConnectionOf(player);
		return connection == null ? null : connection.getGroup();
	}

	private static List<ServerPlayer> groupMembers(@Nullable VoicechatServerApi api, List<ServerPlayer> everyone, ServerPlayer speaker, Group group) {
		List<ServerPlayer> members = new ArrayList<>();
		for (ServerPlayer player : everyone) {
			Group other = player == speaker ? group : groupOf(api, player.getUUID());
			if (other != null && other.getId().equals(group.getId())) {
				members.add(player);
			}
		}
		return members;
	}

	private static List<ServerPlayer> nearby(ServerPlayer speaker, double distance) {
		double max = distance * distance;
		List<ServerPlayer> result = new ArrayList<>();
		for (ServerPlayer player : speaker.level().players()) {
			if (player == speaker || player.distanceToSqr(speaker) <= max) {
				result.add(player);
			}
		}
		return result;
	}

	public void broadcastStatus() {
		server.execute(() -> {
			boolean transcribing = instance == this && isTranscribing();
			server.getPlayerList().getPlayers().forEach(player -> sendStatus(player, transcribing));
		});
	}

	public static void onJoin(ServerPlayer player) {
		ServerSubtitles subtitles = instance;
		if (subtitles != null) {
			sendStatus(player, subtitles.isTranscribing());
		}
	}

	private static void sendStatus(ServerPlayer player, boolean transcribing) {
		if (ServerPlayNetworking.canSend(player, SubtitlesStatusPayload.TYPE)) {
			ServerPlayNetworking.send(player, new SubtitlesStatusPayload(transcribing));
		}
	}

	private void close() {
		tracker.close();
		audioThread.execute(() -> {
			decoders.values().forEach(OpusDecoder::close);
			decoders.clear();
		});
		audioThread.shutdown();
	}
}
