package io.github.harryforest2003.svmsubtitles.chat;

import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jspecify.annotations.Nullable;

/** Builds the chat line for a subtitle from the configured format. */
public final class SubtitleMessage {
	private static final String COLOR_CODES = "0123456789abcdef";
	private static final String FORMAT_CODES = "klmno";

	private SubtitleMessage() {
	}

	/**
	 * @param pmCommand what clicking the name types into chat, or null for a plain name
	 */
	public static MutableComponent build(SubtitlesConfig.Chat chat, String player, String text, @Nullable String pmCommand) {
		MutableComponent root = Component.empty();
		String format = chat.format;
		StringBuilder pending = new StringBuilder();
		Style style = Style.EMPTY;

		for (int i = 0; i < format.length(); i++) {
			char c = format.charAt(i);
			if ((c == '&' || c == '§') && i + 1 < format.length()) {
				char code = Character.toLowerCase(format.charAt(i + 1));
				if (code == '#' && i + 8 <= format.length() && isHex(format, i + 2, i + 8)) {
					flush(root, pending, style);
					style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(format.substring(i + 2, i + 8), 16)));
					i += 7;
					continue;
				}
				ChatFormatting formatting = ChatFormatting.getByCode(code);
				if (formatting != null) {
					flush(root, pending, style);
					if (COLOR_CODES.indexOf(code) >= 0) {
						style = Style.EMPTY.withColor(formatting);
					} else if (FORMAT_CODES.indexOf(code) >= 0) {
						style = style.applyFormat(formatting);
					} else {
						style = Style.EMPTY;
					}
					i++;
					continue;
				}
			}
			if (format.startsWith("{player}", i)) {
				flush(root, pending, style);
				root.append(playerName(player, style, pmCommand, chat.hoverText));
				i += "{player}".length() - 1;
				continue;
			}
			if (format.startsWith("{text}", i)) {
				flush(root, pending, style);
				root.append(Component.literal(text).setStyle(style));
				i += "{text}".length() - 1;
				continue;
			}
			pending.append(c);
		}
		flush(root, pending, style);
		return root;
	}

	/** A status line from the mod itself, e.g. "model downloaded". */
	public static MutableComponent notice(String message, boolean warning) {
		return Component.literal("[Subtitles] ").withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal(message).withStyle(warning ? ChatFormatting.GOLD : ChatFormatting.GRAY));
	}

	private static MutableComponent playerName(String player, Style style, @Nullable String pmCommand, String hoverText) {
		Style nameStyle = style.withInsertion(player);
		if (pmCommand != null) {
			nameStyle = nameStyle.withClickEvent(new ClickEvent.SuggestCommand(pmCommand));
			if (hoverText != null && !hoverText.isBlank()) {
				nameStyle = nameStyle.withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText.replace("{player}", player))));
			}
		}
		return Component.literal(player).setStyle(nameStyle);
	}

	private static void flush(MutableComponent root, StringBuilder pending, Style style) {
		if (!pending.isEmpty()) {
			root.append(Component.literal(pending.toString()).setStyle(style));
			pending.setLength(0);
		}
	}

	private static boolean isHex(String s, int from, int to) {
		for (int i = from; i < to; i++) {
			if (Character.digit(s.charAt(i), 16) < 0) {
				return false;
			}
		}
		return true;
	}
}
