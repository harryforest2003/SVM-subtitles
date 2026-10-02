package io.github.harryforest2003.svmsubtitles.client;

import io.github.harryforest2003.svmsubtitles.network.SubtitlesStatusPayload;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import io.github.harryforest2003.svmsubtitles.voice.SubtitlesVoicechatPlugin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class SvmSubtitlesClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientSubtitles subtitles = ClientSubtitles.INSTANCE;
		SubtitlesVoicechatPlugin.setClientListener(subtitles);
		TranscriptionService.addNoticeListener(subtitles::onNotice);

		ClientPlayNetworking.registerGlobalReceiver(SubtitlesStatusPayload.TYPE,
				(payload, context) -> subtitles.setServerTranscribing(payload.serverTranscribing()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> subtitles.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> subtitles.onLeave());
		ClientTickEvents.END_CLIENT_TICK.register(subtitles::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> ClientSubtitlesCommand.register(dispatcher));
	}
}
