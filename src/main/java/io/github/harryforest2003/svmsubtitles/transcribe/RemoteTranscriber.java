package io.github.harryforest2003.svmsubtitles.transcribe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.harryforest2003.svmsubtitles.audio.Resampler;
import io.github.harryforest2003.svmsubtitles.audio.Wav;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Sends audio to an OpenAI-compatible {@code /v1/audio/transcriptions} endpoint: the bundled companion
 * server, faster-whisper-server/speaches, LocalAI, Groq, OpenAI, ... The game only does an HTTP upload,
 * so even a weak PC can show subtitles when the recognition runs on another machine.
 */
final class RemoteTranscriber implements Transcriber {
	private final HttpClient client;
	private final URI uri;
	private final String apiKey;
	private final String model;
	private final Duration timeout;

	RemoteTranscriber(SubtitlesConfig.Remote config) {
		this.uri = URI.create(config.url.trim());
		this.apiKey = config.apiKey.trim();
		this.model = config.model.trim();
		this.timeout = Duration.ofSeconds(config.timeoutSeconds);
		this.client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(Math.min(10, config.timeoutSeconds)))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@Override
	public String transcribe(float[] samples, String language) throws IOException, InterruptedException {
		String boundary = "svm-subtitles-" + UUID.randomUUID();
		Multipart body = new Multipart(boundary);
		if (!model.isEmpty()) {
			body.field("model", model);
		}
		if (!language.isEmpty() && !language.equals("auto")) {
			body.field("language", language);
		}
		body.field("response_format", "json");
		body.field("temperature", "0");
		body.file("file", "speech.wav", "audio/wav", Wav.encode(samples, Resampler.OUTPUT_RATE));

		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.header("User-Agent", "SVM-Subtitles")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.finish()));
		if (!apiKey.isEmpty()) {
			request.header("Authorization", "Bearer " + apiKey);
		}

		HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		String text = response.body() == null ? "" : response.body();
		if (response.statusCode() / 100 != 2) {
			String detail = text.length() > 300 ? text.substring(0, 300) + "..." : text;
			throw new IOException("HTTP " + response.statusCode() + " from " + uri.getHost() + ": " + detail.strip());
		}
		return parseText(text);
	}

	static String parseText(String body) {
		String trimmed = body.trim();
		if (!trimmed.startsWith("{")) {
			return trimmed;
		}
		JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
		JsonElement text = json.get("text");
		return text == null || text.isJsonNull() ? "" : text.getAsString().trim();
	}

	@Override
	public String describe() {
		return "remote server " + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
	}

	@Override
	public boolean isLocal() {
		return false;
	}

	@Override
	public void close() {
		client.close();
	}

	private static final class Multipart {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();
		private final String boundary;

		Multipart(String boundary) {
			this.boundary = boundary;
		}

		void field(String name, String value) {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
		}

		void file(String name, String filename, String contentType, byte[] data) {
			write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
					+ "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
			out.writeBytes(data);
			write("\r\n");
		}

		byte[] finish() {
			write("--" + boundary + "--\r\n");
			return out.toByteArray();
		}

		private void write(String text) {
			out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
		}
	}
}
