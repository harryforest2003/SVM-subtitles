package io.github.harryforest2003.svmsubtitles.transcribe;

import org.jspecify.annotations.Nullable;

/**
 * What was said. {@code language} is the detected spoken language when the backend reports it, so a
 * translated line can be marked with where it came from.
 */
public record Transcript(String text, @Nullable String language) {
	public boolean wasTranslated() {
		return language != null && !language.isEmpty() && !language.equals("en") && !language.equals("english");
	}
}
