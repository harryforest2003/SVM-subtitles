package io.github.harryforest2003.svmsubtitles.transcribe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.harryforest2003.svmsubtitles.audio.Resampler;
import io.github.harryforest2003.svmsubtitles.audio.Wav;
import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The extras the bundled companion server offers on top of plain transcription: live captions and
 * text-to-speech. Everything is asynchronous, so no game or audio thread ever waits on the network.
 */
final class CompanionClient implements AutoCloseable {
	record Features(boolean stream, boolean tts, boolean translate) {
		static final Features NONE = new Features(false, false, false);
	}

	private final HttpClient client = RemoteTranscriber.newClient(10);
	private final URI base;
	private final String apiKey;
	private final @Nullable URI speechUri;
	private final String speechKey;
	private final SubtitlesConfig.Tts tts;

	CompanionClient(SubtitlesConfig.Remote remote, SubtitlesConfig.Tts tts) throws IOException {
		this.base = remote.url.isBlank() ? null : Endpoints.base(Endpoints.checked(remote.url, remote.allowInsecureHttp));
		this.apiKey = remote.apiKey.trim();
		this.tts = tts;
		if (!tts.url.isBlank()) {
			this.speechUri = Endpoints.checked(tts.url, remote.allowInsecureHttp);
			this.speechKey = tts.apiKey.isBlank() ? apiKey : tts.apiKey.trim();
		} else {
			this.speechUri = base == null ? null : Endpoints.resolve(base, "/v1/audio/speech");
			this.speechKey = apiKey;
		}
	}

	/** Asks the companion what it can do. Other OpenAI-compatible servers just get the basics. */
	Features fetchFeatures() throws InterruptedException {
		if (base == null) {
			return Features.NONE;
		}
		try {
			HttpResponse<String> response = client.send(authorized(HttpRequest.newBuilder(Endpoints.resolve(base, "/health")), apiKey)
					.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				return Features.NONE;
			}
			JsonElement features = JsonParser.parseString(response.body()).getAsJsonObject().get("features");
			Set<String> names = new HashSet<>();
			if (features instanceof JsonArray array) {
				array.forEach(element -> names.add(element.getAsString()));
			}
			return new Features(names.contains("stream"), names.contains("tts"), names.contains("translate"));
		} catch (IOException | RuntimeException e) {
			return Features.NONE;
		}
	}

	boolean hasSpeechEndpoint() {
		return speechUri != null;
	}

	/** Sends new audio for a live caption session and returns the text so far (null if the server skipped it). */
	CompletableFuture<@Nullable Transcript> stream(UUID session, float[] chunk, SpeechRequest request) {
		Multipart body = new Multipart()
				.field("session", session.toString())
				.field("task", request.translate() ? "translate" : "transcribe");
		if (!request.translate() && !request.language().equals("auto")) {
			body.field("language", request.language());
		}
		if (!request.prompt().isEmpty()) {
			body.field("prompt", request.prompt());
		}
		body.file("audio", "chunk.wav", "audio/wav", Wav.encode(chunk, Resampler.OUTPUT_RATE));
		HttpRequest http = authorized(HttpRequest.newBuilder(Endpoints.resolve(base, "/svm/v1/stream")), apiKey)
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", body.contentType())
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.finish()))
				.build();
		return client.sendAsync(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new IllegalStateException(RemoteTranscriber.describeError(response.statusCode(), base.getHost(), response.body()));
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonElement text = json.get("text");
			if (text == null || text.isJsonNull()) {
				return null;
			}
			JsonElement language = json.get("language");
			return new Transcript(text.getAsString().trim(), language == null || language.isJsonNull() ? null : language.getAsString());
		});
	}

	/** Text-to-speech through an OpenAI-style /v1/audio/speech endpoint. Returns a WAV file. */
	CompletableFuture<byte[]> speech(String text) {
		JsonObject json = new JsonObject();
		json.addProperty("model", tts.model);
		json.addProperty("input", text);
		if (!tts.voice.isBlank()) {
			json.addProperty("voice", tts.voice);
		}
		json.addProperty("response_format", "wav");
		HttpRequest http = authorized(HttpRequest.newBuilder(speechUri), speechKey)
				.timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
				.build();
		return client.sendAsync(http, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new IllegalStateException(RemoteTranscriber.describeError(response.statusCode(), speechUri.getHost(),
						new String(response.body(), StandardCharsets.UTF_8)));
			}
			return response.body();
		});
	}

	private static HttpRequest.Builder authorized(HttpRequest.Builder builder, String key) {
		builder.header("User-Agent", "SVM-Subtitles");
		if (!key.isEmpty()) {
			builder.header("Authorization", "Bearer " + key);
		}
		return builder;
	}

	@Override
	public void close() {
		client.close();
	}
}
