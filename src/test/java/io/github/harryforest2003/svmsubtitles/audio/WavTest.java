package io.github.harryforest2003.svmsubtitles.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WavTest {
	@Test
	void roundTrips() throws IOException {
		float[] samples = {0f, 0.5f, -0.5f, 1f, -1f, 0.25f};
		byte[] wav = Wav.encode(samples, 16_000);
		assertEquals(44 + samples.length * 2, wav.length);

		Wav.Decoded decoded = Wav.decode(wav);
		assertEquals(16_000, decoded.sampleRate());
		assertArrayEquals(samples, decoded.samples(), 1e-4f);
	}

	@Test
	void readsTheBundledBenchmarkClip() throws IOException {
		try (var in = WavTest.class.getResourceAsStream("/svm_subtitles/benchmark.wav")) {
			Wav.Decoded decoded = Wav.decode(in.readAllBytes());
			assertEquals(16_000, decoded.sampleRate());
			assertEquals(11.0, decoded.samples().length / 16_000.0, 0.1);
		}
	}

	@Test
	void rejectsOtherFiles() {
		assertThrows(IOException.class, () -> Wav.decode("not audio at all".getBytes()));
	}
}
