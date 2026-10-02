package io.github.harryforest2003.svmsubtitles.client;

import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.server.ServerSubtitles;
import io.github.harryforest2003.svmsubtitles.transcribe.TranscriptionService;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;

/**
 * Settings screen (Cloth Config). Edits the same config/svm_subtitles/config.json. The "Hosting" page applies
 * to singleplayer and LAN worlds you host; dedicated servers have their own copy of the file.
 */
final class SettingsScreen {
	private SettingsScreen() {
	}

	static Screen create(Screen parent) {
		SubtitlesConfig config = SvmSubtitles.config();
		SubtitlesConfig defaults = new SubtitlesConfig();
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("SVM Subtitles"))
				.setSavingRunnable(SettingsScreen::apply);
		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory general = builder.getOrCreateCategory(Component.literal("Subtitles"));
		general.addEntry(entries.startBooleanToggle(Component.literal("Make subtitles on this PC"), config.client.enabled)
				.setDefaultValue(defaults.client.enabled)
				.setTooltip(Component.literal("On servers that don't run this mod, turn the voices you hear into subtitles only you see."))
				.setSaveConsumer(v -> config.client.enabled = v).build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Show what I say"), config.client.includeOwnVoice)
				.setDefaultValue(defaults.client.includeOwnVoice)
				.setSaveConsumer(v -> config.client.includeOwnVoice = v).build());
		general.addEntry(entries.startBooleanToggle(Component.literal("Live captions while people talk"), config.live.enabled)
				.setDefaultValue(defaults.live.enabled)
				.setTooltip(Component.literal("Word by word as they speak. Runs on the companion speech server, never on your PC."))
				.setSaveConsumer(v -> config.live.enabled = v).build());
		general.addEntry(entries.startSelector(Component.literal("Show live captions"), new String[]{"chat", "screen", "off"}, config.live.display)
				.setDefaultValue(defaults.live.display)
				.setNameProvider(v -> Component.literal(switch (v) {
					case "chat" -> "In chat (one line that fills in)";
					case "screen" -> "On screen, above the hotbar";
					default -> "Off (finished lines only)";
				}))
				.setSaveConsumer(v -> config.live.display = v).build());
		general.addEntry(entries.startIntSlider(Component.literal("Live caption updates (ms)"), config.live.intervalMs, 300, 3000)
				.setDefaultValue(defaults.live.intervalMs)
				.setSaveConsumer(v -> config.live.intervalMs = v).build());
		general.addEntry(entries.startStrField(Component.literal("Chat format"), config.chat.format)
				.setDefaultValue(defaults.chat.format)
				.setTooltip(Component.literal("&-colour codes work. {player} is the clickable name, {text} what they said."))
				.setSaveConsumer(v -> config.chat.format = v).build());
		general.addEntry(entries.startStrField(Component.literal("Private message command"), config.chat.privateMessageCommand)
				.setDefaultValue(defaults.chat.privateMessageCommand)
				.setTooltip(Component.literal("\"auto\" finds /msg, /tell, /w ... Or e.g. \"/m {player} \", or \"none\"."))
				.setSaveConsumer(v -> config.chat.privateMessageCommand = v).build());

