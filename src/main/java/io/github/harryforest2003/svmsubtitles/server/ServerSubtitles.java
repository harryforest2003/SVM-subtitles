package io.github.harryforest2003.svmsubtitles.server;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.audio.LiveCaptioner;
import io.github.harryforest2003.svmsubtitles.audio.Segment;
import io.github.harryforest2003.svmsubtitles.audio.SpeakerTracker;
import io.github.harryforest2003.svmsubtitles.audio.SpeechSegmenter;
import io.github.harryforest2003.svmsubtitles.audio.Wav;
import io.github.harryforest2003.svmsubtitles.chat.PrivateMessageCommand;
import io.github.harryforest2003.svmsubtitles.chat.SubtitleMessage;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.network.SubtitlePayload;
import io.github.harryforest2003.svmsubtitles.network.SubtitlesStatusPayload;
import io.github.harryforest2003.svmsubtitles.transcribe.Transcript;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import io.github.harryforest2003.svmsubtitles.util.Threads;
import io.github.harryforest2003.svmsubtitles.voice.SubtitlesVoicechatPlugin;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Server mode: decodes everyone's microphone packets, transcribes finished sentences and sends them to players.
 * Players with the mod get a subtitle packet (for name alerts and on-screen live captions); everyone else gets
 * an ordinary chat line, and live captions in the action bar.
 *
 * <p>Who sees a line follows who could hear it: group voice only goes to the group, whispers only to players in
 * whisper range, and normal speech to everyone (or to players in voice range with audience = "nearby").
 */
public final class ServerSubtitles {
	private static volatile @Nullable ServerSubtitles instance;

	private record Audience(List<ServerPlayer> players, String scope) {
	}

	private final MinecraftServer server;
	private final PlayerPreferences preferences;
	private final @Nullable VoiceHistory history;
	private final SpeakerTracker tracker;
	/** Opus decoding and segmentation run here, off Simple Voice Chat's network thread. */
	private final ExecutorService audioThread = Threads.single("SVM Subtitles audio");
	/** Only touched on the audio thread. */
	private final Map<UUID, OpusDecoder> decoders = new HashMap<>();
	private final Map<UUID, Long> lastTts = new ConcurrentHashMap<>();
	private int ticks;

