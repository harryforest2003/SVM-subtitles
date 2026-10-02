package io.github.harryforest2003.svmsubtitles.client;

import com.mojang.brigadier.CommandDispatcher;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.chat.SubtitleMessage;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

/** /clientsubtitles on|off|status|test|reload, for subtitles this client makes itself. */
final class ClientSubtitlesCommand {
	private ClientSubtitlesCommand() {
	}

	static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("clientsubtitles")
				.executes(ctx -> status(ctx.getSource()))
				.then(ClientCommands.literal("status").executes(ctx -> status(ctx.getSource())))
				.then(ClientCommands.literal("on").executes(ctx -> setEnabled(ctx.getSource(), true)))
				.then(ClientCommands.literal("off").executes(ctx -> setEnabled(ctx.getSource(), false)))
				.then(ClientCommands.literal("test").executes(ctx -> test(ctx.getSource())))
				.then(ClientCommands.literal("reload").executes(ctx -> reload(ctx.getSource()))));
	}

	private static int status(FabricClientCommandSource source) {
		ClientSubtitles subtitles = ClientSubtitles.INSTANCE;
		String mode;
		if (!SvmSubtitles.config().client.enabled) {
			mode = "Local subtitles are off (/clientsubtitles on to enable).";
		} else if (!subtitles.isActive()) {
			mode = "This server makes the subtitles itself, so your PC isn't doing any work.";
		} else {
			mode = "This PC turns the voices you hear into subtitles.";
		}
		source.sendFeedback(SubtitleMessage.notice(mode, false));
		TranscriptionService service = TranscriptionService.current();
		if (service != null) {
			source.sendFeedback(SubtitleMessage.notice("Speech recognition " + service.status(), false));
		}
		return 1;
	}

	private static int setEnabled(FabricClientCommandSource source, boolean enabled) {
		SvmSubtitles.config().client.enabled = enabled;
		SvmSubtitles.saveConfig();
		ClientSubtitles.INSTANCE.applyConfig();
		source.sendFeedback(SubtitleMessage.notice(enabled ? "Local subtitles turned on." : "Local subtitles turned off.", false));
		return 1;
	}

	private static int test(FabricClientCommandSource source) {
		source.sendFeedback(SubtitleMessage.notice("Transcribing a sample sentence...", false));
		Minecraft minecraft = source.getClient();
		TranscriptionService.shared().selfTest(result ->
				minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(SubtitleMessage.notice(result, false))));
		return 1;
	}

	private static int reload(FabricClientCommandSource source) {
		SvmSubtitles.reloadConfig();
		TranscriptionService.restartShared();
		ClientSubtitles.INSTANCE.applyConfig();
		source.sendFeedback(SubtitleMessage.notice("Reloaded config/svm_subtitles/config.json.", false));
		return 1;
	}
}