		ConfigCategory speech = builder.getOrCreateCategory(Component.literal("Speech recognition"));
		speech.addEntry(entries.startSelector(Component.literal("Where speech is recognised"),
						new String[]{"auto", "remote", "local", "off"}, config.transcription.backend)
				.setDefaultValue(defaults.transcription.backend)
				.setTooltip(Component.literal("auto: the speech server below if set, otherwise this PC if it's fast enough."),
						Component.literal("remote: always the speech server (no work for your PC)."),
						Component.literal("local: always this PC."))
				.setSaveConsumer(v -> config.transcription.backend = v).build());
		speech.addEntry(entries.startStrField(Component.literal("Speech server URL"), config.transcription.remote.url)
				.setDefaultValue("")
				.setTooltip(Component.literal("e.g. https://you-svm-subtitles.hf.space/v1/audio/transcriptions"),
						Component.literal("Must be https:// unless the server is on your own network."))
				.setSaveConsumer(v -> config.transcription.remote.url = v.trim()).build());
		boolean hasKey = !config.transcription.remote.apiKey.isEmpty();
		speech.addEntry(entries.startStrField(Component.literal(hasKey ? "New API key (one is set)" : "API key (none set)"), "")
				.setDefaultValue("")
				.setTooltip(Component.literal("Never shown again after saving, so it can't leak in a screenshot or stream."),
						Component.literal("Leave empty to keep the current key."))
				.setSaveConsumer(v -> {
					if (!v.isBlank()) {
						config.transcription.remote.apiKey = v.trim();
					}
				}).build());
		speech.addEntry(entries.startBooleanToggle(Component.literal("Forget the API key"), false)
				.setDefaultValue(false)
				.setSaveConsumer(v -> {
					if (v) {
						config.transcription.remote.apiKey = "";
					}
				}).build());
		speech.addEntry(entries.startStrField(Component.literal("Language"), config.transcription.language)
				.setDefaultValue(defaults.transcription.language)
				.setTooltip(Component.literal("\"en\", \"de\", \"es\", ... or \"auto\". Needs a multilingual model for anything but English."))
				.setSaveConsumer(v -> config.transcription.language = v.trim().toLowerCase(java.util.Locale.ROOT)).build());
		speech.addEntry(entries.startBooleanToggle(Component.literal("Translate into English"), config.transcription.translateToEnglish)
				.setDefaultValue(defaults.transcription.translateToEnglish)
				.setTooltip(Component.literal("Free, built into Whisper. Needs a multilingual model (no \".en\")."))
				.setSaveConsumer(v -> config.transcription.translateToEnglish = v).build());
		speech.addEntry(entries.startBooleanToggle(Component.literal("Accuracy hints"), config.transcription.accuracyHints)
				.setDefaultValue(defaults.transcription.accuracyHints)
				.setTooltip(Component.literal("Tell Whisper the online player names and the words below so it spells them right."))
				.setSaveConsumer(v -> config.transcription.accuracyHints = v).build());
		speech.addEntry(entries.startStrList(Component.literal("Words to recognise"), new ArrayList<>(config.transcription.vocabulary))
				.setDefaultValue(defaults.transcription.vocabulary)
				.setSaveConsumer(v -> config.transcription.vocabulary = new ArrayList<>(v)).build());
		speech.addEntry(entries.startStrField(Component.literal("Local model"), config.transcription.local.model)
				.setDefaultValue(defaults.transcription.local.model)
				.setTooltip(Component.literal("Only used when recognition runs on this PC. tiny.en-q5_1 is fastest, small.en-q5_1 most accurate."))
				.setSaveConsumer(v -> config.transcription.local.model = v.trim()).build());

		ConfigCategory alerts = builder.getOrCreateCategory(Component.literal("Name alerts"));
		alerts.addEntry(entries.startBooleanToggle(Component.literal("Alerts"), config.alerts.enabled)
				.setDefaultValue(defaults.alerts.enabled)
				.setTooltip(Component.literal("Play a sound and highlight the line when someone says your name or a word below."))
				.setSaveConsumer(v -> config.alerts.enabled = v).build());
		alerts.addEntry(entries.startBooleanToggle(Component.literal("My player name"), config.alerts.ownName)
				.setDefaultValue(defaults.alerts.ownName)
				.setSaveConsumer(v -> config.alerts.ownName = v).build());
		alerts.addEntry(entries.startStrList(Component.literal("Other words (nicknames)"), new ArrayList<>(config.alerts.words))
				.setDefaultValue(defaults.alerts.words)
				.setSaveConsumer(v -> config.alerts.words = new ArrayList<>(v)).build());
		alerts.addEntry(entries.startStrField(Component.literal("Sound"), config.alerts.sound)
				.setDefaultValue(defaults.alerts.sound)
				.setSaveConsumer(v -> config.alerts.sound = v.trim()).build());
		alerts.addEntry(entries.startIntSlider(Component.literal("Volume (%)"), (int) Math.round(config.alerts.volume * 100), 0, 200)
				.setDefaultValue((int) Math.round(defaults.alerts.volume * 100))
				.setSaveConsumer(v -> config.alerts.volume = v / 100.0).build());

