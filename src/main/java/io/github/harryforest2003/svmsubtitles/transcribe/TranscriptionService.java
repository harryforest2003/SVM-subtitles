package io.github.harryforest2003.svmsubtitles.transcribe;

import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.github.harryforest2003.svmsubtitles.audio.Segment;
import io.github.harryforest2003.svmsubtitles.audio.Wav;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import io.github.harryforest2003.svmsubtitles.util.Threads;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Picks a speech-to-text backend and runs it on background threads, never on the game or audio threads.
 *
 * <p>In "auto" mode it uses the remote server if one is configured. Otherwise it checks whether this machine can
 * run Whisper without hurting the game: weak hardware is skipped outright, and everything else gets a short
 * benchmark. If recognition would be too slow, subtitles switch off and tell the player how to offload the work.
 *
 * <p>One instance is shared per JVM, so a singleplayer host doesn't load the model twice for client and server.
 */
public final class TranscriptionService implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("SVM Subtitles");
	private static final int MIN_CPU_THREADS = 4;
	private static final long MIN_MEMORY_BYTES = 4L * 1024 * 1024 * 1024 - 256L * 1024 * 1024;
	private static final String OFFLOAD_HINT = "To still get subtitles, run the companion Whisper server on a faster computer"
			+ " (or use a cloud speech API) and set transcription.remote.url in config/svm_subtitles/config.json.";

	public enum State { STARTING, READY, OFF, FAILED }

	public record Notice(String message, boolean warning) {
	}

	private static final List<Consumer<Notice>> NOTICE_LISTENERS = new CopyOnWriteArrayList<>();
	private static final List<Runnable> STATE_LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile @Nullable TranscriptionService shared;

	private final SubtitlesConfig.Transcription config;
	private final List<String> ignoredPhrases;
	private final Path modelsDir;
	private final ThreadPoolExecutor executor;
	private final AtomicLong transcribed = new AtomicLong();
	private final AtomicLong skipped = new AtomicLong();

	private volatile @Nullable Transcriber transcriber;
	private volatile State state = State.STARTING;
	private volatile String detail = "starting up";
	private volatile boolean closed;
	private volatile double realtimeFactor = -1;
	private volatile boolean warnedSlow;
	private volatile long lastErrorLog;

	private TranscriptionService(SubtitlesConfig config, Path dataDir) {
		this.config = config.transcription;
		this.ignoredPhrases = List.copyOf(config.chat.ignoredPhrases);
		this.modelsDir = dataDir.resolve("models");
		this.executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(this.config.maxQueuedClips),
				Threads.factory("SVM Subtitles transcriber"),
				this::dropOldest);
		executor.execute(this::start);
	}

	/** The shared service, started on first use. */
	public static TranscriptionService shared() {
		TranscriptionService service = shared;
		if (service != null) {
			return service;
		}
		synchronized (TranscriptionService.class) {
			if (shared == null) {
				shared = new TranscriptionService(SvmSubtitles.config(), SvmSubtitles.dataDir());
			}
			return shared;
		}
	}

	public static @Nullable TranscriptionService current() {
		return shared;
	}

	/** Re-reads the config into a fresh service, if one was running. */
	public static void restartShared() {
		synchronized (TranscriptionService.class) {
			TranscriptionService old = shared;
			if (old == null) {
				return;
			}
			old.close();
			shared = new TranscriptionService(SvmSubtitles.config(), SvmSubtitles.dataDir());
		}
		fireStateChanged();
	}

	public static void shutdownShared() {
		synchronized (TranscriptionService.class) {
			if (shared != null) {
				shared.close();
				shared = null;
			}
		}
	}

	public static void addNoticeListener(Consumer<Notice> listener) {
		NOTICE_LISTENERS.add(listener);
	}

	public static void addStateListener(Runnable listener) {
		STATE_LISTENERS.add(listener);
	}

	public boolean isAccepting() {
		return !closed && (state == State.STARTING || state == State.READY);
	}

	public State state() {
		return state;
	}

	/** Transcribes a sentence in the background. The callback runs on a worker thread, only for real speech. */
	public void submit(Segment segment, Consumer<String> onText) {
		if (!isAccepting()) {
			return;
		}
		long queuedAt = System.nanoTime();
		try {
			executor.execute(() -> process(segment, queuedAt, onText));
		} catch (RejectedExecutionException ignored) {
			// shutting down
		}
	}

	/** Runs the bundled sample sentence through the active backend and reports what came out. */
	public void selfTest(Consumer<String> result) {
		try {
			executor.execute(() -> {
				Transcriber engine = transcriber;
				if (engine == null) {
					result.accept("Speech recognition is not running: " + detail);
					return;
				}
				try {
					float[] sample = benchmarkAudio();
					long start = System.nanoTime();
					String text = engine.transcribe(sample, config.language);
					double seconds = (System.nanoTime() - start) / 1e9;
					result.accept(String.format(Locale.ROOT, "Heard \"%s\" in %.1f s (%.0f s of audio, %s)",
							text.strip(), seconds, sample.length / 16_000.0, engine.describe()));
				} catch (Exception e) {
					result.accept("Test failed: " + message(e));
				}
			});
		} catch (RejectedExecutionException e) {
			result.accept("Speech recognition is shutting down");
		}
	}

	public String status() {
		StringBuilder status = new StringBuilder(switch (state) {
			case STARTING -> "starting: ";
			case READY -> "on: ";
			case OFF -> "off: ";
			case FAILED -> "error: ";
		}).append(detail);
		if (state == State.READY) {
			status.append(String.format(Locale.ROOT, ". %d sentences transcribed, %d skipped", transcribed.get(), skipped.get()));
			double rtf = realtimeFactor;
			if (rtf >= 0) {
				status.append(String.format(Locale.ROOT, ", %.2fx real time", rtf));
			}
			int queued = executor.getQueue().size();
			if (queued > 0) {
				status.append(", ").append(queued).append(" waiting");
			}
		}
		return status.toString();
	}

	private void start() {
		try {
			switch (config.backend) {
				case "off" -> switchOff("turned off in the config", null);
				case "remote" -> startRemote();
				case "local" -> startLocal(false);
				default -> {
					if (!config.backend.equals("auto")) {
						LOGGER.warn("Unknown transcription.backend '{}', treating it as 'auto'", config.backend);
					}
					if (!config.remote.url.isBlank()) {
						startRemote();
					} else {
						startLocal(true);
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			if (closed) {
				return;
			}
			LOGGER.error("Speech recognition failed to start", t);
			setState(State.FAILED, "failed to start (" + message(t) + ")");
			notice("Speech recognition failed to start: " + message(t), true);
		}
	}

	private void startRemote() throws IOException {
		if (config.remote.url.isBlank()) {
			throw new IOException("transcription.backend is \"remote\" but transcription.remote.url is empty");
		}
		RemoteTranscriber remote = new RemoteTranscriber(config.remote);
		executor.setMaximumPoolSize(config.remote.parallelRequests);
		executor.setCorePoolSize(config.remote.parallelRequests);
		becomeReady(remote, "");
	}

	private void startLocal(boolean auto) throws IOException, InterruptedException {
		int cpus = Runtime.getRuntime().availableProcessors();
		long memory = totalMemory();
		if (auto && (cpus < MIN_CPU_THREADS || (memory > 0 && memory < MIN_MEMORY_BYTES))) {
			switchOff(String.format(Locale.ROOT, "this machine is too weak to run speech recognition alongside Minecraft (%d CPU threads, %.1f GB RAM)",
					cpus, memory / 1e9), OFFLOAD_HINT);
			return;
		}

		Path model = ModelStore.resolve(config.local, modelsDir, message -> notice(message, false));
		if (closed) {
			return;
		}
		int threads = config.local.threads > 0 ? config.local.threads : Math.clamp(cpus / 2, 1, 4);
		LocalWhisperTranscriber local = LocalWhisperTranscriber.load(model, threads);
		if (closed) {
			local.close();
			return;
		}

		float[] sample = benchmarkAudio();
		double audioSeconds = sample.length / 16_000.0;
		long start = System.nanoTime();
		String heard = local.transcribe(sample, config.language);
		double seconds = (System.nanoTime() - start) / 1e9;
		double rtf = seconds / audioSeconds;
		LOGGER.info("Benchmark: {} transcribed {} s of audio in {} s ({}x real time): \"{}\"", local.describe(),
				Math.round(audioSeconds), String.format(Locale.ROOT, "%.2f", seconds), String.format(Locale.ROOT, "%.2f", rtf), heard);

		if (auto && rtf > config.local.maxRealtimeFactor) {
			local.close();
			switchOff(String.format(Locale.ROOT, "this machine needed %.1f s to transcribe %.0f s of speech, so running it in-game would cause lag",
					seconds, audioSeconds), OFFLOAD_HINT);
			return;
		}
		realtimeFactor = rtf;
		becomeReady(local, String.format(Locale.ROOT, ", %.2fx real time", rtf));
	}

	private void becomeReady(Transcriber engine, String extra) {
		if (closed) {
			engine.close();
			return;
		}
		transcriber = engine;
		setState(State.READY, engine.describe());
		notice("Voice subtitles are on, using " + engine.describe() + extra + ".", false);
	}

	private void switchOff(String reason, @Nullable String hint) {
		setState(State.OFF, reason);
		notice("Voice subtitles are off: " + reason + "." + (hint == null ? "" : " " + hint), hint != null);
	}

	private void process(Segment segment, long queuedAt, Consumer<String> onText) {
		Transcriber engine = transcriber;
		if (engine == null || closed) {
			return;
		}
		if (System.nanoTime() - queuedAt > config.maxDelaySeconds * 1_000_000_000L) {
			skipped.incrementAndGet();
			warnSlow();
			return;
		}

		long start = System.nanoTime();
		String raw;
		try {
			raw = engine.transcribe(segment.samples(), config.language);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		} catch (Exception e) {
			long now = System.currentTimeMillis();
			if (now - lastErrorLog > 30_000) {
				lastErrorLog = now;
				LOGGER.warn("Transcription failed ({}): {}", engine.describe(), message(e));
			}
			return;
		}
		double rtf = (System.nanoTime() - start) / 1e9 / Math.max(1.0, segment.seconds());
		realtimeFactor = realtimeFactor < 0 ? rtf : realtimeFactor * 0.8 + rtf * 0.2;
		long count = transcribed.incrementAndGet();
		if (engine.isLocal() && count >= 5 && realtimeFactor > 1.0) {
			warnSlow();
		}

		String text = TextCleaner.clean(raw, ignoredPhrases);
		if (text != null && !closed) {
			onText.accept(text);
		}
	}

	private void dropOldest(Runnable task, ThreadPoolExecutor pool) {
		if (pool.isShutdown()) {
			return;
		}
		pool.getQueue().poll();
		skipped.incrementAndGet();
		warnSlow();
		pool.execute(task);
	}

	private void warnSlow() {
		// Clips pile up while the model downloads; that's expected and not worth a warning.
		if (warnedSlow || state != State.READY) {
			return;
		}
		warnedSlow = true;
		Transcriber engine = transcriber;
		boolean local = engine != null && engine.isLocal();
		notice("Speech recognition can't keep up, so some sentences are skipped."
				+ (local ? " Try a smaller model (transcription.local.model = \"tiny.en-q5_1\") or a remote server." : ""), true);
	}

	private void setState(State state, String detail) {
		this.state = state;
		this.detail = detail;
		fireStateChanged();
	}

	private static void fireStateChanged() {
		for (Runnable listener : STATE_LISTENERS) {
			try {
				listener.run();
			} catch (Throwable t) {
				LOGGER.error("Subtitle state listener failed", t);
			}
		}
	}

	private void notice(String message, boolean warning) {
		if (closed) {
			return;
		}
		if (warning) {
			LOGGER.warn(message);
		} else {
			LOGGER.info(message);
		}
		Notice notice = new Notice(message, warning);
		for (Consumer<Notice> listener : NOTICE_LISTENERS) {
			try {
				listener.accept(notice);
			} catch (Throwable t) {
				LOGGER.error("Subtitle notice listener failed", t);
			}
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		executor.shutdownNow();
		Transcriber engine = transcriber;
		transcriber = null;
		if (engine != null) {
			// Waits for a running transcription to finish, so do it off the game thread.
			Threads.factory("SVM Subtitles shutdown").newThread(() -> {
				try {
					executor.awaitTermination(30, TimeUnit.SECONDS);
				} catch (InterruptedException ignored) {
				}
				engine.close();
			}).start();
		}
	}

	static float[] benchmarkAudio() throws IOException {
		try (InputStream in = TranscriptionService.class.getResourceAsStream("/svm_subtitles/benchmark.wav")) {
			if (in == null) {
				throw new IOException("benchmark.wav is missing from the mod jar");
			}
			return Wav.decode(in.readAllBytes()).samples();
		}
	}

	private static long totalMemory() {
		try {
			if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				return os.getTotalMemorySize();
			}
		} catch (Throwable ignored) {
		}
		return -1;
	}

	private static String message(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
	}
}
