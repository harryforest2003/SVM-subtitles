package io.github.harryforest2003.svmsubtitles.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything lives in {@code config/svm_subtitles/config.json}. Missing keys fall back to the defaults below
 * and are written back on load, so new options show up automatically after an update.
 */
public final class SubtitlesConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("SVM Subtitles");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public String _help = "Every option is explained at https://github.com/harryforest2003/SVM-subtitles#configuration";
	public Transcription transcription = new Transcription();
	public Segmentation segmentation = new Segmentation();
	public Chat chat = new Chat();
	public Server server = new Server();
	public Client client = new Client();

	public static final class Transcription {
		/** "auto", "local", "remote" or "off". */
		public String backend = "auto";
		/** Spoken language ("en", "de", "es", ...) or "auto". Models ending in ".en" only understand English. */
		public String language = "en";
		/** How many finished sentences may wait for the recogniser before the oldest is skipped. */
		public int maxQueuedClips = 8;
		/** Sentences that waited longer than this are skipped instead of showing up late. */
		public int maxDelaySeconds = 20;
		public Local local = new Local();
		public Remote remote = new Remote();
	}

	public static final class Local {
		/** A whisper.cpp model name (tiny.en, base.en-q5_1, small.en, ...) or a path to a ggml .bin file. */
		public String model = "base.en-q5_1";
		/** CPU threads for whisper.cpp. 0 picks half of the CPU threads, capped at 4, so the game keeps the rest. */
		public int threads = 0;
		public boolean autoDownload = true;
		public String downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{model}.bin";
		/**
		 * In "auto" mode local recognition is only used when it needs at most this many seconds per second of
		 * audio on this machine. Anything slower would make a weak PC stutter, so it switches itself off instead.
		 */
		public double maxRealtimeFactor = 0.5;
	}

	public static final class Remote {
		/** An OpenAI-compatible /v1/audio/transcriptions endpoint, e.g. the bundled companion server. */
		public String url = "";
		public String apiKey = "";
		public String model = "whisper-1";
		public int timeoutSeconds = 30;
		public int parallelRequests = 2;
	}

	public static final class Segmentation {
		/** Loudness (RMS of 16-bit samples) below which a 20 ms frame counts as silence. */
		public int silenceThreshold = 250;
		/** A pause this long ends a sentence. */
		public int endOfSpeechMs = 700;
		/** Long monologues are cut into pieces of at most this length. */
		public int maxClipSeconds = 15;
		/** Clips with less speech than this are ignored (coughs, clicks, keyboard noise). */
		public int minSpeechMs = 300;
	}

	public static final class Chat {
		/** &-colour codes (and &#RRGGBB) are supported. {player} becomes the clickable name, {text} the speech. */
		public String format = "&8[&3Voice&8] &b{player}&7: &f{text}";
		/** "auto" finds /msg, /tell, /w ... on the server. Use e.g. "/m {player} " to force one, or "none". */
		public String privateMessageCommand = "auto";
		public String hoverText = "Click to message {player}";
		/** Transcripts containing any of these are dropped. Whisper invents them when it hears noise. */
		public List<String> ignoredPhrases = new ArrayList<>(List.of(
				"thanks for watching",
				"thank you for watching",
				"thanks for listening",
				"please subscribe",
				"like and subscribe",
				"subtitles by",
				"subtitled by",
				"captions by",
				"transcribed by",
				"transcription by",
				"amara.org"
		));
	}

	public static final class Server {
		/** Transcribe on the server and send the subtitles to players (they need nothing installed). */
		public boolean enabled = true;
		/** "everyone" sends proximity speech to every player, "nearby" only to players within voice range. */
		public String audience = "everyone";
		/** Transcribe voice inside groups. Only members of that group see it. */
		public boolean transcribeGroups = true;
		/** Transcribe whispers. Only players within whisper range see it. */
		public boolean transcribeWhispers = true;
		public boolean logToConsole = true;
	}

	public static final class Client {
		/** Transcribe the voices you hear on servers that don't run this mod themselves. */
		public boolean enabled = true;
		public boolean includeOwnVoice = true;
		/** Stay idle when the server already transcribes, so nothing shows up twice. */
		public boolean deferToServer = true;
	}

	public static SubtitlesConfig load(Path file) {
		SubtitlesConfig config = null;
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				config = GSON.fromJson(reader, SubtitlesConfig.class);
			} catch (IOException | JsonParseException e) {
				LOGGER.error("Could not read {}, using defaults until it is fixed: {}", file, e.getMessage());
				SubtitlesConfig defaults = new SubtitlesConfig();
				defaults.sanitize();
				return defaults;
			}
		}
		if (config == null) {
			config = new SubtitlesConfig();
		}
		config.sanitize();
		config.save(file);
		return config;
	}

	public void save(Path file) {
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Could not write {}", file, e);
		}
	}

	private void sanitize() {
		if (transcription == null) transcription = new Transcription();
		if (transcription.local == null) transcription.local = new Local();
		if (transcription.remote == null) transcription.remote = new Remote();
		if (segmentation == null) segmentation = new Segmentation();
		if (chat == null) chat = new Chat();
		if (server == null) server = new Server();
		if (client == null) client = new Client();
		if (chat.ignoredPhrases == null) chat.ignoredPhrases = new ArrayList<>();

		transcription.backend = orDefault(transcription.backend, "auto").toLowerCase(Locale.ROOT);
		transcription.language = orDefault(transcription.language, "en").toLowerCase(Locale.ROOT);
		transcription.maxQueuedClips = clamp(transcription.maxQueuedClips, 1, 100);
		transcription.maxDelaySeconds = clamp(transcription.maxDelaySeconds, 2, 600);
		transcription.local.model = orDefault(transcription.local.model, "base.en-q5_1");
		transcription.local.threads = clamp(transcription.local.threads, 0, 64);
		transcription.local.downloadUrl = orDefault(transcription.local.downloadUrl, new Local().downloadUrl);
		if (!(transcription.local.maxRealtimeFactor > 0)) transcription.local.maxRealtimeFactor = 0.5;
		transcription.remote.url = orDefault(transcription.remote.url, "");
		transcription.remote.apiKey = orDefault(transcription.remote.apiKey, "");
		transcription.remote.model = orDefault(transcription.remote.model, "whisper-1");
		transcription.remote.timeoutSeconds = clamp(transcription.remote.timeoutSeconds, 1, 600);
		transcription.remote.parallelRequests = clamp(transcription.remote.parallelRequests, 1, 16);

		segmentation.silenceThreshold = clamp(segmentation.silenceThreshold, 0, 32767);
		segmentation.endOfSpeechMs = clamp(segmentation.endOfSpeechMs, 100, 10_000);
		segmentation.maxClipSeconds = clamp(segmentation.maxClipSeconds, 2, 30);
		segmentation.minSpeechMs = clamp(segmentation.minSpeechMs, 0, 10_000);

		chat.format = orDefault(chat.format, new Chat().format);
		chat.privateMessageCommand = orDefault(chat.privateMessageCommand, "auto");
		chat.hoverText = orDefault(chat.hoverText, "");
		server.audience = orDefault(server.audience, "everyone").toLowerCase(Locale.ROOT);
	}

	private static String orDefault(String value, String fallback) {
		return value == null ? fallback : value.trim();
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