		ConfigCategory hosting = builder.getOrCreateCategory(Component.literal("Hosting"));
		hosting.addEntry(entries.startTextDescription(Component.literal(
				"These apply to singleplayer and LAN worlds you host. Dedicated servers use their own config file.")).build());
		hosting.addEntry(entries.startBooleanToggle(Component.literal("Transcribe everyone (server mode)"), config.server.enabled)
				.setDefaultValue(defaults.server.enabled)
				.setSaveConsumer(v -> config.server.enabled = v).build());
		hosting.addEntry(entries.startSelector(Component.literal("Who sees normal speech"), new String[]{"everyone", "nearby"}, config.server.audience)
				.setDefaultValue(defaults.server.audience)
				.setSaveConsumer(v -> config.server.audience = v).build());
		hosting.addEntry(entries.startBooleanToggle(Component.literal("Transcribe group voice (group only)"), config.server.transcribeGroups)
				.setDefaultValue(defaults.server.transcribeGroups)
				.setSaveConsumer(v -> config.server.transcribeGroups = v).build());
		hosting.addEntry(entries.startBooleanToggle(Component.literal("Transcribe whispers (nearby only)"), config.server.transcribeWhispers)
				.setDefaultValue(defaults.server.transcribeWhispers)
				.setSaveConsumer(v -> config.server.transcribeWhispers = v).build());
		hosting.addEntry(entries.startBooleanToggle(Component.literal("Live captions in the action bar"), config.live.actionBar)
				.setDefaultValue(defaults.live.actionBar)
				.setTooltip(Component.literal("For players without the mod. Each player can also turn it off with /subtitles live off."))
				.setSaveConsumer(v -> config.live.actionBar = v).build());
		hosting.addEntry(entries.startBooleanToggle(Component.literal("Moderation history"), config.history.enabled)
				.setDefaultValue(defaults.history.enabled)
				.setTooltip(Component.literal("Operators can read what was said with /subtitles history."))
				.setSaveConsumer(v -> config.history.enabled = v).build());
		hosting.addEntry(entries.startIntSlider(Component.literal("Keep history (days)"), config.history.keepDays, 1, 90)
				.setDefaultValue(defaults.history.keepDays)
				.setSaveConsumer(v -> config.history.keepDays = v).build());
		hosting.addEntry(entries.startBooleanToggle(Component.literal("Tell players when they join"), config.server.joinNotice)
				.setDefaultValue(defaults.server.joinNotice)
				.setSaveConsumer(v -> config.server.joinNotice = v).build());
		hosting.addEntry(entries.startBooleanToggle(Component.literal("Text-to-speech (/tts)"), config.tts.enabled)
				.setDefaultValue(defaults.tts.enabled)
				.setSaveConsumer(v -> config.tts.enabled = v).build());
		hosting.addEntry(entries.startStrField(Component.literal("Text-to-speech voice"), config.tts.voice)
				.setDefaultValue(defaults.tts.voice)
				.setTooltip(Component.literal("A Piper voice like en_GB-alan-medium; empty uses the speech server's default."))
				.setSaveConsumer(v -> config.tts.voice = v.trim()).build());

		return builder.build();
	}

	private static void apply() {
		SvmSubtitles.saveConfig();
		SvmSubtitles.reloadConfig();
		TranscriptionService.restartShared();
		ClientSubtitles.INSTANCE.applyConfig();
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> ServerSubtitles.reload(server));
		}
	}
}
