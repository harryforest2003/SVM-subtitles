package io.github.harryforest2003.svmsubtitles.transcribe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs real whisper.cpp. Needs a model, so it only runs when SVM_TEST_MODEL points at a ggml .bin file:
 * SVM_TEST_MODEL=/path/to/ggml-base.en-q5_1.bin ./gradlew test
 */
@EnabledIfEnvironmentVariable(named = "SVM_TEST_MODEL", matches = ".+")
class LocalWhisperTranscriberTest {
	@Test
	void transcribesTheBenchmarkClip() throws Exception {
		try (LocalWhisperTranscriber whisper = LocalWhisperTranscriber.load(Path.of(System.getenv("SVM_TEST_MODEL")), 4)) {
			float[] audio = TranscriptionService.benchmarkAudio();
			long start = System.nanoTime();
			String text = whisper.transcribe(audio, "en");
			double seconds = (System.nanoTime() - start) / 1e9;
			System.out.printf("%s: \"%s\" in %.2f s (%.2fx real time)%n", whisper.describe(), text, seconds, seconds / 11.0);
			assertTrue(text.toLowerCase().contains("ask not what your country can do for you"), text);
		}
	}

	@Test
	void handlesClipsShorterThanOneSecond() throws Exception {
		try (LocalWhisperTranscriber whisper = LocalWhisperTranscriber.load(Path.of(System.getenv("SVM_TEST_MODEL")), 4)) {
			float[] audio = TranscriptionService.benchmarkAudio();
			float[] shortClip = java.util.Arrays.copyOfRange(audio, 0, 8_000);
			whisper.transcribe(shortClip, "en");
		}
	}
}
