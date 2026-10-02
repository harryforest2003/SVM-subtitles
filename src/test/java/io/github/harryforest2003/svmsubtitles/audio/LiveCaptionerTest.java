package io.github.harryforest2003.svmsubtitles.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCaptionerTest {
	private final List<CompletableFuture<String>> requests = new ArrayList<>();
	private final List<UUID> sessions = new ArrayList<>();
	private final List<Integer> sizes = new ArrayList<>();
	private final List<String> shown = new ArrayList<>();
	private final UUID speaker = UUID.randomUUID();
	private final LiveCaptioner live = new LiveCaptioner((session, audio) -> {
		CompletableFuture<String> request = new CompletableFuture<>();
		requests.add(request);
		sessions.add(session);
		sizes.add(audio.length);
		return request;
	}, (speaker, text, whispering) -> shown.add(text), () -> 0);

	@Test
	void sendsOnlyNewAudioAndOneRequestAtATime() {
		float[] audio = new float[48_000];
		live.onProgress(speaker, audio, 16_000, false);
		live.onProgress(speaker, audio, 32_000, false); // still waiting for the first answer
		assertEquals(1, requests.size());

		requests.getFirst().complete("hello");
		assertEquals(List.of("hello"), shown);
		live.onProgress(speaker, audio, 32_000, false);
		assertEquals(List.of(16_000, 16_000), sizes);
		assertEquals(sessions.get(0), sessions.get(1));
	}

	@Test
	void waitsForEnoughNewAudio() {
		float[] audio = new float[48_000];
		live.onProgress(speaker, audio, 4_000, false);
		live.onProgress(speaker, audio, 12_000, false); // under a second: too little to guess from
		assertTrue(requests.isEmpty());
		live.onProgress(speaker, audio, 16_000, false);
		assertEquals(1, requests.size());
	}

	@Test
	void lateAnswersForFinishedSentencesAreIgnored() {
		float[] audio = new float[48_000];
		live.onProgress(speaker, audio, 16_000, false);
		live.onSentenceEnd(speaker);
		requests.getFirst().complete("too late");
		assertTrue(shown.isEmpty());

		live.onProgress(speaker, audio, 16_000, false);
		assertEquals(2, requests.size());
		assertNotEquals(sessions.get(0), sessions.get(1));
	}

	@Test
	void serverErrorsBackOff() {
		float[] audio = new float[48_000];
		live.onProgress(speaker, audio, 16_000, false);
		requests.getFirst().completeExceptionally(new RuntimeException("down"));
		live.onProgress(speaker, audio, 40_000, false);
		assertEquals(1, requests.size());
	}
}
