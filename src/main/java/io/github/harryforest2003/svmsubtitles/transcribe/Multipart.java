package io.github.harryforest2003.svmsubtitles.transcribe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A multipart/form-data request body. */
final class Multipart {
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private final String boundary = "svm-subtitles-" + UUID.randomUUID();

	Multipart field(String name, String value) {
		write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
		return this;
	}

	Multipart file(String name, String filename, String contentType, byte[] data) {
		write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
				+ "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
		out.writeBytes(data);
		write("\r\n");
		return this;
	}

	String contentType() {
		return "multipart/form-data; boundary=" + boundary;
	}

	byte[] finish() {
		write("--" + boundary + "--\r\n");
		return out.toByteArray();
	}

	private void write(String text) {
		out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
	}
}
