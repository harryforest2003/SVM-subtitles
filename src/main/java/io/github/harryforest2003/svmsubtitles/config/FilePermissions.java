package io.github.harryforest2003.svmsubtitles.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

/** Makes files with secrets or personal data readable only by the account running the game or server. */
public final class FilePermissions {
	private FilePermissions() {
	}

	public static void ownerOnly(Path path) {
		if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
			return; // Windows: user profile folders are already private by default
		}
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
		} catch (IOException | UnsupportedOperationException ignored) {
			// best effort
		}
	}
}
