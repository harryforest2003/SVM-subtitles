package io.github.harryforest2003.svmsubtitles.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.transcribe.TextCleaner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /tts <message>: speak typed text in voice chat, for players without a microphone. */
public final class TtsCommand {
	private TtsCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("tts")
				.then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(TtsCommand::speak)));
	}

	private static int speak(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SubtitlesConfig.Tts config = SvmSubtitles.config().tts;
		ServerSubtitles subtitles = ServerSubtitles.get();
		if (subtitles == null || !config.enabled) {
			ctx.getSource().sendFailure(Component.literal("Text-to-speech is turned off on this server."));
			return 0;
		}
		String text = TextCleaner.sanitize(StringArgumentType.getString(ctx, "message"));
		if (text.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("Type something to say."));
			return 0;
		}
		if (text.length() > config.maxLength) {
			ctx.getSource().sendFailure(Component.literal("That's too long; the limit is " + config.maxLength + " characters."));
			return 0;
		}
		int wait = subtitles.ttsCooldown(player.getUUID());
		if (wait > 0) {
			ctx.getSource().sendFailure(Component.literal("Wait " + wait + (wait == 1 ? " second" : " seconds") + " before using /tts again."));
			return 0;
		}
		subtitles.speak(player, text, player::sendSystemMessage);
		return 1;
	}
}
