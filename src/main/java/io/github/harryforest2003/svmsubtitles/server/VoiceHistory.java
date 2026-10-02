package io.github.harryforest2003.svmsubtitles.server;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.github.harryforest2003.svmsubtitles.config.FilePermissions;
import io.github.harryforest2003.svmsubtitles.util.Threads;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What was said recently, so operators can look into reports with /subtitles history. Kept in memory and in one
 * JSON-lines file per day, readable only by the server's account. Files older than {@code keepDays} are deleted.
 * Players who opted out are never transcribed, so they never end up here.
 */
public final class VoiceHistory implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("SVM Subtitles");
	private static final Gson GSON = new Gson();
	private static final int PER_PLAYER = 200;
	private static final int OVERALL = 1000;

	public record Entry(long time, UUID player, String name, String kind, String scope, String text) {
	}

	private final Path dir;
	private final int keepDays;
	private final Clock clock;
	private final ExecutorService writer = Threads.single("SVM Subtitles history");
	private final Map<String, Deque<Entry>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final Deque<Entry> overall = new ArrayDeque<>();
	private LocalDate lastCleanup;

	public VoiceHistory(Path dir, int keepDays, Clock clock) {
		this.dir = dir;
		this.keepDays = keepDays;
		this.clock = clock;
		load();
	}

	public synchronized void record(Entry entry) {
		remember(entry);
		writer.execute(() -> append(entry));
	}

	/** Newest last. {@code name} null means everyone. */
	public synchronized List<Entry> recent(@Nullable String name, int count) {
		Deque<Entry> source = name == null ? overall : byName.get(name);
		if (source == null) {
			return List.of();
		}
		List<Entry> all = new ArrayList<>(source);
		return all.subList(Math.max(0, all.size() - count), all.size());
	}

	public synchronized List<String> names() {
		return new ArrayList<>(new TreeSet<>(byName.keySet()));
	}

	private void remember(Entry entry) {
		Deque<Entry> forPlayer = byName.computeIfAbsent(entry.name(), n -> new ArrayDeque<>());
		forPlayer.addLast(entry);
		while (forPlayer.size() > PER_PLAYER) {
			forPlayer.removeFirst();
		}
		overall.addLast(entry);
		while (overall.size() > OVERALL) {
			overall.removeFirst();
		}
	}

	private void load() {
		try {
			Files.createDirectories(dir);
			FilePermissions.ownerOnly(dir);
		} catch (IOException e) {
			LOGGER.error("Could not create {}", dir, e);
			return;
		}
		cleanup();
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
			stream.forEach(files::add);
		} catch (IOException e) {
			LOGGER.error("Could not read {}", dir, e);
		}
		files.sort(null);
		for (Path file : files) {
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					Entry entry = parse(line);
					if (entry != null) {
						synchronized (this) {
							remember(entry);
						}
					}
				}
			} catch (IOException e) {
				LOGGER.error("Could not read {}", file, e);
			}
		}
	}

	private static @Nullable Entry parse(String line) {
		try {
			Entry entry = GSON.fromJson(line, Entry.class);
			return entry == null || entry.player() == null || entry.name() == null || entry.text() == null ? null : entry;
		} catch (JsonParseException e) {
			return null;
		}
	}

	private void append(Entry entry) {
		LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(entry.time()), clock.getZone());
		if (!day.equals(lastCleanup)) {
			cleanup();
		}
		Path file = dir.resolve(day + ".jsonl");
		try {
			boolean created = Files.notExists(file);
			try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
				out.write(GSON.toJson(entry));
				out.newLine();
			}
			if (created) {
				FilePermissions.ownerOnly(file);
			}
		} catch (IOException e) {
			LOGGER.error("Could not write {}", file, e);
		}
	}

	/** Deletes day files older than keepDays. */
	void cleanup() {
		LocalDate today = LocalDate.now(clock);
		lastCleanup = today;
		LocalDate oldest = today.minusDays(keepDays - 1L);
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jsonl")) {
			for (Path file : stream) {
				String name = file.getFileName().toString();
				try {
					if (LocalDate.parse(name.substring(0, name.length() - ".jsonl".length())).isBefore(oldest)) {
						Files.deleteIfExists(file);
					}
				} catch (DateTimeParseException | IOException ignored) {
					// not one of ours, or already gone
				}
			}
		} catch (IOException e) {
			LOGGER.error("Could not clean up {}", dir, e);
		}
	}

	@Override
	public void close() {
		writer.shutdown();
		try {
			writer.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
		}
	}

	public static String scopeLabel(String scope) {
		return switch (scope.toLowerCase(Locale.ROOT)) {
			case "group" -> "group";
			case "whisper" -> "whisper";
			case "nearby" -> "nearby";
			default -> "";
		};
	}
}
