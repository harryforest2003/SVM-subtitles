package io.github.harryforest2003.svmsubtitles.dev;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;

import java.util.function.Consumer;

/** Keeps Simple Voice Chat from opening the real microphone during the self test (no OS permission prompt). */
public final class SelfTestVoicechatPlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "svm_subtitles_dev";
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void registerEvents(EventRegistration registration) {
		if (!Boolean.getBoolean("svmdev.selftest")) {
			return;
		}
		try {
			Class startMic = Class.forName("de.maxhenkel.voicechat.api.internal.events.StartMicEvent");
			registration.registerEvent(startMic, (Consumer<Event>) Event::cancel);
		} catch (ClassNotFoundException e) {
			SelfTestClient.LOGGER.warn("[selftest] StartMicEvent not found, the microphone may be opened");
		}
	}
}
