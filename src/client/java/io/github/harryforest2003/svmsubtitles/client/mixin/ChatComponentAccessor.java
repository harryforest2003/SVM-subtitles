package io.github.harryforest2003.svmsubtitles.client.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Lets a live caption line in chat be updated in place instead of adding a new line for every word. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Accessor("allMessages")
	List<GuiMessage> svmSubtitles$allMessages();

	@Invoker("refreshTrimmedMessages")
	void svmSubtitles$refreshTrimmedMessages();
}
