package io.github.harryforest2003.svmsubtitles.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds your name (and other watched words) in a subtitle, as whole words, ignoring case. */
public final class AlertMatcher {
	private AlertMatcher() {
	}

	/**
	 * Your name, plus the name without trailing digits/underscores ("Harry_2003" also matches "Harry"),
	 * plus any extra words.
	 */
	public static List<String> terms(String ownName, boolean includeOwnName, Collection<String> words) {
		Set<String> terms = new LinkedHashSet<>();
		if (includeOwnName && ownName != null && !ownName.isBlank()) {
			terms.add(ownName.trim());
			String stem = ownName.trim().replaceAll("[_\\d]+$", "");
			if (stem.length() >= 3) {
				terms.add(stem);
			}
		}
		for (String word : words) {
			if (word != null && word.trim().length() >= 2) {
				terms.add(word.trim());
			}
		}
		return List.copyOf(terms);
	}

	/** Start/end offsets of every match in {@code text}, sorted and without overlaps. */
	public static List<int[]> find(String text, Collection<String> terms) {
		String lower = text.toLowerCase(Locale.ROOT);
		List<int[]> matches = new ArrayList<>();
		for (String term : terms) {
			String needle = term.toLowerCase(Locale.ROOT);
			for (int at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + 1)) {
				int end = at + needle.length();
				if (isBoundary(lower, at - 1) && isBoundary(lower, end)) {
					matches.add(new int[]{at, end});
				}
			}
		}
		matches.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));
		List<int[]> merged = new ArrayList<>();
		for (int[] match : matches) {
			if (!merged.isEmpty() && match[0] < merged.getLast()[1]) {
				merged.getLast()[1] = Math.max(merged.getLast()[1], match[1]);
			} else {
				merged.add(match);
			}
		}
		return merged;
	}

	private static boolean isBoundary(String text, int index) {
		return index < 0 || index >= text.length() || !Character.isLetterOrDigit(text.charAt(index));
	}
}
