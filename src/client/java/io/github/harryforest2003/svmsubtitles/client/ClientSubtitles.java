package io.github.harryforest2003.svmsubtitles.client;

import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.audio.LiveCaptioner;
import io.github.harryforest2003.svmsubtitles.audio.Segment;
import io.github.harryforest2003.svmsubtitles.audio.SpeakerTracker;
import io.github.harryforest2003.svmsubtitles.audio.SpeechSegmenter;
import io.github.harryforest2003.svmsubtitles.chat.AlertMatcher;
import io.github.harryforest2003.svmsubtitles.chat.PrivateMessageCommand;
import io.github.harryforest2003.svmsubtitles.chat.SubtitleMessage;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.network.SubtitlePayload;
import io.github.harryforest2003.svmsubtitles.transcribe.TextCleaner;
import io.github.harryforest2003.svmsubtitles.transcribe.Transcript;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import io.github.harryforest2003.svmsubtitles.voice.ClientVoiceListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shows subtitles on this client: ones the server sends (server mode), and, on servers that don't transcribe,
 * ones this client makes itself from the voices it hears (client mode). Adds name alerts and live captions.
 */
public final class ClientSubtitles implements ClientVoiceListener {
	public static final ClientSubtitles INSTANCE = new ClientSubtitles();
	private static final int MAX_PENDING_NOTICES = 5;

	/** Players in the tab list; other audio (music from plugins, etc.) is ignored. Read on audio threads. */
	private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
	private final Queue<Component> pendingNotices = new ConcurrentLinkedQueue<>();
	/** Game thread only. */
	private final LiveChatLines chatLines = new LiveChatLines();
	private final java.util.Map<UUID, String> livePartials = new java.util.HashMap<>();
	private volatile boolean inWorld;
	private volatile boolean serverTranscribing;
	private volatile @Nullable UUID self;
	private volatile @Nullable SpeakerTracker tracker;
	private int ticks;
	private long lastAlertSound;
	private int alertsPlayed;

	private ClientSubtitles() {
	}

	public boolean isServerTranscribing() {
		return serverTranscribing;
	}

	public void setServerTranscribing(boolean transcribing) {
		serverTranscribing = transcribing;
	}

	/** True while this client should transcribe what it hears. */
	public boolean isActive() {
		SubtitlesConfig config = SvmSubtitles.config();
		return config.client.enabled && inWorld && !(config.client.deferToServer && serverTranscribing);
	}

	// ------------------------------------------------------------------ client mode

	@Override
	public void onReceive(UUID speaker, short[] audio, boolean whispering) {
		if (onlinePlayers.contains(speaker) && !speaker.equals(self)) {
			feed(speaker, audio, whispering);
		}
	}

	@Override
	public void onOwnVoice(short[] audio, boolean whispering) {
		UUID me = self;
		if (me != null && SvmSubtitles.config().client.includeOwnVoice) {
			feed(me, audio, whispering);
		}
	}

	private void feed(UUID speaker, short[] audio, boolean whispering) {
		if (!isActive() || !TranscriptionService.shared().isAccepting()) {
			return;
		}
		tracker().accept(speaker, audio, whispering);
	}

	private SpeakerTracker tracker() {
		SpeakerTracker current = tracker;
		if (current == null) {
			synchronized (this) {
				current = tracker;
				if (current == null) {
					LiveCaptioner live = new LiveCaptioner(ClientSubtitles::sendLive,
							(speaker, text, whispering) -> Minecraft.getInstance().execute(() -> showLocalPartial(speaker, text)),
							() -> TranscriptionService.shared().liveIntervalMs());
					current = new SpeakerTracker(SpeechSegmenter.Settings.from(SvmSubtitles.config().segmentation), this::onSentence, live);
					tracker = current;
				}
			}
		}
		return current;
	}

	private static @Nullable CompletableFuture<@Nullable String> sendLive(UUID session, float[] audio) {
		TranscriptionService service = TranscriptionService.current();
		if (service == null || !service.supportsLive()) {
			return null;
		}
		return service.streamPartial(session, audio).thenApply(result -> result == null ? null : result.text());
	}

	private void onSentence(UUID speaker, Segment segment) {
		TranscriptionService.shared().submit(segment, transcript -> Minecraft.getInstance().execute(() -> showLocal(speaker, transcript)));
	}

