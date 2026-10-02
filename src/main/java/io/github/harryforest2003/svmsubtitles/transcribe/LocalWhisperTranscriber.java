package io.github.harryforest2003.svmsubtitles.transcribe;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Runs whisper.cpp inside the game process through the bundled whisper-jni natives. */
final class LocalWhisperTranscriber implements Transcriber {
	private static final Logger LOGGER = LoggerFactory.getLogger("SVM Subtitles");
	/** whisper.cpp refuses clips shorter than one second, so short ones are padded with silence. */
	private static final int MIN_SAMPLES = 17_600;

	private static boolean libraryLoaded;

	private final WhisperJNI whisper;
	private final WhisperContext context;
	private final String modelName;
	private final int threads;
	private final boolean multilingual;

	private LocalWhisperTranscriber(WhisperJNI whisper, WhisperContext context, String modelName, int threads) {
		this.whisper = whisper;
		this.context = context;
		this.modelName = modelName;
		this.threads = threads;
		this.multilingual = whisper.isMultilingual(context);
	}

	boolean isMultilingual() {
		return multilingual;
	}

	static synchronized LocalWhisperTranscriber load(Path model, int threads) throws IOException {
		if (!libraryLoaded) {
			WhisperJNI.loadLibrary(LOGGER::debug);
			WhisperJNI.setLibraryLogger(null);
			libraryLoaded = true;
		}
		WhisperJNI whisper = new WhisperJNI();
		WhisperContext context = whisper.init(model);
		if (context == null) {
			throw new IOException("whisper.cpp could not load " + model.getFileName() + " (corrupt or unsupported model file?)");
		}
		String name = model.getFileName().toString().replaceFirst("^ggml-", "").replaceFirst("\\.bin$", "");
		return new LocalWhisperTranscriber(whisper, context, name, threads);
	}

	@Override
	public synchronized Transcript transcribe(float[] samples, SpeechRequest request) throws IOException {
		boolean translate = request.translate() && multilingual;
		WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
		params.nThreads = threads;
		params.language = translate || !multilingual ? (multilingual ? "auto" : "en") : request.language();
		params.translate = translate;
		if (!request.prompt().isEmpty()) {
			params.initialPrompt = request.prompt();
		}
		params.noContext = true;
		params.noTimestamps = true;
		params.singleSegment = false;
		params.suppressBlank = true;
		params.suppressNonSpeechTokens = true;
		params.printProgress = false;
		params.printRealtime = false;
		params.printSpecial = false;
		params.printTimestamps = false;

		float[] input = samples.length >= MIN_SAMPLES ? samples : Arrays.copyOf(samples, MIN_SAMPLES);
		int result = whisper.full(context, params, input, input.length);
		if (result != 0) {
			throw new IOException("whisper.cpp failed with code " + result);
		}
		StringBuilder text = new StringBuilder();
		int segments = whisper.fullNSegments(context);
		for (int i = 0; i < segments; i++) {
			text.append(whisper.fullGetSegmentText(context, i));
		}
		return new Transcript(text.toString().trim(), null);
	}

	@Override
	public String describe() {
		return "local whisper.cpp (" + modelName + ", " + threads + (threads == 1 ? " thread)" : " threads)");
	}

	@Override
	public boolean isLocal() {
		return true;
	}

	@Override
	public synchronized void close() {
		whisper.free(context);
	}
}
