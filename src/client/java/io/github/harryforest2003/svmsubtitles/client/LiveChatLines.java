package io.github.harryforest2003.svmsubtitles.client;

import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.client.mixin.ChatComponentAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live captions inside chat: one line per speaker that grows word by word and then becomes the finished
 * subtitle, instead of a new chat line for every update. Game thread only.
 */
final class LiveChatLines {
	private final Map<UUID, GuiMessage> lines = new HashMap<>();

	void update(UUID speaker, Component line) {
		GuiMessage previous = lines.remove(speaker);
		if (previous != null) {
			GuiMessage replaced = replace(previous, line);
			if (replaced != null) {
				lines.put(speaker, replaced);
				return;
			}
		}
		ChatComponent chat = chat();
		chat.addClientSystemMessage(line);
		List<GuiMessage> all = ((ChatComponentAccessor) chat).svmSubtitles$allMessages();
		if (!all.isEmpty() && all.getFirst().content() == line) {
			lines.put(speaker, all.getFirst());
		}
	}

	/** Turns the speaker's live line into the finished subtitle. False if there is no live line to replace. */
	boolean finish(UUID speaker, Component line) {
		GuiMessage previous = lines.remove(speaker);
		if (previous == null || replace(previous, line) == null) {
			return false;
		}
		SvmSubtitles.LOGGER.info("[CHAT] {}", line.getString());
		return true;
	}

	void clear() {
		lines.clear();
	}

	private static @Nullable GuiMessage replace(GuiMessage old, Component line) {
		ChatComponent chat = chat();
		ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
		List<GuiMessage> all = accessor.svmSubtitles$allMessages();
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i) == old) {
				// A fresh timestamp keeps the line from fading out while someone is still talking.
				GuiMessage updated = new GuiMessage(Minecraft.getInstance().gui.hud.getGuiTicks(), line, null, old.source(), old.tag());
				all.set(i, updated);
				accessor.svmSubtitles$refreshTrimmedMessages();
				return updated;
			}
		}
		return null;
	}

	private static ChatComponent chat() {
		return Minecraft.getInstance().gui.hud.getChat();
	}
}
