package io.github.harryforest2003.svmsubtitles.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechSegmenterTest {
	private static final SpeechSegmenter.Settings SETTINGS = new SpeechSegmenter.Settings(250, 700, 15_000, 300);

	private final List<Segment> segments = new ArrayList<>();
	private final SpeechSegmenter segmenter = new SpeechSegmenter(SETTINGS, segments::add);
	private long clock;

	@Test
	void pauseEndsASentence() {
		feed(voice(), 50);   // 1 s of speech
		feed(silence(), 40); // 0.8 s pause
		assertEquals(1, segments.size());
		double seconds = segments.getFirst().seconds();
		assertTrue(seconds > 1.0 && seconds < 1.5, "speech plus a short tail, got " + seconds);
	}

	@Test
	void stopPacketEndsASentence() {
		feed(voice(), 25);
		assertTrue(segments.isEmpty());
		segmenter.endOfStream();
		assertEquals(1, segments.size());
	}

	@Test
	void idleSpeakerIsFlushedByTheTimer() {
		feed(voice(), 25);
		assertFalse(segmenter.checkIdle(clock + 100));
		assertTrue(segmenter.checkIdle(clock + 800));
		assertEquals(1, segments.size());
	}

	@Test
	void shortNoiseIsIgnored() {
		feed(voice(), 5); // 100 ms click
		segmenter.endOfStream();
		assertTrue(segments.isEmpty());
	}

	@Test
	void silenceAloneProducesNothing() {
		feed(silence(), 500);
		segmenter.endOfStream();
		assertTrue(segments.isEmpty());
	}

	@Test
	void longSpeechIsSplit() {
		feed(voice(), 50 * 40); // 40 s without a pause
		segmenter.endOfStream();
		assertEquals(3, segments.size());
		assertTrue(segments.getFirst().seconds() <= 15.0);
	}

	@Test
	void keepsALittleAudioFromBeforeTheSpeechStarted() {
		feed(silence(), 50);
		feed(voice(), 25);
		segmenter.endOfStream();
		double seconds = segments.getFirst().seconds();
		assertTrue(seconds >= 0.69 && seconds <= 0.72, "0.5 s speech + 0.2 s pre-roll, got " + seconds);
	}

	@Test
	void whisperFlagSticksToTheSentence() {
		feed(voice(), 10);
		segmenter.accept(voice(), true, clock += 20);
		feed(voice(), 10);
		segmenter.endOfStream();
		assertTrue(segments.getFirst().whispering());
	}

	private void feed(short[] frame, int count) {
		for (int i = 0; i < count; i++) {
			segmenter.accept(frame, false, clock += 20);
		}
	}

	private static short[] voice() {
		short[] pcm = new short[960];
		for (int i = 0; i < pcm.length; i++) {
			pcm[i] = (short) (3000 * Math.sin(2 * Math.PI * 200 * i / 48_000.0));
		}
		return pcm;
	}

	private static short[] silence() {
		short[] pcm = new short[960];
		for (int i = 0; i < pcm.length; i++) {
			pcm[i] = (short) ((i % 7) - 3);
		}
		return pcm;
	}
}
