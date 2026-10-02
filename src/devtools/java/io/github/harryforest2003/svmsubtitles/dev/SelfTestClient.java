package io.github.harryforest2003.svmsubtitles.dev;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.client.ClientSubtitles;
import io.github.harryforest2003.svmsubtitles.client.ModMenuIntegration;
import io.github.harryforest2003.svmsubtitles.client.mixin.ChatComponentAccessor;
import io.github.harryforest2003.svmsubtitles.network.SubtitlePayload;
import io.github.harryforest2003.svmsubtitles.server.ServerSubtitles;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import io.github.harryforest2003.svmsubtitles.voice.SubtitlesVoicechatPlugin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
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
		// Point the test at a companion server, if one is running (see README: Building).
		String remote = System.getenv("SVMDEV_REMOTE_URL");
		if (remote != null) {
			SvmSubtitles.config().transcription.remote.url = remote;
			SvmSubtitles.config().transcription.remote.apiKey = System.getenv().getOrDefault("SVMDEV_REMOTE_KEY", "");
		}
		SvmSubtitles.config().alerts.words = new java.util.ArrayList<>(java.util.List.of("country"));
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (!started && minecraft.gui.screen() instanceof TitleScreen) {
				started = true;
				openWorld(minecraft);
				Thread.ofPlatform().daemon().name("SVM Subtitles self test").start(() -> run(minecraft));
			}
		});
	}

	/** A brand-new world every run, so reopening an old one never stops at a confirmation screen. */
	private static void openWorld(Minecraft minecraft) {
		Path old = minecraft.getLevelSource().getLevelPath(WORLD);
		if (Files.exists(old)) {
			try (var files = Files.walk(old)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			} catch (java.io.IOException e) {
				LOGGER.warn("[selftest] could not delete the old test world", e);
			}
		}
		LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
		minecraft.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
				WorldPresets::createTestWorldDimensions, new TitleScreen());
	}

	private static void run(Minecraft minecraft) {
		try {
			waitFor("player in world", 180, () -> minecraft.player != null && minecraft.getSingleplayerServer() != null);
			IntegratedServer server = minecraft.getSingleplayerServer();
			UUID me = minecraft.player.getUUID();
			ServerPlayer player = call(minecraft, () -> server.getPlayerList().getPlayer(me));
			waitFor("voice chat server", 60, () -> SubtitlesVoicechatPlugin.serverApi() != null);
			waitFor("speech recognition", 300, () -> TranscriptionService.shared().state() != TranscriptionService.State.STARTING);
			LOGGER.info("[selftest] speech recognition {}", TranscriptionService.shared().status());
			VoicechatServerApi api = SubtitlesVoicechatPlugin.serverApi();

			LOGGER.info("[selftest] phase 1: server mode with live captions. Client defers to server: {}", !ClientSubtitles.INSTANCE.isActive());
			int lines = ServerSubtitles.get().history().recent(null, 1000).size();
			try (LiveWatcher ignored = new LiveWatcher(minecraft, me)) {
				FakeVoice.speakToServer(api, ServerSubtitles.get(), player, false);
				waitFor("the finished sentence", 60, () -> ServerSubtitles.get().history().recent(null, 1000).size() > lines);
			}
			LOGGER.info("[selftest] chat lines holding that sentence: {} (live caption display: {})",
					countChatLines(minecraft, "fellow Americans"), SvmSubtitles.config().live.display);

			// Alerts ignore your own voice, so pretend another player said our name.
			int alertsBefore = ClientSubtitles.INSTANCE.alertsPlayed();
			call(minecraft, () -> {
				ClientSubtitles.INSTANCE.onPayload(new SubtitlePayload(UUID.randomUUID(), "Steve",
						"hey " + minecraft.player.getGameProfile().name() + ", come look at this",
						SvmSubtitles.config().chat.format, "/msg Steve ", "", SubtitlePayload.FINAL));
				return null;
			});
			LOGGER.info("[selftest] name alert played for Steve's line: {}", ClientSubtitles.INSTANCE.alertsPlayed() > alertsBefore);

			LOGGER.info("[selftest] phase 2: player opted out, expecting no subtitle");
			ServerSubtitles.get().preferences().setOptedOut(me, true);
			FakeVoice.speakToServer(api, ServerSubtitles.get(), player, false);
			Thread.sleep(5_000);
			ServerSubtitles.get().preferences().setOptedOut(me, false);

			String french = System.getenv("SVMDEV_FRENCH_WAV");
			if (french != null) {
				LOGGER.info("[selftest] phase 3: French speech, translated into English");
				SvmSubtitles.config().transcription.translateToEnglish = true;
				int before = ServerSubtitles.get().history().recent(null, 1000).size();
				FakeVoice.speakToServer(api, ServerSubtitles.get(), player, false, Path.of(french));
				waitFor("the translated sentence", 60, () -> ServerSubtitles.get().history().recent(null, 1000).size() > before);
				SvmSubtitles.config().transcription.translateToEnglish = false;
			}

			LOGGER.info("[selftest] phase 4: /tts");
			call(minecraft, () -> {
				server.execute(() -> server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "tts Hello from the self test"));
				return null;
			});
			Thread.sleep(6_000);

			LOGGER.info("[selftest] phase 5: /subtitles history");
			call(minecraft, () -> {
				server.execute(() -> server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "subtitles history"));
				return null;
			});
			Thread.sleep(2_000);

			LOGGER.info("[selftest] phase 6: settings screen");
			call(minecraft, () -> {
				Screen screen = new ModMenuIntegration().getModConfigScreenFactory().create(null);
				minecraft.gui.setScreen(screen);
				LOGGER.info("[selftest] opened settings screen: {}", screen.getTitle().getString());
				return null;
			});
			Thread.sleep(2_000);
			call(minecraft, () -> {
				minecraft.gui.setScreen(null);
				return null;
			});

			LOGGER.info("[selftest] phase 7: client mode (server mode switched off)");
			call(minecraft, () -> {
				SvmSubtitles.config().server.enabled = false;
				ServerSubtitles.reload(server);
				return null;
			});
			waitFor("client mode", 10, ClientSubtitles.INSTANCE::isActive);
			try (LiveWatcher ignored = new LiveWatcher(minecraft, me)) {
				FakeVoice.speakToClient();
				Thread.sleep(6_000);
			}

			LOGGER.info("[selftest] {}", TranscriptionService.shared().status());
			LOGGER.info("[selftest] done");
		} catch (Throwable t) {
			LOGGER.error("[selftest] failed", t);
		} finally {
			minecraft.execute(minecraft::stop);
		}
	}

	private static long countChatLines(Minecraft minecraft, String text) throws Exception {
		return call(minecraft, () -> ((ChatComponentAccessor) minecraft.gui.hud.getChat()).svmSubtitles$allMessages().stream()
				.filter(message -> message.content().getString().contains(text)).count());
	}

	/** Logs every change of the on-screen live caption while it runs. */
	private static final class LiveWatcher implements AutoCloseable {
		private final Thread thread;
		private volatile boolean running = true;

		LiveWatcher(Minecraft minecraft, UUID speaker) {
			thread = Thread.ofPlatform().daemon().start(() -> {
				String last = null;
				while (running) {
					try {
						String now = minecraft.submit(() -> ClientSubtitles.INSTANCE.liveCaption(speaker)).get();
						if (now != null && !now.equals(last)) {
							LOGGER.info("[selftest] live caption: {}", now);
						}
						last = now;
						Thread.sleep(150);
					} catch (Exception e) {
						return;
					}
				}
			});
		}

		@Override
		public void close() {
			running = false;
			thread.interrupt();
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
