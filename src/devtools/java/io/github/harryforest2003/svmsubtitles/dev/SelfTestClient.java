package io.github.harryforest2003.svmsubtitles.dev;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.client.ClientSubtitles;
import io.github.harryforest2003.svmsubtitles.server.ServerSubtitles;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import io.github.harryforest2003.svmsubtitles.voice.SubtitlesVoicechatPlugin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * ./gradlew runSelfTest: opens a test world and speaks the sample sentence twice, first through server mode
 * (Opus packets into the integrated server, broadcast back as chat) and then through client mode (local
 * subtitles). Everything that reaches chat is logged as "[CHAT] ...", then the game quits.
 */
public final class SelfTestClient implements ClientModInitializer {
	static final Logger LOGGER = LoggerFactory.getLogger("SVM Subtitles Self Test");
	private static final String WORLD = "svm-selftest";

	private boolean started;

	@Override
	public void onInitializeClient() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				message.visit((style, text) -> {
					if (style.getClickEvent() instanceof ClickEvent.SuggestCommand(String command)) {
						LOGGER.info("[selftest] clicking \"{}\" types \"{}\"", text, command);
					}
					return Optional.empty();
				}, Style.EMPTY);
			}
		});

		if (!Boolean.getBoolean("svmdev.selftest")) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (!started && minecraft.gui.screen() instanceof TitleScreen) {
				started = true;
				openWorld(minecraft);
				Thread.ofPlatform().daemon().name("SVM Subtitles self test").start(() -> run(minecraft));
			}
		});
	}

	private static void openWorld(Minecraft minecraft) {
		if (minecraft.getLevelSource().levelExists(WORLD)) {
			minecraft.createWorldOpenFlows().openWorld(WORLD, () -> minecraft.gui.setScreen(new TitleScreen()));
		} else {
			LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
					WorldPresets::createTestWorldDimensions, new TitleScreen());
		}
	}

	private static void run(Minecraft minecraft) {
		try {
			waitFor("player in world", 180, () -> minecraft.player != null && minecraft.getSingleplayerServer() != null);
			IntegratedServer server = minecraft.getSingleplayerServer();
			ServerPlayer player = call(minecraft, () -> server.getPlayerList().getPlayer(minecraft.player.getUUID()));
			waitFor("voice chat server", 60, () -> SubtitlesVoicechatPlugin.serverApi() != null);
			waitFor("speech recognition", 300, () -> TranscriptionService.shared().state() != TranscriptionService.State.STARTING);
			LOGGER.info("[selftest] speech recognition {}", TranscriptionService.shared().status());

			LOGGER.info("[selftest] phase 1: server mode. Client defers to server: {}", !ClientSubtitles.INSTANCE.isActive());
			VoicechatServerApi api = SubtitlesVoicechatPlugin.serverApi();
			FakeVoice.speakToServer(api, ServerSubtitles.get(), player, false);
			Thread.sleep(8_000);

			LOGGER.info("[selftest] phase 2: player opted out, expecting no subtitle");
			ServerSubtitles.get().preferences().setOptedOut(player.getUUID(), true);
			FakeVoice.speakToServer(api, ServerSubtitles.get(), player, false);
			Thread.sleep(6_000);
			ServerSubtitles.get().preferences().setOptedOut(player.getUUID(), false);

			LOGGER.info("[selftest] phase 3: client mode (server mode switched off)");
			call(minecraft, () -> {
				SvmSubtitles.config().server.enabled = false;
				ServerSubtitles.reload(server);
				return null;
			});
			waitFor("client mode", 10, ClientSubtitles.INSTANCE::isActive);
			FakeVoice.speakToClient();
			Thread.sleep(8_000);

			LOGGER.info("[selftest] {}", TranscriptionService.shared().status());
			LOGGER.info("[selftest] done");
		} catch (Throwable t) {
			LOGGER.error("[selftest] failed", t);
		} finally {
			minecraft.execute(minecraft::stop);
		}
	}

	private static void waitFor(String what, int seconds, java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + seconds * 1000L;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new IllegalStateException("Timed out waiting for " + what);
			}
			Thread.sleep(250);
		}
		LOGGER.info("[selftest] ready: {}", what);
	}

	private static <T> T call(Minecraft minecraft, Callable<T> task) throws Exception {
		return minecraft.submit(() -> {
			try {
				return task.call();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).get();
	}
}
