package io.github.harryforest2003.svmsubtitles.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceHistoryTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void keepsLinesAcrossRestartsAndOnlyForTheOwner(@TempDir Path dir) throws Exception {
		UUID steve = UUID.randomUUID();
		try (VoiceHistory history = new VoiceHistory(dir, 7, CLOCK)) {
			history.record(new VoiceHistory.Entry(CLOCK.millis(), steve, "Steve", "voice", "everyone", "hello"));
			history.record(new VoiceHistory.Entry(CLOCK.millis(), UUID.randomUUID(), "Alex", "tts", "group", "hi"));
			assertEquals(1, history.recent("steve", 10).size());
			assertEquals(2, history.recent(null, 10).size());
		}
		Path file = dir.resolve("2026-10-02.jsonl");
		assertTrue(Files.exists(file));
		if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
		}

		try (VoiceHistory reloaded = new VoiceHistory(dir, 7, CLOCK)) {
			List<VoiceHistory.Entry> lines = reloaded.recent("Steve", 10);
			assertEquals(1, lines.size());
			assertEquals("hello", lines.getFirst().text());
			assertEquals(List.of("Alex", "Steve"), reloaded.names());
		}
	}

	@Test
	void deletesDaysOlderThanTheLimit(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("2026-09-20.jsonl"), "");
		Files.writeString(dir.resolve("2026-09-26.jsonl"), "");
		Files.writeString(dir.resolve("notes.txt"), "keep me");
		try (VoiceHistory ignored = new VoiceHistory(dir, 7, CLOCK)) {
			assertFalse(Files.exists(dir.resolve("2026-09-20.jsonl")));
			assertTrue(Files.exists(dir.resolve("2026-09-26.jsonl")));
			assertTrue(Files.exists(dir.resolve("notes.txt")));
		}
	}

	@Test
	void ignoresDamagedLines(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("2026-10-02.jsonl"), "not json\n{\"text\":\"missing fields\"}\n");
		try (VoiceHistory history = new VoiceHistory(dir, 7, CLOCK)) {
			assertTrue(history.recent(null, 10).isEmpty());
		}
	}
}
