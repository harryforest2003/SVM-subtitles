package io.github.harryforest2003.svmsubtitles.audio;

import io.github.harryforest2003.svmsubtitles.util.Threads;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** One {@link SpeechSegmenter} per speaker, plus a timer that closes sentences when someone goes quiet. */
public final class SpeakerTracker implements AutoCloseable {
	private final Map<UUID, SpeechSegmenter> segmenters = new ConcurrentHashMap<>();
	private final BiConsumer<UUID, Segment> sink;
	private final @Nullable LiveCaptioner live;
	private final ScheduledExecutorService ticker;
	private volatile SpeechSegmenter.Settings settings;

	public SpeakerTracker(SpeechSegmenter.Settings settings, BiConsumer<UUID, Segment> sink) {
		this(settings, sink, null);
	}

	public SpeakerTracker(SpeechSegmenter.Settings settings, BiConsumer<UUID, Segment> sink, @Nullable LiveCaptioner live) {
		this.settings = settings;
		this.sink = sink;
		this.live = live;
		this.ticker = Threads.scheduler("SVM Subtitles speech timer");
		ticker.scheduleWithFixedDelay(this::checkIdle, 100, 100, TimeUnit.MILLISECONDS);
	}

	public void accept(UUID speaker, short[] pcm48k, boolean whispering) {
		segmenters.computeIfAbsent(speaker, this::newSegmenter).accept(pcm48k, whispering, now());
	}

	private SpeechSegmenter newSegmenter(UUID speaker) {
		if (live == null) {
			return new SpeechSegmenter(settings, segment -> sink.accept(speaker, segment));
		}
		return new SpeechSegmenter(settings, segment -> {
			live.onSentenceEnd(speaker);
			sink.accept(speaker, segment);
		}, (audio, length, whispering) -> live.onProgress(speaker, audio, length, whispering));
	}

	public void endOfSpeech(UUID speaker) {
		SpeechSegmenter segmenter = segmenters.get(speaker);
		if (segmenter != null) {
			segmenter.endOfStream();
		}
	}

	/** Forgets a speaker without emitting what they were saying (they left). */
	public void remove(UUID speaker) {
		segmenters.remove(speaker);
		if (live != null) {
			live.remove(speaker);
		}
	}

	public void setSettings(SpeechSegmenter.Settings settings) {
		this.settings = settings;
		segmenters.values().forEach(s -> s.setSettings(settings));
	}

	private void checkIdle() {
		long now = now();
		segmenters.values().forEach(s -> s.checkIdle(now));
	}

	@Override
	public void close() {
		ticker.shutdownNow();
		segmenters.clear();
	}

	private static long now() {
		return System.nanoTime() / 1_000_000L;
	}
}
