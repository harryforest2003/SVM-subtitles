package io.github.harryforest2003.svmsubtitles.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.server.ServerSubtitles;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Simple Voice Chat entrypoint ("voicechat" in fabric.mod.json). Listens with a low priority, so audio that
 * another plugin cancels (muted players, for example) never reaches us, just like it never reaches listeners.
 */
public final class SubtitlesVoicechatPlugin implements VoicechatPlugin {
	/** Players can turn text-to-speech up or down separately in Simple Voice Chat's volume settings. */
	public static final String TTS_CATEGORY = "svm_tts";
	private static final int PRIORITY = -100;

	private static volatile @Nullable VoicechatServerApi serverApi;
	private static volatile @Nullable ClientVoiceListener clientListener;
	private static volatile long lastErrorLog;

	public static @Nullable VoicechatServerApi serverApi() {
		return serverApi;
	}

	public static void setClientListener(ClientVoiceListener listener) {
		clientListener = listener;
	}

	@Override
	public String getPluginId() {
		return SvmSubtitles.MOD_ID;
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
			VoicechatServerApi api = event.getVoicechat();
			serverApi = api;
			api.registerVolumeCategory(api.volumeCategoryBuilder()
					.setId(TTS_CATEGORY)
					.setName("Text to speech")
					.setDescription("Messages players typed with /tts")
					.build());
		});
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> serverApi = null);
		registration.registerEvent(MicrophonePacketEvent.class, event -> safely(() -> {
			ServerSubtitles subtitles = ServerSubtitles.get();
			if (subtitles != null) {
				subtitles.onMicrophonePacket(event);
			}
		}), PRIORITY);
		registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
			ServerSubtitles subtitles = ServerSubtitles.get();
			if (subtitles != null) {
				subtitles.forget(event.getPlayerUuid());
			}
		});

		registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class,
				event -> received(event.getId(), event.getRawAudio(), event.isWhispering()), PRIORITY);
		registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class,
				event -> received(event.getId(), event.getRawAudio(), false), PRIORITY);
		registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class,
				event -> received(event.getId(), event.getRawAudio(), false), PRIORITY);
		registration.registerEvent(ClientSoundEvent.class, event -> safely(() -> {
			ClientVoiceListener listener = clientListener;
			short[] audio = event.getRawAudio();
			if (listener != null && audio != null) {
				listener.onOwnVoice(audio, event.isWhispering());
			}
		}), PRIORITY);
	}

	private static void received(UUID speaker, short @Nullable [] audio, boolean whispering) {
		ClientVoiceListener listener = clientListener;
		if (listener != null && audio != null) {
			safely(() -> listener.onReceive(speaker, audio, whispering));
		}
	}

	/** Voice events arrive ~50 times a second per speaker, so a bug must not flood the log. */
	private static void safely(Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			long now = System.currentTimeMillis();
			if (now - lastErrorLog > 10_000) {
				lastErrorLog = now;
				SvmSubtitles.LOGGER.error("Error while handling voice audio", t);
			}
		}
	}
}
