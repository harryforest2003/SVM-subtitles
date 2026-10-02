package io.github.harryforest2003.svmsubtitles.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who asked not to be transcribed, and who doesn't want to see subtitles. Stored in players.json. */
public final class PlayerPreferences {
	private static final Logger LOGGER = LoggerFactory.getLogger("SVM Subtitles");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
	private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();

	private PlayerPreferences(Path file) {
		this.file = file;
	}

	public static PlayerPreferences load(Path file) {
		PlayerPreferences preferences = new PlayerPreferences(file);
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				Stored stored = GSON.fromJson(reader, Stored.class);
				if (stored != null) {
					if (stored.optedOut != null) preferences.optedOut.addAll(stored.optedOut);
					if (stored.hidden != null) preferences.hidden.addAll(stored.hidden);
				}
			} catch (IOException | JsonParseException e) {
				LOGGER.error("Could not read {}", file, e);
			}
		}
		return preferences;
	}

	public boolean isOptedOut(UUID player) {
		return optedOut.contains(player);
	}

	public boolean isHidden(UUID player) {
		return hidden.contains(player);
	}

	public void setOptedOut(UUID player, boolean value) {
		update(optedOut, player, value);
	}

	public void setHidden(UUID player, boolean value) {
		update(hidden, player, value);
	}

	private void update(Set<UUID> set, UUID player, boolean value) {
		if (value ? set.add(player) : set.remove(player)) {
			save();
		}
	}

	private synchronized void save() {
		Stored stored = new Stored();
		stored.optedOut = new TreeSet<>(optedOut);
		stored.hidden = new TreeSet<>(hidden);
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(stored, writer);
			}
		} catch (IOException e) {
			LOGGER.error("Could not write {}", file, e);
		}
	}

	private static final class Stored {
		Set<UUID> optedOut;
		Set<UUID> hidden;
	}
}
