package io.github.harryforest2003.svmsubtitles.chat;

import com.mojang.brigadier.CommandDispatcher;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** Works out what clicking a speaker's name should type into the chat box. */
public final class PrivateMessageCommand {
	/** Common private message commands, most likely first. Vanilla has msg, tell and w; plugins add the rest. */
	static final List<String> CANDIDATES = List.of("msg", "tell", "w", "whisper", "m", "pm", "dm", "message");

	private PrivateMessageCommand() {
	}

	/**
	 * @param configured "auto", "none", or a command such as "/tell {player} "
	 * @param dispatcher the command tree to search in "auto" mode (on a client: only the commands it may use)
	 * @return the text to suggest, or null to make the name not clickable
	 */
	public static @Nullable String build(String configured, String player, @Nullable CommandDispatcher<?> dispatcher) {
		String command = configured == null ? "" : configured.trim();
		String lower = command.toLowerCase(Locale.ROOT);
		if (command.isEmpty() || lower.equals("none") || lower.equals("off")) {
			return null;
		}
		if (lower.equals("auto")) {
			return "/" + detect(dispatcher) + " " + player + " ";
		}
		if (!command.startsWith("/")) {
			command = "/" + command;
		}
		if (command.contains("{player}")) {
			return command.replace("{player}", player) + " ";
		}
		return command + " " + player + " ";
	}

	static String detect(@Nullable CommandDispatcher<?> dispatcher) {
		if (dispatcher != null) {
			for (String candidate : CANDIDATES) {
				if (dispatcher.getRoot().getChild(candidate) != null) {
					return candidate;
				}
			}
		}
		return "msg";
	}
}
