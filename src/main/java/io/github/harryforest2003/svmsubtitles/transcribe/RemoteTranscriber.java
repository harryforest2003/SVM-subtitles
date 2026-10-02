package io.github.harryforest2003.svmsubtitles.transcribe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.harryforest2003.svmsubtitles.audio.Resampler;
import io.github.harryforest2003.svmsubtitles.audio.Wav;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends audio to an OpenAI-compatible {@code /v1/audio/transcriptions} endpoint: the bundled companion
 * server, faster-whisper-server/speaches, LocalAI, Groq, OpenAI, ... The game only does an HTTP upload,
 * so even a weak PC can show subtitles when the recognition runs on another machine.
 *
 * <p>Redirects are never followed: a redirect could otherwise carry the audio and API key somewhere else.
 */
final class RemoteTranscriber implements Transcriber {
	private final HttpClient client;
	private final URI transcriptions;
	private final URI translations;
	private final String apiKey;
	private final String model;
	private final Duration timeout;

	RemoteTranscriber(SubtitlesConfig.Remote config) throws IOException {
		this.transcriptions = Endpoints.checked(config.url, config.allowInsecureHttp);
		this.translations = Endpoints.translations(transcriptions);
		this.apiKey = config.apiKey.trim();
		this.model = config.model.trim();
		this.timeout = Duration.ofSeconds(config.timeoutSeconds);
		this.client = newClient(config.timeoutSeconds);
	}

	static HttpClient newClient(int timeoutSeconds) {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	@Override
	public Transcript transcribe(float[] samples, SpeechRequest request) throws IOException, InterruptedException {
		Multipart body = new Multipart();
		if (!model.isEmpty()) {
			body.field("model", model);
		}
		// The translation endpoint always outputs English and takes no language parameter.
		if (!request.translate() && !request.language().isEmpty() && !request.language().equals("auto")) {
			body.field("language", request.language());
		}
		if (!request.prompt().isEmpty()) {
			body.field("prompt", request.prompt());
		}
		body.field("response_format", "json");
		body.field("temperature", "0");
		body.file("file", "speech.wav", "audio/wav", Wav.encode(samples, Resampler.OUTPUT_RATE));

		HttpRequest.Builder http = HttpRequest.newBuilder(request.translate() ? translations : transcriptions)
				.timeout(timeout)
				.header("Content-Type", body.contentType())
				.header("User-Agent", "SVM-Subtitles")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.finish()));
		if (!apiKey.isEmpty()) {
			http.header("Authorization", "Bearer " + apiKey);
		}

		HttpResponse<String> response = client.send(http.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return parse(response, transcriptions.getHost());
	}

	static Transcript parse(HttpResponse<String> response, String host) throws IOException {
		String text = response.body() == null ? "" : response.body();
		if (response.statusCode() / 100 != 2) {
			throw new IOException(describeError(response.statusCode(), host, text));
		}
		return parseText(text);
	}

	static String describeError(int status, String host, String body) {
		if (status == 401 || status == 403) {
			return "the speech server at " + host + " rejected the API key (HTTP " + status + ")";
		}
		if (status == 429) {
			return "the speech server at " + host + " is rate limiting us (HTTP 429)";
		}
		if (status / 100 == 3) {
			return "the speech server at " + host + " answered with a redirect, which is not followed for safety; check the URL";
		}
		String detail = body.length() > 200 ? body.substring(0, 200) + "..." : body;
		return "HTTP " + status + " from " + host + ": " + TextCleaner.sanitize(detail);
	}

	static Transcript parseText(String body) {
		String trimmed = body.trim();
		if (!trimmed.startsWith("{")) {
			return new Transcript(trimmed, null);
		}
		JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
		JsonElement text = json.get("text");
		JsonElement language = json.get("language");
		return new Transcript(
				text == null || text.isJsonNull() ? "" : text.getAsString().trim(),
				language == null || language.isJsonNull() ? null : language.getAsString());
	}

	@Override
	public String describe() {
		return "speech server " + transcriptions.getHost() + (transcriptions.getPort() > 0 ? ":" + transcriptions.getPort() : "");
	}

	@Override
	public boolean isLocal() {
		return false;
	}

	@Override
	public void close() {
		client.close();
	}
}
