package io.github.harryforest2003.svmsubtitles.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;

/** The settings button in Mod Menu. The screen is built with Cloth Config when it's installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> {
			if (FabricLoader.getInstance().isModLoaded("cloth-config")) {
				return SettingsScreen.create(parent);
			}
			return new AlertScreen(() -> Minecraft.getInstance().gui.setScreen(parent),
					Component.literal("SVM Subtitles settings"),
					Component.literal("Install Cloth Config to change the settings here, or edit config/svm_subtitles/config.json and run /clientsubtitles reload."));
		};
	}
}
