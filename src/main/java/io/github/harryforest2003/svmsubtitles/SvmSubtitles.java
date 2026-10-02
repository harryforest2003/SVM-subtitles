package io.github.harryforest2003.svmsubtitles;

import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.network.SubtitlePayload;
import io.github.harryforest2003.svmsubtitles.network.SubtitlesStatusPayload;
import io.github.harryforest2003.svmsubtitles.server.ServerSubtitles;
import io.github.harryforest2003.svmsubtitles.server.SubtitlesCommand;
import io.github.harryforest2003.svmsubtitles.server.TtsCommand;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class SvmSubtitles implements ModInitializer {
	public static final String MOD_ID = "svm_subtitles";
	public static final Logger LOGGER = LoggerFactory.getLogger("SVM Subtitles");

	private static volatile SubtitlesConfig config;

	@Override
	public void onInitialize() {
		reloadConfig();
		PayloadTypeRegistry.clientboundPlay().register(SubtitlesStatusPayload.TYPE, SubtitlesStatusPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SubtitlePayload.TYPE, SubtitlePayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
			SubtitlesCommand.register(dispatcher);
			TtsCommand.register(dispatcher);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerSubtitles subtitles = ServerSubtitles.get();
			if (subtitles != null) {
				subtitles.tick();
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(ServerSubtitles::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(ServerSubtitles::stop);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerSubtitles.onJoin(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerSubtitles subtitles = ServerSubtitles.get();
			if (subtitles != null) {
				subtitles.forget(handler.player.getUUID());
			}
		});
		TranscriptionService.addStateListener(() -> {
			ServerSubtitles subtitles = ServerSubtitles.get();
			if (subtitles != null) {
				subtitles.broadcastStatus();
			}
		});
	}

	public static SubtitlesConfig config() {
		SubtitlesConfig current = config;
		return current != null ? current : reloadConfig();
	}

	public static synchronized SubtitlesConfig reloadConfig() {
		config = SubtitlesConfig.load(dataDir().resolve("config.json"));
		return config;
	}

	public static synchronized void saveConfig() {
		config().save(dataDir().resolve("config.json"));
	}

	public static Path dataDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
	}
}
