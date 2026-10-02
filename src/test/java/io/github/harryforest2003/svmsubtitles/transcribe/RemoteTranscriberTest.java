package io.github.harryforest2003.svmsubtitles.transcribe;

import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteTranscriberTest {
	@Test
	void readsJsonAndPlainTextResponses() {
		assertEquals("hello there", RemoteTranscriber.parseText("{\"text\": \" hello there \"}").text());
		assertEquals("hello there", RemoteTranscriber.parseText(" hello there\n").text());
		assertEquals("", RemoteTranscriber.parseText("{\"text\": null}").text());
		Transcript translated = RemoteTranscriber.parseText("{\"text\": \"Hello\", \"language\": \"fr\"}");
		assertEquals("fr", translated.language());
		assertTrue(translated.wasTranslated());
	}

	@Test
	void errorMessagesNeverEchoFormattingOrHugeBodies() {
		String message = RemoteTranscriber.describeError(500, "example.com", "§kboom\n" + "x".repeat(1000));
		assertTrue(message.length() < 300, message);
		assertTrue(!message.contains("§") && !message.contains("\n"), message);
		assertTrue(RemoteTranscriber.describeError(401, "example.com", "").contains("API key"));
	}

	/** SVM_TEST_REMOTE_URL=http://127.0.0.1:8000/v1/audio/transcriptions SVM_TEST_REMOTE_KEY=... ./gradlew test */
	@Test
	@EnabledIfEnvironmentVariable(named = "SVM_TEST_REMOTE_URL", matches = ".+")
	void transcribesThroughAServer() throws Exception {
		SubtitlesConfig.Remote config = new SubtitlesConfig.Remote();
		config.url = System.getenv("SVM_TEST_REMOTE_URL");
		config.apiKey = System.getenv().getOrDefault("SVM_TEST_REMOTE_KEY", "");
		try (RemoteTranscriber remote = new RemoteTranscriber(config)) {
			long start = System.nanoTime();
			String text = remote.transcribe(TranscriptionService.benchmarkAudio(), new SpeechRequest("en", false, "Minecraft voice chat.")).text();
			System.out.printf("%s: \"%s\" in %.2f s%n", remote.describe(), text, (System.nanoTime() - start) / 1e9);
			assertTrue(text.toLowerCase().contains("what you can do for your country"), text);
		}
	}
}
