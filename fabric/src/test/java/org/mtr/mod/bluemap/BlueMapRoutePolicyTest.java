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
	public void serviceNamesAreOptionalAndAllowPartialUnsignedMemberOverrides() {
		final BlueMapRoutePolicy original = policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"routeIds\":[\"1\",\"2\"]}]}");
		final BlueMapRoutePolicy emptyNames = policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"routeIds\":[\"1\",\"2\"],\"routeNames\":{}}]}");
		assertEquals(original.fingerprint(), emptyNames.fingerprint());
		assertEquals("Current MTR name", original.world("world-a").route(1).serviceName(1, "Current MTR name"));
		assertEquals("Current MTR name", emptyNames.world("world-a").route(1).serviceName(1, "Current MTR name"));
		assertEquals("Standalone name", policy("{\"routes\":[\"1\"]}").world("world-a").route(1).serviceName(1, "Standalone name"));
		final BlueMapRoutePolicy overrides = policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"routeIds\":[\"1\",\"2\",\"" + MAX_UNSIGNED + "\"],\"routeNames\":{\"1\":\"일반\",\"" + MAX_UNSIGNED + "\":\"특급\"}}]}");
		final BlueMapRoutePolicy.DisplayRoute group = overrides.world("world-a").route(1);
		assertEquals("일반", group.serviceName(1, "Original local"));
		assertEquals("Original express", group.serviceName(2, "Original express"));
		assertEquals("특급", group.serviceName(-1L, "Original limited express"));
		assertEquals("Metro line", group.name);
		assertEquals("group:metro", group.id);
		assertEquals("#112233", group.color);
	}

	@Test
	public void changingOnlyAServiceNameChangesThePublicationFingerprint() {
		final String first = "{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"routeIds\":[\"1\",\"2\"],\"routeNames\":{\"1\":\"일반\",\"2\":\"급행\"}}]}";
		final BlueMapRoutePolicy before = policy(first);
		final BlueMapRoutePolicy after = policy(first.replace("급행", "쾌속"));
		assertNotEquals(before.fingerprint(), after.fingerprint());
		assertEquals(before.world("world-a").route(1).id, after.world("world-a").route(1).id);
		assertEquals(before.world("world-a").route(1).name, after.world("world-a").route(1).name);
		assertEquals(before.world("world-a").route(1).color, after.world("world-a").route(1).color);
		assertEquals("일반", after.world("world-a").route(1).serviceName(1, "Original local"));
		assertEquals("쾌속", after.world("world-a").route(2).serviceName(2, "Original express"));
		assertEquals(before.fingerprint(), policy(first.replace("\"1\":\"일반\",\"2\":\"급행\"", "\"2\":\"급행\",\"1\":\"일반\"")).fingerprint());
	}

	@Test
	public void serviceNameOverridesMustBeMemberNamesAndNonblankStrings() {
		for (String names : List.of(
				"null", "[]", "\"일반\"", "123", "{\"2\":\"Not a group member\"}",
				"{\"999\":\"Unknown route\"}", "{\"01\":\"Noncanonical ID\"}", "{\"invalid\":\"Invalid ID\"}",
				"{\"1\":null}", "{\"1\":42}", "{\"1\":true}", "{\"1\":{}}", "{\"1\":[]}",
				"{\"1\":\"\"}", "{\"1\":\"  \\t\\n\"}", "{\"1\":\"" + "x".repeat(257) + "\"}",
				"{\"1\":\"일반\",\"1\":\"급행\"}")) {
			final String configuration = "{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"routeIds\":[\"1\"],\"routeNames\":" + names + "}]}";
			assertThrows(IllegalArgumentException.class, () -> policy(configuration), names);
		}
		final String maximumLengthName = "x".repeat(256);
		final BlueMapRoutePolicy valid = policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"routeIds\":[\"1\"],\"routeNames\":{\"1\":\"" + maximumLengthName + "\"}}]}");
		assertEquals(maximumLengthName, valid.world("world-a").route(1).serviceName(1, "Fallback"));
	}

	@Test
	public void explicitServicesMergeSourcesAndCanCoexistWithDisjointLegacyMembers() {
		final BlueMapRoutePolicy selected = policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"services\":[{\"id\":\"rapid\",\"name\":\"쾌속\",\"showName\":false,\"routeIds\":[\"1\",\"2\"]}]}]}");
		final var first = selected.world("world-a").route(1);
		final var second = selected.world("world-a").route(2);
		assertSame(first, second);
		assertEquals("#112233", first.color);
		assertEquals("service:metro:rapid", first.service(1, "Original local").id);
		assertEquals(first.service(1, "Original local").id, second.service(2, "Original express").id);
		assertEquals("쾌속", second.serviceName(2, "Original express"));
		assertFalse(second.service(2, "Original express").showName);
		assertNull(selected.world("world-a").route(-1L));
		final BlueMapRoutePolicy mixed = policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"routeIds\":[\"1\"],\"routeNames\":{\"1\":\"일반\"},\"services\":[{\"id\":\"rapid\",\"name\":\"쾌속\",\"showName\":true,\"routeIds\":[\"2\",\"" + MAX_UNSIGNED + "\"]}]}]}");
		assertEquals("1", mixed.world("world-a").route(1).service(1, "Original").id);
		assertEquals("일반", mixed.world("world-a").route(1).serviceName(1, "Original"));
		assertEquals("service:metro:rapid", mixed.world("world-a").route(-1L).service(-1L, "Original").id);
		assertEquals("#112233", mixed.world("world-a").route(-1L).color);
	}

	@Test
	public void explicitServiceNameAndVisibilityChangesRefreshTheFingerprint() {
		final String configuration = "{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro line\",\"services\":[{\"id\":\"rapid\",\"name\":\"쾌속\",\"showName\":true,\"routeIds\":[\"1\",\"2\"]}]}]}";
		final String original = policy(configuration).fingerprint();
		assertNotEquals(original, policy(configuration.replace("쾌속", "급행")).fingerprint());
		assertNotEquals(original, policy(configuration.replace("true", "false")).fingerprint());
		assertNotEquals(original, policy(configuration.replace("\"rapid\"", "\"express\"")).fingerprint());
	}

	@Test
	public void explicitServicesRejectConflictingMembersAndInvalidMetadata() {
		for (String service : List.of(
				"{}", "{\"id\":\"rapid\",\"routeIds\":[\"1\"]}", "{\"name\":\"쾌속\",\"routeIds\":[\"1\"]}",
				"{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[]}",
				"{\"id\":\"rapid\",\"name\":\" \",\"routeIds\":[\"1\"]}",
				"{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\"],\"showName\":\"false\"}",
				"{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\"],\"showName\":null}",
				"{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\"],\"extra\":true}",
				"{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\",\"1\"]}")) {
			assertThrows(IllegalArgumentException.class, () -> policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro\",\"services\":[" + service + "]}]}"), service);
		}
		for (String contents : List.of(
				"\"services\":[]",
				"\"routeIds\":[\"1\"],\"services\":[{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\"]}]",
				"\"services\":[{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\"]},{\"id\":\"express\",\"name\":\"급행\",\"routeIds\":[\"1\"]}]",
				"\"services\":[{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\"]},{\"id\":\"rapid\",\"name\":\"급행\",\"routeIds\":[\"2\"]}]",
				"\"services\":[{\"id\":\"rapid\",\"name\":\"쾌속\",\"routeIds\":[\"1\"]}],\"routeNames\":{\"1\":\"Not a legacy member\"}")) {
			assertThrows(IllegalArgumentException.class, () -> policy("{\"groups\":[{\"id\":\"metro\",\"name\":\"Metro\"," + contents + "}]}"), contents);
		}
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
