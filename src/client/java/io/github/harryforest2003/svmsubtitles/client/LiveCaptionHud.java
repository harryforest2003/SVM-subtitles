package io.github.harryforest2003.svmsubtitles.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live captions above the hotbar, one line per person who is talking right now. Updated on the game thread
 * only; each line disappears when the finished sentence lands in chat or a few seconds after the last update.
 */
final class LiveCaptionHud implements HudElement {
	static final LiveCaptionHud INSTANCE = new LiveCaptionHud();
	private static final long EXPIRE_MS = 4_000;
	private static final int MAX_SPEAKERS = 4;

	private record Line(String name, String text, long updated) {
	}

	private final Map<UUID, Line> lines = new LinkedHashMap<>();

	private LiveCaptionHud() {
	}

	void update(UUID speaker, String name, String text) {
		lines.remove(speaker);
		lines.put(speaker, new Line(name, text, System.currentTimeMillis()));
		while (lines.size() > MAX_SPEAKERS) {
			lines.remove(lines.keySet().iterator().next());
		}
	}

	@Nullable String text(UUID speaker) {
		Line line = lines.get(speaker);
		return line == null ? null : line.text();
	}

	void clear(UUID speaker) {
		lines.remove(speaker);
	}

	void clearAll() {
		lines.clear();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (lines.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		lines.values().removeIf(line -> now - line.updated() > EXPIRE_MS);
		Font font = Minecraft.getInstance().font;
		int maxWidth = Math.min(graphics.guiWidth() - 40, 360);
		List<FormattedCharSequence> rows = new ArrayList<>();
		for (Line line : lines.values()) {
			Component caption = Component.literal(line.name() + ": ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal(line.text()).withStyle(ChatFormatting.WHITE));
			List<FormattedCharSequence> wrapped = font.split(caption, maxWidth);
			// Long sentences keep only their newest two lines, so the latest words stay visible.
			rows.addAll(wrapped.subList(Math.max(0, wrapped.size() - 2), wrapped.size()));
		}
		int y = graphics.guiHeight() - 72 - rows.size() * 11;
		for (FormattedCharSequence row : rows) {
			int width = font.width(row);
			int x = (graphics.guiWidth() - width) / 2;
			graphics.fill(x - 3, y - 2, x + width + 3, y + 9, 0x99000000);
			graphics.text(font, row, x, y, 0xFFFFFFFF, false);
			y += 11;
		}
	}
}
