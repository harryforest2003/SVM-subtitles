package io.github.harryforest2003.svmsubtitles.audio;

/**
 * Turns Simple Voice Chat's 48 kHz audio into the 16 kHz float samples Whisper expects.
 * A windowed-sinc low-pass filter runs before every third sample is kept, so high frequencies
 * don't fold back into the speech band. Keeps state between frames, so use one per speaker.
 */
public final class Resampler {
	public static final int INPUT_RATE = 48_000;
	public static final int OUTPUT_RATE = 16_000;
	private static final int FACTOR = INPUT_RATE / OUTPUT_RATE;
	private static final float[] TAPS = lowPass(63, 6_800.0 / INPUT_RATE);

	private final float[] history = new float[TAPS.length - 1];
	private int phase;

	public float[] process(short[] input) {
		int n = input.length;
		int h = history.length;
		float[] extended = new float[h + n];
		System.arraycopy(history, 0, extended, 0, h);
		for (int i = 0; i < n; i++) {
			extended[h + i] = input[i] / 32768f;
		}

		int count = phase < n ? (n - phase + FACTOR - 1) / FACTOR : 0;
		float[] output = new float[count];
		int o = 0;
		for (int p = phase; p < n; p += FACTOR) {
			int newest = h + p;
			float acc = 0;
			for (int t = 0; t < TAPS.length; t++) {
				acc += TAPS[t] * extended[newest - t];
			}
			output[o++] = acc;
		}

		phase = Math.floorMod(phase - n, FACTOR);
		System.arraycopy(extended, extended.length - h, history, 0, h);
		return output;
	}

	public void reset() {
		java.util.Arrays.fill(history, 0);
		phase = 0;
	}

	private static float[] lowPass(int length, double cutoff) {
		float[] taps = new float[length];
		double middle = (length - 1) / 2.0;
		double sum = 0;
		for (int i = 0; i < length; i++) {
			double x = i - middle;
			double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
			double blackman = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (length - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (length - 1));
			taps[i] = (float) (sinc * blackman);
			sum += taps[i];
		}
		for (int i = 0; i < length; i++) {
			taps[i] /= (float) sum;
		}
		return taps;
	}
}
