package io.github.harryforest2003.svmsubtitles.audio;

import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Collects one speaker's 48 kHz voice frames and cuts them into sentences. A sentence ends after a pause,
 * when the speaker stops transmitting, or when it gets too long. Each finished sentence goes to the sink.
 * Thread-safe: frames and the idle check may arrive from different threads.
 */
public final class SpeechSegmenter {
	private static final int SAMPLES_PER_MS = Resampler.OUTPUT_RATE / 1000;
	private static final int PREROLL_SAMPLES = 200 * SAMPLES_PER_MS;
	private static final int KEPT_TAIL_SAMPLES = 200 * SAMPLES_PER_MS;

	public record Settings(int silenceThreshold, int endOfSpeechMs, int maxClipMs, int minSpeechMs) {
		public static Settings from(SubtitlesConfig.Segmentation config) {
			return new Settings(config.silenceThreshold, config.endOfSpeechMs, config.maxClipSeconds * 1000, config.minSpeechMs);
		}
	}

	private final Resampler resampler = new Resampler();
	private final ArrayDeque<float[]> preroll = new ArrayDeque<>();
	private final Consumer<Segment> sink;
	private volatile Settings settings;

	private float[] buffer = new float[16 * Resampler.OUTPUT_RATE];
	private int length;
	private int prerollLength;
	private boolean speaking;
	private boolean whispered;
	private int voicedSamples;
	private int trailingSilence;
	private long lastFrameMillis;

	public SpeechSegmenter(Settings settings, Consumer<Segment> sink) {
		this.settings = settings;
		this.sink = sink;
	}

	public void setSettings(Settings settings) {
		this.settings = settings;
	}

	public synchronized void accept(short[] pcm48k, boolean whispering, long nowMillis) {
		lastFrameMillis = nowMillis;
		Settings s = settings;
		float[] frame = resampler.process(pcm48k);
		boolean voiced = rms(pcm48k) >= s.silenceThreshold();

		if (!speaking) {
			if (!voiced) {
				preroll.addLast(frame);
				prerollLength += frame.length;
				while (prerollLength - preroll.peekFirst().length >= PREROLL_SAMPLES) {
					prerollLength -= preroll.removeFirst().length;
				}
				return;
			}
			speaking = true;
			for (float[] f : preroll) {
				append(f);
			}
			preroll.clear();
			prerollLength = 0;
		}

		append(frame);
		whispered |= whispering;
		if (voiced) {
			voicedSamples += frame.length;
			trailingSilence = 0;
		} else {
			trailingSilence += frame.length;
		}

		if (trailingSilence >= s.endOfSpeechMs() * SAMPLES_PER_MS) {
			finish();
		} else if (length >= s.maxClipMs() * SAMPLES_PER_MS) {
			finish();
		}
	}

	/** The speaker stopped transmitting (stop packet or no audio for a while). */
	public synchronized void endOfStream() {
		finish();
		resampler.reset();
		preroll.clear();
		prerollLength = 0;
	}

	/** Ends the sentence if no audio arrived for the configured pause. Returns true when the stream is idle. */
	public synchronized boolean checkIdle(long nowMillis) {
		if (nowMillis - lastFrameMillis < settings.endOfSpeechMs()) {
			return false;
		}
		if (speaking || prerollLength > 0) {
			endOfStream();
		}
		return true;
	}

	private void finish() {
		if (!speaking) {
			return;
		}
		int keep = length - Math.max(0, trailingSilence - KEPT_TAIL_SAMPLES);
		boolean enoughSpeech = voicedSamples >= settings.minSpeechMs() * SAMPLES_PER_MS;
		float[] samples = enoughSpeech ? Arrays.copyOf(buffer, keep) : null;
		boolean wasWhisper = whispered;

		speaking = false;
		whispered = false;
		length = 0;
		voicedSamples = 0;
		trailingSilence = 0;

		if (samples != null) {
			sink.accept(new Segment(samples, wasWhisper));
		}
	}

	private void append(float[] frame) {
		if (length + frame.length > buffer.length) {
			buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + frame.length));
		}
		System.arraycopy(frame, 0, buffer, length, frame.length);
		length += frame.length;
	}

	static double rms(short[] pcm) {
		if (pcm.length == 0) {
			return 0;
		}
		double sum = 0;
		for (short s : pcm) {
			sum += (double) s * s;
		}
		return Math.sqrt(sum / pcm.length);
	}
}
