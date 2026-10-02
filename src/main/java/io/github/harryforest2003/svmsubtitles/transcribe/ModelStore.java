package io.github.harryforest2003.svmsubtitles.transcribe;

import io.github.harryforest2003.svmsubtitles.config.SubtitlesConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Finds the whisper.cpp model on disk, downloading it once if needed. */
final class ModelStore {
	private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]+");
	private static final int GGML_MAGIC = 0x67676d6c;

	private ModelStore() {
	}

	static Path resolve(SubtitlesConfig.Local config, Path modelsDir, Consumer<String> progress) throws IOException, InterruptedException {
		String model = config.model.trim();
		if (model.toLowerCase(Locale.ROOT).endsWith(".bin")) {
			Path path = Path.of(model);
			if (!path.isAbsolute()) {
				path = modelsDir.resolve(model);
			}
			if (!Files.isRegularFile(path)) {
				throw new IOException("Model file not found: " + path);
			}
			return path;
		}
		if (!MODEL_NAME.matcher(model).matches()) {
			throw new IOException("Invalid model name '" + model + "'");
		}

		Path target = modelsDir.resolve("ggml-" + model + ".bin");
		if (Files.isRegularFile(target) && isModelFile(target)) {
			return target;
		}
		if (!config.autoDownload) {
			throw new IOException("Model " + target + " is missing and transcription.local.autoDownload is off");
		}
		download(config.downloadUrl.replace("{model}", model), target, model, progress);
		return target;
	}

	private static void download(String url, Path target, String model, Consumer<String> progress) throws IOException, InterruptedException {
		Files.createDirectories(target.getParent());
		Path partial = target.resolveSibling(target.getFileName() + ".part");
		try (HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "SVM-Subtitles").GET().build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = response.body()) {
				if (response.statusCode() != 200) {
					throw new IOException("Downloading " + url + " failed with HTTP " + response.statusCode());
				}
				long size = response.headers().firstValueAsLong("content-length").orElse(-1);
				progress.accept("Downloading speech model " + model + (size > 0 ? " (" + size / 1_000_000 + " MB)" : "")
						+ ". This only happens once.");
				Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!isModelFile(partial)) {
				throw new IOException("Downloaded file from " + url + " is not a whisper.cpp model");
			}
			try {
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	private static boolean isModelFile(Path path) throws IOException {
		if (Files.size(path) < 1_000_000) {
			return false;
		}
		try (InputStream in = Files.newInputStream(path)) {
			byte[] header = in.readNBytes(4);
			return header.length == 4 && ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt() == GGML_MAGIC;
		}
	}
}
