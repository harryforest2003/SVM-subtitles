package io.github.harryforest2003.svmsubtitles.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertMatcherTest {
	@Test
	void nameWithoutTrailingDigitsAlsoCounts() {
		assertEquals(List.of("Harry_2003", "Harry", "boss"), AlertMatcher.terms("Harry_2003", true, List.of("boss", " ")));
		assertEquals(List.of("boss"), AlertMatcher.terms("Harry_2003", false, List.of("boss")));
	}

	@Test
	void findsWholeWordsIgnoringCase() {
		List<String> terms = AlertMatcher.terms("Harry2003", true, List.of("diamonds"));
		List<int[]> found = AlertMatcher.find("hey harry, found DIAMONDS!", terms);
		assertEquals(2, found.size());
		assertArrayEquals(new int[]{4, 9}, found.get(0));
		assertArrayEquals(new int[]{17, 25}, found.get(1));
	}

	@Test
	void ignoresPartsOfOtherWords() {
		assertTrue(AlertMatcher.find("the harrying wind", List.of("harry")).isEmpty());
		assertTrue(AlertMatcher.find("nothing here", List.of("harry")).isEmpty());
	}

	@Test
	void overlappingMatchesAreMerged() {
		List<int[]> found = AlertMatcher.find("Harry Forest is here", List.of("Harry", "Harry Forest"));
		assertEquals(1, found.size());
		assertArrayEquals(new int[]{0, 12}, found.getFirst());
	}
}
