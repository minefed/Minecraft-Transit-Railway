package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

final class TransitApiConfigTest {

	@Test
	void disabledConfigDoesNotNeedCredentialAndPublicUrlCanDifferFromPrivateListener() throws Exception {
		assertFalse(TransitApiConfig.parse("{}", ignored -> null).enabled());
		final TransitApiConfig config = TransitApiConfig.parse("{\"apiBaseUrl\":\"http://127.0.0.1:8088/v1/transit/minefed/\",\"publicApiBaseUrl\":\"https://api.example.com/v1/transit/minefed\"}", ignored -> "x".repeat(32));
		assertEquals("http://127.0.0.1:8088/v1/transit/minefed", config.apiBase);
		assertEquals("https://api.example.com/v1/transit/minefed", config.publicApiBase);
	}

	@Test
	void rejectsUnsafeTargetsAndMismatchedDatasetWithoutDisclosingInputs() {
		for (String url : new String[]{"http://api.example.com/v1/transit/minefed", "https://user:secret@example.com/v1/transit/minefed", "https://example.com/v1/transit/minefed?token=secret", "https://example.com/v1/transit/minefed#secret", "https://example.com/v1/transit/../admin", "https://example.com/v1/transit/minefed%2fadmin", "https://example.com:0/v1/transit/minefed"}) {
			final IOException error = assertThrows(IOException.class, () -> TransitApiConfig.parse("{\"apiBaseUrl\":\"" + url + "\"}", ignored -> "x".repeat(32)));
			assertFalse(error.toString().contains("secret"));
			assertNull(error.getCause());
		}
		assertThrows(IOException.class, () -> TransitApiConfig.parse("{\"apiBaseUrl\":\"https://example.com/v1/transit/a\",\"publicApiBaseUrl\":\"https://example.com/v1/transit/b\"}", ignored -> "x".repeat(32)));
	}

	@Test
	void credentialsMustComeFromNamedEnvironmentAndMustBeHeaderSafe() throws Exception {
		final String json = "{\"apiBaseUrl\":\"https://example.com/v1/transit/minefed\",\"tokenEnvironment\":\"TEST_TRANSIT_TOKEN\"}";
		final TransitApiConfig config = TransitApiConfig.parse(json, name -> name.equals("TEST_TRANSIT_TOKEN") ? "x".repeat(32) : null);
		assertEquals("x".repeat(32), config.token);
		for (String invalid : new String[]{"", "short", "x".repeat(32) + "\r\nheader", "x".repeat(32) + ",other"}) assertThrows(IOException.class, () -> TransitApiConfig.parse(json, ignored -> invalid));
		assertThrows(IOException.class, () -> TransitApiConfig.parse(json, ignored -> null));
		assertThrows(IOException.class, () -> TransitApiConfig.parse("{\"token\":\"secret\"}", ignored -> null));
	}
}
