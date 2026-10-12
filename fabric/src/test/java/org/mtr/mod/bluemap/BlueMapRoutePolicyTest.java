package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class BlueMapRoutePolicyTest {

	private static final String MAX_UNSIGNED = "18446744073709551615";

	@Test
	public void initialAndUnconfiguredWorldsPublishNothing() {
		final BlueMapRoutePolicy initial = BlueMapRoutePolicy.parse(BlueMapRoutePolicy.INITIAL, catalogs());
		assertEquals(BlueMapRoutePolicy.CLOSED.fingerprint(), initial.fingerprint());
		for (String world : List.of("world-a", "world-b", "unknown-world")) {
			assertNull(initial.world(world).route(1));
			assertNull(initial.world(world).route(2));
		}
		final BlueMapRoutePolicy selected = policy("{\"routes\":[\"1\"]}");
		assertNotNull(selected.world("world-a").route(1));
		assertNull(selected.world("world-a").route(2));
		assertNull(selected.world("world-b").route(1));
		assertNull(selected.world("unknown-world").route(1));
		assertNull(policy("{}").world("world-a").route(1));
	}

	@Test
	public void explicitSelectionCanPublishHiddenRoutesAndUnsignedIds() {
		final BlueMapRoutePolicy policy = policy("{\"routes\":[\"2\",\"" + MAX_UNSIGNED + "\"]}");
		final BlueMapRoutePolicy.DisplayRoute hidden = policy.world("world-a").route(2);
		assertNotNull(hidden);
		assertEquals("Private branch", hidden.name);
		assertFalse(hidden.grouped);
		assertEquals(MAX_UNSIGNED, policy.world("world-a").route(-1L).id);
		assertNull(policy.world("world-a").route(1));
	}

	@Test
	public void groupsPublishOnlyTheirMembersWithOneDisplayIdentity() {
		final BlueMapRoutePolicy policy = policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"color\":\"#Ab12Cd\",\"routeIds\":[\"1\",\"2\"]}]}");
		final BlueMapRoutePolicy.DisplayRoute first = policy.world("world-a").route(1);
		final BlueMapRoutePolicy.DisplayRoute second = policy.world("world-a").route(2);
		assertSame(first, second);
		assertTrue(first.grouped);
		assertEquals("group:metro", first.id);
		assertEquals("Metro line", first.name);
		assertEquals("#ab12cd", first.color);
		assertNull(policy.world("world-a").route(-1L));
		final BlueMapRoutePolicy.DisplayRoute inherited = policy("{\"groups\":[{\"id\":\"1\",\"name\":\"Named group\",\"routeIds\":[\"1\"]}]}").world("world-a").route(1);
		assertEquals("group:1", inherited.id); // A group never collides with a numeric source route ID.
		assertEquals("#112233", inherited.color);
	}

	@Test
	public void privateCatalogChangesDoNotEnterThePublishedFingerprint() {
		final String configuration = "{\"schema\":1,\"worlds\":{\"world-a\":{\"routes\":[\"1\"]}}}";
		final Map<String, JsonObject> catalogs = catalogs();
		final String before = BlueMapRoutePolicy.parse(configuration, catalogs).fingerprint();
		catalogs.get("world-a").getAsJsonArray("routes").get(1).getAsJsonObject().addProperty("name", "New private name");
		assertEquals(before, BlueMapRoutePolicy.parse(configuration, catalogs).fingerprint());
		assertFalse(before.contains("Private branch"));
		assertFalse(before.contains(MAX_UNSIGNED));
	}

	@Test
	public void duplicateJsonKeysAndMalformedDocumentsFailClosed() {
		for (String document : List.of(
				"{\"schema\":1,\"worlds\":{},\"worlds\":{\"world-a\":{\"routes\":[\"1\"]}}}",
				"{\"schema\":1,\"worlds\":{\"world-a\":{},\"world-a\":{\"routes\":[\"1\"]}}}",
				"{\"schema\":1,\"worlds\":{\"world-a\":{\"routes\":[],\"routes\":[\"1\"]}}}",
				"{\"schema\":1,\"worlds\":{}} {}", "{schema:1,worlds:{}}", "{\"schema\":1,\"worlds\":{},}",
				"null", "[]", "{", "{\"schema\":1,\"worlds\":null}", "{\"schema\":1}",
				"{\"schema\":\"1\",\"worlds\":{}}", "{\"schema\":true,\"worlds\":{}}",
				"{\"schema\":2,\"worlds\":{}}", "{\"schema\":1,\"worlds\":{},\"routes\":[\"1\"]}")) {
			assertThrows(IllegalArgumentException.class, () -> BlueMapRoutePolicy.parse(document, catalogs()), document);
		}
	}

	@Test
	public void unknownWorldsRoutesAndConfigurationKeysAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> BlueMapRoutePolicy.parse(
				"{\"schema\":1,\"worlds\":{\"unknown-world\":{\"routes\":[\"1\"]}}}", catalogs()));
		for (String configuration : List.of(
				"{\"routes\":[\"999\"]}", "{\"routs\":[\"1\"]}", "{\"routes\":\"1\"}",
				"{\"routes\":null}", "{\"groups\":null}",
				"{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro\",\"routeIds\":[\"999\"]}]}",
				"{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro\",\"routeIds\":[\"1\"],\"routes\":[\"2\"]}]}")) {
			assertThrows(IllegalArgumentException.class, () -> policy(configuration), configuration);
		}
	}

	@Test
	public void routeIdsMustBeCanonicalUnsignedStringsAndNotRepeat() {
		for (String id : List.of("1", "null", "true", "\"0\"", "\"-1\"", "\"01\"", "\"1.0\"", "\"1e0\"", "\"abc\"", "\"18446744073709551616\"")) {
			assertThrows(IllegalArgumentException.class, () -> policy("{\"routes\":[" + id + "]}"), id);
		}
		for (String configuration : List.of(
				"{\"routes\":[\"1\",\"1\"]}",
				"{\"routes\":[\"1\"],\"groups\":[{\"id\":\"metro\",\"name\":\"Metro\",\"routeIds\":[\"1\"]}]}",
				"{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro\",\"routeIds\":[\"1\",\"1\"]}]}",
				"{\"groups\":[{\"id\":\"a\",\"name\":\"A\",\"routeIds\":[\"1\"]},{\"id\":\"b\",\"name\":\"B\",\"routeIds\":[\"1\"]}]}")) {
			assertThrows(IllegalArgumentException.class, () -> policy(configuration), configuration);
		}
	}

	@Test
	public void groupMetadataCannotIntroduceAmbiguousOrInvalidSelections() {
		for (String group : List.of(
				"{\"id\":\"UPPER\",\"name\":\"Metro\",\"routeIds\":[\"1\"]}",
				"{\"id\":\"\",\"name\":\"Metro\",\"routeIds\":[\"1\"]}",
				"{\"id\":1,\"name\":\"Metro\",\"routeIds\":[\"1\"]}",
				"{\"id\":\"metro\",\"name\":\"  \",\"routeIds\":[\"1\"]}",
				"{\"id\":\"metro\",\"name\":\"" + "x".repeat(257) + "\",\"routeIds\":[\"1\"]}",
				"{\"id\":\"metro\",\"name\":\"Metro\",\"routeIds\":[]}",
				"{\"id\":\"metro\",\"name\":\"Metro\",\"color\":\"red\",\"routeIds\":[\"1\"]}",
				"{\"id\":\"metro\",\"name\":\"Metro\",\"color\":null,\"routeIds\":[\"1\"]}",
				"{\"id\":\"metro\",\"name\":\"Metro\",\"routeIds\":[\"1\"],\"id\":\"different\"}")) {
			assertThrows(IllegalArgumentException.class, () -> policy("{\"groups\":[" + group + "]}"), group);
		}
		assertThrows(IllegalArgumentException.class, () -> policy("{\"groups\":["
				+ "{\"id\":\"metro\",\"name\":\"A\",\"routeIds\":[\"1\"]},"
				+ "{\"id\":\"metro\",\"name\":\"B\",\"routeIds\":[\"2\"]}]}"));
	}

	private static BlueMapRoutePolicy policy(String world) {
		return BlueMapRoutePolicy.parse("{\"schema\":1,\"worlds\":{\"world-a\":" + world + "}}", catalogs());
	}

	private static Map<String, JsonObject> catalogs() {
		return Map.of("world-a", catalog(), "world-b", catalog());
	}

	private static JsonObject catalog() {
		final JsonArray routes = new JsonArray();
		for (String[] values : List.of(new String[]{"1", "Public service", "#112233", "false"},
				new String[]{"2", "Private branch", "#445566", "true"},
				new String[]{MAX_UNSIGNED, "Unsigned service", "#778899", "false"})) {
			final JsonObject route = new JsonObject();
			route.addProperty("id", values[0]);
			route.addProperty("name", values[1]);
			route.addProperty("color", values[2]);
			route.addProperty("hidden", Boolean.parseBoolean(values[3]));
			routes.add(route);
		}
		final JsonObject catalog = new JsonObject();
		catalog.add("routes", routes);
		return catalog;
	}
}
