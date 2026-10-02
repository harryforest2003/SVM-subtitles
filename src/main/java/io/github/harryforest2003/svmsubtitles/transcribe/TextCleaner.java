package io.github.harryforest2003.svmsubtitles.transcribe;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tidies Whisper output and throws away the things it invents when it only hears noise. */
public final class TextCleaner {
	private static final Pattern ANNOTATIONS = Pattern.compile("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫]");
	private static final Pattern SENTENCE = Pattern.compile("[^.!?]+[.!?]*");
	private static final int MAX_LENGTH = 256;

	private TextCleaner() {
	}

	public static @Nullable String clean(@Nullable String raw, List<String> ignoredPhrases) {
		return clean(raw, ignoredPhrases, "");
	}

	/**
	 * @param prompt the hint Whisper was given; on near-silence Whisper sometimes just repeats it back
	 */
	public static @Nullable String clean(@Nullable String raw, List<String> ignoredPhrases, String prompt) {
		if (raw == null) {
			return null;
		}
		String text = ANNOTATIONS.matcher(sanitize(raw)).replaceAll(" ");
		text = text.replace(">>", " ").replaceAll("\\s+", " ").trim();
		text = text.replaceFirst("^[-–—\\s]+", "");
		if (text.codePoints().noneMatch(Character::isLetterOrDigit)) {
			return null;
		}

		String normalized = normalize(text);
		if (normalized.equals("you")) {
			return null;
		}
		if (!prompt.isEmpty() && normalized.split(" ").length >= 3 && normalize(prompt).contains(normalized)) {
			return null;
		}
		for (String phrase : ignoredPhrases) {
			String p = normalize(phrase);
			if (!p.isEmpty() && normalized.contains(p)) {
				return null;
			}
		}

		text = dropRepeatedSentences(text);
		if (text.length() > MAX_LENGTH) {
			text = text.substring(0, MAX_LENGTH - 3).trim() + "...";
		}
		return text;
	}

	/**
	 * Removes Minecraft formatting codes (§) and control characters, so text from a speech server or typed by a
	 * player can never restyle chat, break lines or hide content.
	 */
	public static String sanitize(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '§') {
				if (i < text.length()) {
					i += Character.charCount(text.codePointAt(i)); // the formatting letter after it
				}
				continue;
			}
			if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT || cp == 0x2028 || cp == 0x2029) {
				out.append(' ');
				continue;
			}
			out.appendCodePoint(cp);
		}
		return out.toString().replaceAll(" {2,}", " ").trim();
	}

	/** Whisper sometimes loops on one sentence ("Okay. Okay. Okay. Okay."). Keep a single copy. */
	static String dropRepeatedSentences(String text) {
		List<String> kept = new ArrayList<>();
		String previous = null;
		Matcher matcher = SENTENCE.matcher(text);
		while (matcher.find()) {
			String sentence = matcher.group().trim();
			if (sentence.isEmpty()) {
				continue;
			}
			String key = normalize(sentence);
			if (!key.equals(previous)) {
				kept.add(sentence);
				previous = key;
			}
		}
		return kept.isEmpty() ? text : String.join(" ", kept);
	}

	static String normalize(String text) {
		StringBuilder out = new StringBuilder(text.length());
		boolean space = false;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isLetterOrDigit(cp)) {
				if (space && !out.isEmpty()) {
					out.append(' ');
				}
				out.appendCodePoint(Character.toLowerCase(cp));
				space = false;
			} else if (cp != '\'' && cp != '’') {
				space = true;
			}
		}
		return out.toString().toLowerCase(Locale.ROOT);
	}
}
