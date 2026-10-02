package io.github.harryforest2003.svmsubtitles.transcribe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointsTest {
	@Test
	void plainHttpOnlyInsideYourOwnNetwork() {
		assertDoesNotThrow(() -> Endpoints.checked("http://192.168.1.50:8000/v1/audio/transcriptions", false));
		assertDoesNotThrow(() -> Endpoints.checked("http://localhost:8000/v1/audio/transcriptions", false));
		assertDoesNotThrow(() -> Endpoints.checked("http://gaming-pc:8000/v1/audio/transcriptions", false));
		assertDoesNotThrow(() -> Endpoints.checked("https://me-svm.hf.space/v1/audio/transcriptions", false));
		assertThrows(IOException.class, () -> Endpoints.checked("http://me-svm.hf.space/v1/audio/transcriptions", false));
		assertThrows(IOException.class, () -> Endpoints.checked("http://8.8.8.8/v1/audio/transcriptions", false));
		assertDoesNotThrow(() -> Endpoints.checked("http://8.8.8.8/v1/audio/transcriptions", true));
	}

	@Test
	void rejectsOddUrls() {
		assertThrows(IOException.class, () -> Endpoints.checked("ftp://example.com/x", false));
		assertThrows(IOException.class, () -> Endpoints.checked("https://user:secret@example.com/v1/audio/transcriptions", false));
		assertThrows(IOException.class, () -> Endpoints.checked("not a url", false));
	}

	@Test
	void privateAddresses() {
		assertTrue(Endpoints.isPrivateHost("10.0.0.5"));
		assertTrue(Endpoints.isPrivateHost("172.20.1.1"));
		assertTrue(Endpoints.isPrivateHost("100.101.102.103")); // Tailscale
		assertTrue(Endpoints.isPrivateHost("[::1]"));
		assertTrue(Endpoints.isPrivateHost("fd12:3456::1"));
		assertTrue(Endpoints.isPrivateHost("server.local"));
		assertFalse(Endpoints.isPrivateHost("172.32.0.1"));
		assertFalse(Endpoints.isPrivateHost("example.com"));
		assertFalse(Endpoints.isPrivateHost("2001:4860:4860::8888"));
	}

	@Test
	void derivesCompanionEndpoints() {
		URI url = URI.create("https://me-svm.hf.space/v1/audio/transcriptions");
		assertEquals("https://me-svm.hf.space", Endpoints.base(url).toString());
		assertEquals("https://me-svm.hf.space/svm/v1/stream", Endpoints.resolve(Endpoints.base(url), "/svm/v1/stream").toString());
		assertEquals("https://me-svm.hf.space/v1/audio/translations", Endpoints.translations(url).toString());
		URI prefixed = URI.create("http://192.168.1.5:8000/whisper/v1/audio/transcriptions");
		assertEquals("http://192.168.1.5:8000/whisper", Endpoints.base(prefixed).toString());
	}
}
