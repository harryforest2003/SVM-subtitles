package io.github.harryforest2003.svmsubtitles.audio;

/** One spoken sentence: mono 16 kHz samples in [-1, 1]. */
public record Segment(float[] samples, boolean whispering) {
	public double seconds() {
		return samples.length / (double) Resampler.OUTPUT_RATE;
	}
}
