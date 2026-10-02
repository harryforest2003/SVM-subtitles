package io.github.harryforest2003.svmsubtitles.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.chat.SubtitleMessage;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * /subtitles             what's happening and the options below
 * /subtitles optout|optin  stop or resume transcribing your own voice
 * /subtitles hide|show     stop or resume seeing subtitles
 * /subtitles status|test|reload  (operators)
 */
public final class SubtitlesCommand {
	private SubtitlesCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("subtitles")
				.executes(SubtitlesCommand::help)
				.then(Commands.literal("optout").executes(ctx -> setOptedOut(ctx, true)))
				.then(Commands.literal("optin").executes(ctx -> setOptedOut(ctx, false)))
				.then(Commands.literal("hide").executes(ctx -> setHidden(ctx, true)))
				.then(Commands.literal("show").executes(ctx -> setHidden(ctx, false)))
				.then(Commands.literal("status")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(SubtitlesCommand::status))
				.then(Commands.literal("test")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(SubtitlesCommand::test))
				.then(Commands.literal("reload")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(SubtitlesCommand::reload)));
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerSubtitles subtitles = ServerSubtitles.get();
		if (subtitles == null) {
			source.sendSuccess(() -> SubtitleMessage.notice("Voice subtitles are turned off on this server.", false), false);
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		if (player != null) {
			boolean optedOut = subtitles.preferences().isOptedOut(player.getUUID());
			boolean hidden = subtitles.preferences().isHidden(player.getUUID());
			source.sendSuccess(() -> SubtitleMessage.notice("Your voice is " + (optedOut ? "not " : "") + "being transcribed, and you "
					+ (hidden ? "don't see" : "see") + " other players' subtitles.", false), false);
		}
		source.sendSuccess(() -> line("/subtitles optout", "stop turning your voice into chat messages"), false);
		source.sendSuccess(() -> line("/subtitles optin", "turn your voice into chat messages again"), false);
		source.sendSuccess(() -> line("/subtitles hide", "stop showing voice subtitles to you"), false);
		source.sendSuccess(() -> line("/subtitles show", "show voice subtitles again"), false);
		return 1;
	}

	private static int setOptedOut(CommandContext<CommandSourceStack> ctx, boolean optedOut) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerSubtitles subtitles = ServerSubtitles.get();
		if (subtitles == null) {
			ctx.getSource().sendFailure(Component.literal("Voice subtitles are turned off on this server."));
			return 0;
		}
		subtitles.preferences().setOptedOut(player.getUUID(), optedOut);
		ctx.getSource().sendSuccess(() -> SubtitleMessage.notice(optedOut
				? "Your voice will no longer be turned into chat messages."
				: "Your voice will be turned into chat messages again.", false), false);
		return 1;
	}

	private static int setHidden(CommandContext<CommandSourceStack> ctx, boolean hidden) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerSubtitles subtitles = ServerSubtitles.get();
		if (subtitles == null) {
			ctx.getSource().sendFailure(Component.literal("Voice subtitles are turned off on this server."));
			return 0;
		}
		subtitles.preferences().setHidden(player.getUUID(), hidden);
		ctx.getSource().sendSuccess(() -> SubtitleMessage.notice(hidden
				? "Voice subtitles are now hidden for you."
				: "Voice subtitles are shown to you again.", false), false);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		String status = ServerSubtitles.get() == null
				? "server mode is turned off (server.enabled = false)"
				: TranscriptionService.shared().status();
		ctx.getSource().sendSuccess(() -> SubtitleMessage.notice("Speech recognition " + status, false), false);
		return 1;
	}

	private static int test(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();
		source.sendSuccess(() -> SubtitleMessage.notice("Transcribing a sample sentence...", false), false);
		TranscriptionService.shared().selfTest(result ->
				server.execute(() -> source.sendSuccess(() -> SubtitleMessage.notice(result, false), false)));
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ctx.getSource().getServer();
		SvmSubtitles.reloadConfig();
		TranscriptionService.restartShared();
		ServerSubtitles.reload(server);
		ctx.getSource().sendSuccess(() -> SubtitleMessage.notice("Reloaded config/svm_subtitles/config.json.", false), true);
		return 1;
	}

	private static Component line(String command, String description) {
		return Component.literal(command).withStyle(ChatFormatting.AQUA)
				.append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY));
	}
}
