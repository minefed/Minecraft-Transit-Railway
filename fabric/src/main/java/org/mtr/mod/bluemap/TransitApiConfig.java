package org.mtr.mod.bluemap;

import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;

/** Only the public base URL is written to BlueMap. The credential exists in server memory only. */
final class TransitApiConfig {

	static final String INITIAL = "{\"apiBaseUrl\":\"\",\"publicApiBaseUrl\":\"\",\"tokenEnvironment\":\"MINEFED_TRANSIT_TOKEN\"}\n";
	final String apiBase;
	final String publicApiBase;
	final String token;

	private TransitApiConfig(String apiBase, String publicApiBase, String token) {
		this.apiBase = apiBase;
		this.publicApiBase = publicApiBase;
		this.token = token;
	}

	static TransitApiConfig load(Path file) throws IOException {
		BlueMapLiveBridge.createInitial(file, INITIAL);
		if (Files.size(file) > 16384) throw new IOException("Transit API configuration exceeds 16 KiB");
		return parse(Files.readString(file), System::getenv);
	}

	static TransitApiConfig parse(String contents, Function<String, String> environment) throws IOException {
		try {
			final JsonObject json = JsonParser.parseString(contents).getAsJsonObject();
			for (String key : json.keySet()) {
				if (!Set.of("apiBaseUrl", "publicApiBaseUrl", "tokenEnvironment").contains(key)) throw new IllegalArgumentException();
			}
			final String base = string(json, "apiBaseUrl", "");
			if (base.isEmpty()) return new TransitApiConfig("", "", "");
			final String api = validateBase(base);
			final String publicValue = string(json, "publicApiBaseUrl", "");
			final String publicApi = publicValue.isEmpty() ? api : validateBase(publicValue);
			if (!URI.create(api).getPath().equals(URI.create(publicApi).getPath())) throw new IllegalArgumentException();
			final String variable = string(json, "tokenEnvironment", "MINEFED_TRANSIT_TOKEN");
			if (!variable.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException();
			final String token = environment.apply(variable);
			if (token == null || token.length() < 32 || token.length() > 4096 || !token.chars().allMatch(c -> c >= 33 && c <= 126 && c != ',')) throw new IllegalArgumentException();
			return new TransitApiConfig(api, publicApi, token);
		} catch (RuntimeException e) {
			// Do not include the original exception: malformed URIs/JSON may contain credentials.
			throw new IOException("Invalid transit API configuration or missing server token; use HTTPS (HTTP only on loopback) and matching /v1/transit/{dataset} paths");
		}
	}

	static String validateBase(String value) {
		final URI uri = URI.create(value);
		final String host = uri.getHost();
		final boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
		if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
				|| !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback)
				|| !uri.getRawPath().matches("/v1/transit/[A-Za-z0-9][A-Za-z0-9_-]{0,95}/?")) throw new IllegalArgumentException();
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static String string(JsonObject object, String key, String fallback) {
		if (!object.has(key)) return fallback;
		if (!object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
		return object.get(key).getAsString();
	}

	boolean enabled() {
		return !apiBase.isEmpty();
	}
}
