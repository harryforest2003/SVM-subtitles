package io.github.harryforest2003.svmsubtitles.client;

import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.audio.Segment;
import io.github.harryforest2003.svmsubtitles.audio.SpeakerTracker;
import io.github.harryforest2003.svmsubtitles.audio.SpeechSegmenter;
import io.github.harryforest2003.svmsubtitles.chat.PrivateMessageCommand;
import io.github.harryforest2003.svmsubtitles.chat.SubtitleMessage;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import io.github.harryforest2003.svmsubtitles.voice.ClientVoiceListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client mode: on servers that don't transcribe themselves, turn the voices this player hears into chat lines
 * that only this player sees. Nothing is sent to the server.
 */
public final class ClientSubtitles implements ClientVoiceListener {
	public static final ClientSubtitles INSTANCE = new ClientSubtitles();
	private static final int MAX_PENDING_NOTICES = 5;

	/** Players in the tab list; other audio (music from plugins, etc.) is ignored. Read on audio threads. */
	private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
	private final Queue<Component> pendingNotices = new ConcurrentLinkedQueue<>();
	private volatile boolean inWorld;
	private volatile boolean serverTranscribing;
	private volatile @Nullable UUID self;
	private volatile @Nullable SpeakerTracker tracker;
	private int ticks;

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
					current = new SpeakerTracker(SpeechSegmenter.Settings.from(SvmSubtitles.config().segmentation), this::onSentence);
					tracker = current;
				}
			}
		}
		return current;
	}

	private void onSentence(UUID speaker, Segment segment) {
		TranscriptionService.shared().submit(segment, text -> Minecraft.getInstance().execute(() -> show(speaker, text)));
	}

	private void show(UUID speaker, String text) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null || !isActive()) {
			return;
		}
		boolean isSelf = speaker.equals(self);
		String name = null;
		PlayerInfo info = connection.getPlayerInfo(speaker);
		if (info != null) {
			name = info.getProfile().name();
		} else if (isSelf && minecraft.player != null) {
			name = minecraft.player.getGameProfile().name();
		}
		if (name == null) {
			return;
		}
		SubtitlesConfig config = SvmSubtitles.config();
		String pmCommand = isSelf ? null : PrivateMessageCommand.build(config.chat.privateMessageCommand, name, connection.getCommands());
		minecraft.gui.hud.getChat().addClientSystemMessage(SubtitleMessage.build(config.chat, name, text, pmCommand));
	}

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