	private void showLocal(UUID speaker, Transcript transcript) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		String name = nameOf(speaker);
		if (connection == null || name == null || !isActive()) {
			return;
		}
		SubtitlesConfig config = SvmSubtitles.config();
		String pmCommand = speaker.equals(self) ? null : PrivateMessageCommand.build(config.chat.privateMessageCommand, name, connection.getCommands());
		show(speaker, name, transcript.text(), config.chat.format, pmCommand, transcript.wasTranslated() ? transcript.language() : null);
	}

	private void showLocalPartial(UUID speaker, String text) {
		String name = nameOf(speaker);
		if (name != null && isActive()) {
			showPartial(speaker, name, text, SvmSubtitles.config().chat.format);
		}
	}

	/** A sentence that's still being spoken: in chat (one growing line), above the hotbar, or not at all. */
	private void showPartial(UUID speaker, String name, String text, String format) {
		SubtitlesConfig config = SvmSubtitles.config();
		livePartials.put(speaker, text);
		switch (config.live.display) {
			case "screen" -> LiveCaptionHud.INSTANCE.update(speaker, name, text);
			case "chat" -> chatLines.update(speaker, SubtitleMessage.build(format, config.chat.hoverText, name,
					text.replaceAll("[.…\\s]+$", "") + " ...", null, null, List.of()));
			default -> {
			}
		}
	}

	private @Nullable String nameOf(UUID speaker) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null) {
			return null;
		}
		PlayerInfo info = connection.getPlayerInfo(speaker);
		if (info != null) {
			return info.getProfile().name();
		}
		if (speaker.equals(self) && minecraft.player != null) {
			return minecraft.player.getGameProfile().name();
		}
		return null;
	}

	// ------------------------------------------------------------------ server mode

	/** A subtitle sent by the server. The server is not trusted blindly: text is sanitised and lengths are capped. */
	public void onPayload(SubtitlePayload payload) {
		String text = TextCleaner.sanitize(payload.text());
		String name = TextCleaner.sanitize(payload.name());
		if (text.isEmpty() || name.isEmpty()) {
			return;
		}
		if (payload.kind() == SubtitlePayload.PARTIAL) {
			showPartial(payload.speaker(), name, text, payload.format());
			return;
		}
		// Clicking a name only ever fills in the chat box; the player still has to press enter.
		String pm = payload.pmCommand().startsWith("/") ? TextCleaner.sanitize(payload.pmCommand()) + " " : null;
		String language = payload.language().isBlank() ? null : payload.language().replaceAll("[^A-Za-z-]", "");
		show(payload.speaker(), name, text, payload.format(), pm, language);
	}

	// ------------------------------------------------------------------ showing

	private void show(UUID speaker, String name, String text, String format, @Nullable String pmCommand, @Nullable String language) {
		Minecraft minecraft = Minecraft.getInstance();
		LiveCaptionHud.INSTANCE.clear(speaker);
		livePartials.remove(speaker);
		SubtitlesConfig config = SvmSubtitles.config();
		List<int[]> highlights = List.of();
		if (config.alerts.enabled && !speaker.equals(self) && minecraft.player != null) {
			highlights = AlertMatcher.find(text, AlertMatcher.terms(minecraft.player.getGameProfile().name(), config.alerts.ownName, config.alerts.words));
		}
		Component line = SubtitleMessage.build(format, config.chat.hoverText, name, text, pmCommand, language, highlights);
		if (!chatLines.finish(speaker, line)) {
			minecraft.gui.hud.getChat().addClientSystemMessage(line);
		}
		if (!highlights.isEmpty()) {
			playAlert(minecraft, config.alerts);
		}
	}

	private void playAlert(Minecraft minecraft, SubtitlesConfig.Alerts alerts) {
		long now = System.currentTimeMillis();
		if (now - lastAlertSound < 1_000 || alerts.volume <= 0) {
			return;
		}
		lastAlertSound = now;
		alertsPlayed++;
		SoundEvent sound = null;
		Identifier id = Identifier.tryParse(alerts.sound);
		if (id != null) {
			sound = BuiltInRegistries.SOUND_EVENT.getValue(id);
		}
		if (sound == null) {
			sound = SoundEvents.NOTE_BLOCK_PLING.value();
		}
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0f, (float) alerts.volume));
	}

	/** The live caption currently on screen for a speaker, if any (for other mods, narration and tests). */
	public @Nullable String liveCaption(UUID speaker) {
		return livePartials.get(speaker);
	}

	public int alertsPlayed() {
		return alertsPlayed;
	}

	// ------------------------------------------------------------------ lifecycle

	public void onJoin() {
		serverTranscribing = false;
		inWorld = true;
		ticks = 0;
	}

	public void onLeave() {
		inWorld = false;
		serverTranscribing = false;
		self = null;
		onlinePlayers.clear();
		LiveCaptionHud.INSTANCE.clearAll();
		chatLines.clear();
		livePartials.clear();
		resetTracker();
	}

	public void applyConfig() {
		resetTracker();
	}

	private synchronized void resetTracker() {
		SpeakerTracker current = tracker;
		tracker = null;
		if (current != null) {
			current.close();
		}
	}

	public void tick(Minecraft minecraft) {
		if (!inWorld || ticks++ % 20 != 0) {
			return;
		}
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null) {
			return;
		}
		if (minecraft.player != null) {
			self = minecraft.player.getUUID();
		}
		onlinePlayers.retainAll(connection.getOnlinePlayerIds());
		onlinePlayers.addAll(connection.getOnlinePlayerIds());
		if (isActive() && ticks % 100 == 1) {
			TranscriptionService.setPlayerNames(connection.getOnlinePlayers().stream().map(info -> info.getProfile().name()).toList());
		}

		Component notice;
		while ((notice = pendingNotices.poll()) != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(notice);
		}
	}

	/** Messages from the speech engine (model download, too slow, ...) go to chat. */
	public void onNotice(TranscriptionService.Notice notice) {
		Component message = SubtitleMessage.notice(notice.message(), notice.warning());
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (inWorld && minecraft.getConnection() != null) {
				minecraft.gui.hud.getChat().addClientSystemMessage(message);
			} else if (pendingNotices.size() < MAX_PENDING_NOTICES) {
				pendingNotices.add(message);
			}
		});
	}
}