	private ServerSubtitles(MinecraftServer server) {
		SubtitlesConfig config = SvmSubtitles.config();
		this.server = server;
		this.preferences = PlayerPreferences.load(SvmSubtitles.dataDir().resolve("players.json"));
		this.history = config.history.enabled
				? new VoiceHistory(FabricLoader.getInstance().getGameDir().resolve("svm_subtitles").resolve("history"),
				config.history.keepDays, Clock.systemDefaultZone())
				: null;
		LiveCaptioner live = new LiveCaptioner(ServerSubtitles::sendLive,
				(speaker, text, whispering) -> server.execute(() -> deliverPartial(speaker, whispering, text)),
				() -> TranscriptionService.shared().liveIntervalMs());
		this.tracker = new SpeakerTracker(SpeechSegmenter.Settings.from(config.segmentation), this::onSentence, live);
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

	/** Applies a reloaded config by starting server mode afresh (or stopping it). */
	public static void reload(MinecraftServer server) {
		ServerSubtitles old = instance;
		instance = null;
		if (old != null) {
			old.close();
		}
		if (SvmSubtitles.config().server.enabled) {
			start(server);
		} else if (server.isDedicatedServer()) {
			TranscriptionService.shutdownShared();
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

	public @Nullable VoiceHistory history() {
		return history;
	}

	public boolean isTranscribing() {
		return TranscriptionService.shared().isAccepting();
	}

	public void tick() {
		if (++ticks % 100 == 0) {
			TranscriptionService.setPlayerNames(server.getPlayerList().getPlayers().stream().map(p -> p.getGameProfile().name()).toList());
		}
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
		SubtitlesConfig.Server config = SvmSubtitles.config().server;
		// Don't even listen to what nobody would be allowed to read.
		Group group = sender.getGroup();
		if (!config.transcribeGroups && group != null && group.getType() != Group.Type.OPEN) {
			return;
		}
		if (!config.transcribeWhispers && packet.isWhispering()) {
			return;
		}
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
		lastTts.remove(player);
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

	private static @Nullable CompletableFuture<@Nullable String> sendLive(UUID session, float[] audio) {
		TranscriptionService service = TranscriptionService.current();
		if (service == null || !service.supportsLive()) {
			return null;
		}
		return service.streamPartial(session, audio).thenApply(result -> result == null ? null : result.text());
	}

	private void onSentence(UUID speaker, Segment segment) {
		TranscriptionService.shared().submit(segment,
				transcript -> server.execute(() -> deliver(speaker, segment.whispering(), transcript, SubtitlePayload.FINAL)));
	}

	private void deliver(UUID speakerId, boolean whispering, Transcript transcript, int kind) {
		if (instance != this || (kind == SubtitlePayload.FINAL && preferences.isOptedOut(speakerId))) {
			return;
		}
		ServerPlayer speaker = server.getPlayerList().getPlayer(speakerId);
		if (speaker == null) {
			return;
		}
		SubtitlesConfig config = SvmSubtitles.config();
		String name = speaker.getGameProfile().name();
		String text = transcript.text();
		String language = transcript.wasTranslated() ? transcript.language() : null;
		String format = kind == SubtitlePayload.TTS ? config.chat.ttsFormat : config.chat.format;
		String pmCommand = PrivateMessageCommand.build(config.chat.privateMessageCommand, name, server.getCommands().getDispatcher());
		Audience audience = audience(speaker, whispering, config);

		SubtitlePayload payload = new SubtitlePayload(speakerId, name, text, format, pmCommand == null ? "" : pmCommand,
				language == null ? "" : language, kind);
		Component chatLine = null;
		for (ServerPlayer player : audience.players()) {
			if (preferences.isHidden(player.getUUID())) {
				continue;
			}
			if (ServerPlayNetworking.canSend(player, SubtitlePayload.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			} else {
				if (chatLine == null) {
					chatLine = SubtitleMessage.build(format, config.chat.hoverText, name, text, pmCommand, language, List.of());
				}
				player.sendSystemMessage(chatLine);
			}
		}

		String kindName = kind == SubtitlePayload.TTS ? "tts" : "voice";
		if (config.server.logToConsole) {
			SvmSubtitles.LOGGER.info("[{}] {}{}: {}", kind == SubtitlePayload.TTS ? "TTS" : "Voice", name,
					audience.scope().equals("everyone") ? "" : " (" + audience.scope() + ")", text);
		}
		if (history != null) {
			history.record(new VoiceHistory.Entry(System.currentTimeMillis(), speakerId, name, kindName, audience.scope(), text));
		}
	}

	/** Live caption: on screen for players with the mod, in the action bar for everyone else. */
	private void deliverPartial(UUID speakerId, boolean whispering, String text) {
		if (instance != this || preferences.isOptedOut(speakerId)) {
			return;
		}
		ServerPlayer speaker = server.getPlayerList().getPlayer(speakerId);
		if (speaker == null) {
			return;
		}
		SubtitlesConfig config = SvmSubtitles.config();
		String name = speaker.getGameProfile().name();
		SubtitlePayload payload = new SubtitlePayload(speakerId, name, text, config.chat.format, "", "", SubtitlePayload.PARTIAL);
		Component actionBar = null;
		for (ServerPlayer player : audience(speaker, whispering, config).players()) {
			if (preferences.isHidden(player.getUUID()) || preferences.isLiveOff(player.getUUID())) {
				continue;
			}
			if (ServerPlayNetworking.canSend(player, SubtitlePayload.TYPE)) {
				ServerPlayNetworking.send(player, payload); // the player's own mod decides: chat, screen or off
			} else if (config.live.actionBar) {
				if (actionBar == null) {
					actionBar = Component.literal(name + ": ").withStyle(ChatFormatting.AQUA)
							.append(Component.literal(text.replaceAll("[.…\\s]+$", "") + " ...").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
				}
				player.sendSystemMessage(actionBar, true);
			}
		}
	}

	// ------------------------------------------------------------------ text-to-speech

	/** Seconds until this player may use /tts again (0 = now). */
	public int ttsCooldown(UUID player) {
		long wait = lastTts.getOrDefault(player, 0L) + SvmSubtitles.config().tts.cooldownSeconds * 1000L - System.currentTimeMillis();
		return wait <= 0 ? 0 : (int) Math.ceil(wait / 1000.0);
	}

	/** Speaks typed text in voice chat as if the player said it, and shows it as a subtitle. */
	public void speak(ServerPlayer player, String text, Consumer<Component> reply) {
		TranscriptionService service = TranscriptionService.shared();
		if (!service.supportsTts()) {
			reply.accept(SubtitleMessage.notice("Text-to-speech needs a companion speech server; ask an admin to set one up.", true));
			return;
		}
		lastTts.put(player.getUUID(), System.currentTimeMillis());
		UUID id = player.getUUID();
		service.synthesize(text).whenComplete((wav, error) -> server.execute(() -> {
			if (instance != this) {
				return;
			}
			ServerPlayer current = server.getPlayerList().getPlayer(id);
			if (current == null) {
				return;
			}
			if (error != null) {
				reply.accept(SubtitleMessage.notice("Text-to-speech failed: " + rootMessage(error), true));
				return;
			}
			try {
				play(current, Wav.decode(wav));
			} catch (IOException e) {
				reply.accept(SubtitleMessage.notice("Text-to-speech failed: " + e.getMessage(), true));
				return;
			}
			deliver(id, false, new Transcript(text, null), SubtitlePayload.TTS);
		}));
	}

	private void play(ServerPlayer player, Wav.Decoded audio) throws IOException {
		VoicechatServerApi api = SubtitlesVoicechatPlugin.serverApi();
		if (api == null) {
			throw new IOException("Simple Voice Chat isn't running");
		}
		short[] pcm = Wav.toPcm(audio.samples(), audio.sampleRate(), 48_000);
		Group group = groupOf(api, player.getUUID());
		AudioChannel channel;
		if (group != null && group.getType() != Group.Type.OPEN) {
			StaticAudioChannel groupChannel = api.createStaticAudioChannel(UUID.randomUUID());
			if (groupChannel == null) {
				throw new IOException("could not create a voice channel");
			}
			for (ServerPlayer member : groupMembers(api, server.getPlayerList().getPlayers(), player, group)) {
				VoicechatConnection connection = api.getConnectionOf(member.getUUID());
				if (connection != null) {
					groupChannel.addTarget(connection);
				}
			}
			channel = groupChannel;
		} else {
			EntityAudioChannel nearbyChannel = api.createEntityAudioChannel(UUID.randomUUID(), api.fromServerPlayer(player));
			if (nearbyChannel == null) {
				throw new IOException("could not create a voice channel");
			}
			nearbyChannel.setDistance((float) api.getVoiceChatDistance());
			channel = nearbyChannel;
		}
		channel.setCategory(SubtitlesVoicechatPlugin.TTS_CATEGORY);
		OpusEncoder encoder = api.createEncoder();
		AudioPlayer audioPlayer = api.createAudioPlayer(channel, encoder, pcm);
		audioPlayer.setOnStopped(encoder::close);
		audioPlayer.startPlaying();
	}

	private static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	// ------------------------------------------------------------------ audience

	private Audience audience(ServerPlayer speaker, boolean whispering, SubtitlesConfig config) {
		VoicechatServerApi api = SubtitlesVoicechatPlugin.serverApi();
		Group group = groupOf(api, speaker.getUUID());
		List<ServerPlayer> everyone = server.getPlayerList().getPlayers();

		if (group != null && group.getType() != Group.Type.OPEN) {
			// Only the group can hear this, so only the group may read it.
			return new Audience(config.server.transcribeGroups ? groupMembers(api, everyone, speaker, group) : List.of(), "group");
		}
		if (whispering) {
			double distance = api == null ? 24 : api.getServerConfig().getDouble("whisper_distance", 24);
			return new Audience(config.server.transcribeWhispers ? nearby(speaker, distance) : List.of(), "whisper");
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
			return new Audience(result, "nearby");
		}
		return new Audience(everyone, "everyone");
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

	// ------------------------------------------------------------------ joining

	public void broadcastStatus() {
		server.execute(() -> {
			boolean transcribing = instance == this && isTranscribing();
			server.getPlayerList().getPlayers().forEach(player -> sendStatus(player, transcribing));
		});
	}

	public static void onJoin(ServerPlayer player) {
		ServerSubtitles subtitles = instance;
		if (subtitles == null) {
			return;
		}
		sendStatus(player, subtitles.isTranscribing());
		SubtitlesConfig config = SvmSubtitles.config();
		if (config.server.joinNotice && subtitles.isTranscribing()) {
			StringBuilder notice = new StringBuilder("Voice chat on this server is turned into text in chat");
			if (subtitles.history != null) {
				notice.append(" and kept for ").append(config.history.keepDays).append(config.history.keepDays == 1 ? " day" : " days")
						.append(" so admins can check reports");
			}
			if (TranscriptionService.shared().usesRemoteServer()) {
				notice.append(". The audio is processed by a separate speech server");
			}
			notice.append(". Type /subtitles to opt out.");
			player.sendSystemMessage(SubtitleMessage.notice(notice.toString(), false));
		}
	}

	private static void sendStatus(ServerPlayer player, boolean transcribing) {
		if (ServerPlayNetworking.canSend(player, SubtitlesStatusPayload.TYPE)) {
			ServerPlayNetworking.send(player, new SubtitlesStatusPayload(transcribing));
		}
	}

	private void close() {
		tracker.close();
		try {
			audioThread.execute(() -> {
				decoders.values().forEach(OpusDecoder::close);
				decoders.clear();
			});
		} catch (RejectedExecutionException ignored) {
			// already closed
		}
		audioThread.shutdown();
		if (history != null) {
			history.close();
		}
	}
}
