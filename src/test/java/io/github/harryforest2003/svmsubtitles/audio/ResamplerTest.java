package io.github.harryforest2003.svmsubtitles.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResamplerTest {
	@Test
	void producesOneThirdOfTheSamplesAcrossOddFrameSizes() {
		Resampler resampler = new Resampler();
		int in = 0;
		int out = 0;
		for (int size : new int[]{960, 961, 1, 2, 959, 480, 7}) {
			out += resampler.process(new short[size]).length;
			in += size;
		}
		assertEquals((in + 2) / 3, out);
	}

	@Test
	void keepsSpeechFrequenciesAndRemovesHighOnes() {
		assertTrue(amplitudeAfterResampling(1_000) > 0.45, "1 kHz should pass");
		assertTrue(amplitudeAfterResampling(3_000) > 0.45, "3 kHz should pass");
		assertTrue(amplitudeAfterResampling(12_000) < 0.02, "12 kHz would alias and must be filtered");
	}

	private static double amplitudeAfterResampling(double frequency) {
		Resampler resampler = new Resampler();
		double peak = 0;
		for (int frame = 0; frame < 20; frame++) {
			short[] pcm = new short[960];
			for (int i = 0; i < pcm.length; i++) {
				double t = (frame * 960 + i) / 48_000.0;
				pcm[i] = (short) (0.5 * 32767 * Math.sin(2 * Math.PI * frequency * t));
			}
			float[] out = resampler.process(pcm);
			if (frame >= 2) {
				for (float v : out) {
					peak = Math.max(peak, Math.abs(v));
				}
			}
		}
		return peak;
	}
}
