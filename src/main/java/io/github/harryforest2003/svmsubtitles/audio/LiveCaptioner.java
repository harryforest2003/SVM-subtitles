package io.github.harryforest2003.svmsubtitles.audio;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Word-by-word captions while someone is still talking. New audio is sent to the speech server about once a
 * second, never more than one request per speaker at a time, so a slow server just means fewer updates rather
 * than a growing backlog. Nothing here blocks: requests are asynchronous.
 */
public final class LiveCaptioner {
	/** Sends new audio of the current sentence; completes with the text so far, or null to skip. */
	public interface Sender {
		@Nullable CompletableFuture<@Nullable String> send(UUID session, float[] newAudio);
	}

	public interface Display {
		void partial(UUID speaker, String text, boolean whispering);
	}

	private static final int MIN_NEW_SAMPLES = Resampler.OUTPUT_RATE / 2;
	/** Whisper guesses wildly from a fraction of a word, so the first update waits for a full second. */
	private static final int MIN_FIRST_SAMPLES = Resampler.OUTPUT_RATE;
	private static final long ERROR_BACKOFF_NANOS = 5_000_000_000L;

	private final Map<UUID, State> states = new ConcurrentHashMap<>();
	private final Sender sender;
	private final Display display;
	private final IntSupplier intervalMs;

	public LiveCaptioner(Sender sender, Display display, IntSupplier intervalMs) {
		this.sender = sender;
		this.display = display;
		this.intervalMs = intervalMs;
	}

	/** Called for every audio frame of an unfinished sentence (from the segmenter, under its lock). */
	public void onProgress(UUID speaker, float[] audio, int length, boolean whispering) {
		State state = states.computeIfAbsent(speaker, id -> new State());
		synchronized (state) {
			long now = System.nanoTime();
			if (state.inFlight || length - state.sent < MIN_NEW_SAMPLES || length < MIN_FIRST_SAMPLES || now < state.nextSend) {
				return;
			}
			CompletableFuture<@Nullable String> request = sender.send(state.session, Arrays.copyOfRange(audio, state.sent, length));
			if (request == null) {
				return;
			}
			state.sent = length;
			state.inFlight = true;
			state.nextSend = now + intervalMs.getAsInt() * 1_000_000L;
			int generation = state.generation;
			request.whenComplete((text, error) -> {
				synchronized (state) {
					if (state.generation != generation) {
						return; // the sentence already finished; the final text replaces this
					}
					state.inFlight = false;
					if (error != null) {
						state.nextSend = System.nanoTime() + ERROR_BACKOFF_NANOS;
						return;
					}
				}
				if (text != null && !text.isBlank()) {
					display.partial(speaker, text, whispering);
				}
			});
		}
	}

	/** The sentence is complete; the next one starts a new session. */
	public void onSentenceEnd(UUID speaker) {
		State state = states.get(speaker);
		if (state != null) {
			synchronized (state) {
				state.generation++;
				state.session = UUID.randomUUID();
				state.sent = 0;
				state.inFlight = false;
			}
		}
	}

	public void remove(UUID speaker) {
		State state = states.remove(speaker);
		if (state != null) {
			synchronized (state) {
				state.generation++;
			}
		}
	}

	private static final class State {
		UUID session = UUID.randomUUID();
		int sent;
		int generation;
		boolean inFlight;
		long nextSend;
	}
}
