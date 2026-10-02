package io.github.harryforest2003.svmsubtitles.transcribe;

import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextCleanerTest {
	private static final List<String> IGNORED = new SubtitlesConfig.Chat().ignoredPhrases;

	@Test
	void trimsAndKeepsNormalSpeech() {
		assertEquals("Watch out, there's a creeper behind you!", clean("  Watch out, there's a creeper behind you!  "));
	}

	@Test
	void removesSoundAnnotations() {
		assertEquals("let's go", clean("[MUSIC] let's go (laughs) ♪"));
		assertNull(clean("[BLANK_AUDIO]"));
		assertNull(clean("(wind blowing)"));
		assertNull(clean(" ... "));
	}

	@Test
	void dropsCommonHallucinations() {
		assertNull(clean(" Thanks for watching!"));
		assertNull(clean("Subtitles by the Amara.org community"));
		assertNull(clean(" you"));
		assertEquals("Thank you, you saved me.", clean("Thank you, you saved me."));
	}

	@Test
	void collapsesRepeatedSentences() {
		assertEquals("Okay. Let's go.", clean("Okay. Okay. Okay. Let's go."));
	}

	@Test
	void stripsFormattingCodesAndControlCharacters() {
		assertEquals("red text new line", TextCleaner.sanitize("§cred text\nnew\u202E line"));
		assertEquals("hello world", clean("hello\u0000 §kworld"));
	}

	@Test
	void dropsAnEchoOfTheSpellingHints() {
		String prompt = "Minecraft voice chat. Players: Steve, Alex. creeper, Nether.";
		assertNull(TextCleaner.clean("Minecraft voice chat.", IGNORED, prompt));
		assertEquals("Steve", TextCleaner.clean("Steve", IGNORED, prompt));
		assertEquals("Steve, there's a creeper", TextCleaner.clean("Steve, there's a creeper", IGNORED, prompt));
	}

	@Test
	void capsVeryLongOutput() {
		String text = clean("word ".repeat(200));
		assertEquals(256, text.length());
	}

	private static String clean(String raw) {
		return TextCleaner.clean(raw, IGNORED);
	}
}
