package io.github.harryforest2003.svmsubtitles.transcribe;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Checks speech server addresses and derives the companion server's other endpoints from them. */
public final class Endpoints {
	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	private Endpoints() {
	}

	/**
	 * Voice audio is personal data: it may only travel unencrypted inside your own network.
	 * Credentials in the URL are rejected too, since URLs end up in logs.
	 */
	public static URI checked(String url, boolean allowInsecureHttp) throws IOException {
		URI uri;
		try {
			uri = URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid speech server URL: " + e.getMessage());
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http")) {
			throw new IOException("The speech server URL must start with https:// or http://");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IOException("The speech server URL has no host name");
		}
		if (uri.getRawUserInfo() != null) {
			throw new IOException("Put the API key in the apiKey setting, not in the URL");
		}
		if (scheme.equals("http") && !allowInsecureHttp && !isPrivateHost(uri.getHost())) {
			throw new IOException("Refusing to send voice audio unencrypted over the internet to " + uri.getHost()
					+ ". Use an https:// address, or set transcription.remote.allowInsecureHttp if you really want this.");
		}
		return uri;
	}

	/** Loopback, LAN, link-local, carrier-grade NAT (Tailscale) and single-label/.local/.lan names. */
	public static boolean isPrivateHost(String host) {
		String h = host.toLowerCase(Locale.ROOT);
		if (h.startsWith("[") && h.endsWith("]")) {
			h = h.substring(1, h.length() - 1);
		}
		if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan")
				|| h.endsWith(".home.arpa") || h.endsWith(".internal")) {
			return true;
		}
		boolean ipLiteral = IPV4.matcher(h).matches() || h.contains(":");
		if (!ipLiteral) {
			return !h.contains(".");
		}
		try {
			InetAddress address = InetAddress.getByName(h); // a literal, so no DNS lookup
			if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
				return true;
			}
			byte[] bytes = address.getAddress();
			if (bytes.length == 4) {
				return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64; // 100.64.0.0/10
			}
			return (bytes[0] & 0xFE) == 0xFC; // fc00::/7 unique local
		} catch (UnknownHostException e) {
			return false;
		}
	}

	/** "https://host/v1/audio/transcriptions" becomes "https://host"; a path prefix before /v1/ is kept. */
	public static URI base(URI transcriptions) {
		String path = transcriptions.getRawPath() == null ? "" : transcriptions.getRawPath();
		int cut = path.indexOf("/v1/");
		if (cut < 0) {
			cut = path.indexOf("/audio/");
		}
		String basePath = cut < 0 ? "" : path.substring(0, cut);
		return URI.create(transcriptions.getScheme() + "://" + transcriptions.getRawAuthority() + basePath);
	}

	public static URI resolve(URI base, String path) {
		String root = base.toString().replaceAll("/+$", "");
		return URI.create(root + (path.startsWith("/") ? path : "/" + path));
	}

	/** The OpenAI-style translation endpoint next to a transcription endpoint. */
	public static URI translations(URI transcriptions) {
		String url = transcriptions.toString();
		int at = url.lastIndexOf("/transcriptions");
		return at < 0 ? transcriptions : URI.create(url.substring(0, at) + "/translations" + url.substring(at + "/transcriptions".length()));
	}
}
