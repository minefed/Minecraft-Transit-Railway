package org.mtr.mod.bluemap;

import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.com.google.gson.stream.JsonReader;
import org.mtr.libraries.com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/** Strict, immutable opt-in publication policy. No configuration ever means publish nothing. */
final class BlueMapRoutePolicy {

	static final String INITIAL = "{\"schema\":1,\"worlds\":{}}\n";
	static final BlueMapRoutePolicy CLOSED = new BlueMapRoutePolicy(Collections.emptyMap());
	private final Map<String, WorldSelection> worlds;

	private BlueMapRoutePolicy(Map<String, WorldSelection> worlds) {
		this.worlds = Collections.unmodifiableMap(new LinkedHashMap<>(worlds));
	}

	WorldSelection world(String worldId) {
		return worlds.getOrDefault(worldId, WorldSelection.EMPTY);
	}

	/** Catalogs stay private; only selected route metadata is retained by the bound policy. */
	static BlueMapRoutePolicy parse(String content, Map<String, JsonObject> catalogs) {
		final JsonObject root = object(parseStrict(content), "policy");
		keys(root, "schema", "worlds");
		if (!root.has("schema") || !root.get("schema").isJsonPrimitive() || !root.get("schema").getAsJsonPrimitive().isNumber() || !root.get("schema").getAsString().equals("1")) {
			throw invalid("schema must be 1");
		}
		final JsonObject configuredWorlds = object(root.get("worlds"), "worlds");
		final Map<String, WorldSelection> worlds = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> worldEntry : configuredWorlds.entrySet()) {
			final String worldId = worldEntry.getKey();
			final JsonObject catalog = catalogs.get(worldId);
			if (catalog == null) {
				throw invalid("unknown world: " + worldId);
			}
			final Map<String, JsonObject> available = new LinkedHashMap<>();
			for (JsonElement element : catalog.getAsJsonArray("routes")) {
				final JsonObject route = element.getAsJsonObject();
				available.put(route.get("id").getAsString(), route);
			}
			final JsonObject configuration = object(worldEntry.getValue(), worldId);
			keys(configuration, "routes", "groups");
			final Map<String, DisplayRoute> routes = new LinkedHashMap<>();
			for (String routeId : ids(configuration, "routes", false)) {
				final JsonObject source = requireRoute(available, routeId, worldId);
				put(routes, routeId, new DisplayRoute(routeId, source.get("name").getAsString(), source.get("color").getAsString(), false, Collections.emptyMap(), Collections.emptyMap()));
			}
			final Set<String> groups = new HashSet<>();
			for (JsonElement element : array(configuration, "groups", false)) {
				final JsonObject group = object(element, "group");
				keys(group, "id", "name", "color", "routeIds", "routeNames", "services");
				final String id = string(group.get("id"), "group.id");
				if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}") || !groups.add(id)) {
					throw invalid("invalid or duplicate group id: " + id);
				}
				final String name = string(group.get("name"), "group.name");
				if (name.trim().isEmpty() || name.length() > 256) {
					throw invalid("group name must contain 1-256 characters");
				}
				final List<String> members = ids(group, "routeIds", false);
				final Map<String, String> routeNames = new LinkedHashMap<>();
				if (group.has("routeNames")) {
					for (Map.Entry<String, JsonElement> entry : object(group.get("routeNames"), "group.routeNames").entrySet()) {
						if (!members.contains(entry.getKey())) {
							throw invalid("routeNames key must be a member of routeIds: " + entry.getKey());
						}
						final String routeName = string(entry.getValue(), "group.routeNames." + entry.getKey());
						if (routeName.trim().isEmpty() || routeName.length() > 256) {
							throw invalid("routeNames value must contain 1-256 characters: " + entry.getKey());
						}
						routeNames.put(entry.getKey(), routeName);
					}
				}
				final Map<String, Service> services = new LinkedHashMap<>();
				final Set<String> serviceIds = new HashSet<>();
				for (JsonElement serviceElement : array(group, "services", false)) {
					final JsonObject service = object(serviceElement, "service");
					keys(service, "id", "name", "routeIds", "showName");
					final String serviceId = string(service.get("id"), "service.id");
					if (!serviceId.matches("[a-z0-9][a-z0-9_-]{0,63}") || !serviceIds.add(serviceId)) {
						throw invalid("invalid or duplicate service id: " + serviceId);
					}
					final String serviceName = string(service.get("name"), "service.name");
					if (serviceName.trim().isEmpty() || serviceName.length() > 256) {
						throw invalid("service name must contain 1-256 characters");
					}
					boolean showName = true;
					if (service.has("showName")) {
						final JsonElement flag = service.get("showName");
						if (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) {
							throw invalid("service.showName must be a boolean");
						}
						showName = flag.getAsBoolean();
					}
					final List<String> serviceMembers = ids(service, "routeIds", true);
					if (serviceMembers.isEmpty()) {
						throw invalid("service must have routeIds: " + serviceId);
					}
					final Service displayService = new Service("service:" + id + ":" + serviceId, serviceName, showName);
					for (String member : serviceMembers) {
						if (members.contains(member)) {
							throw invalid("route occurs more than once in routeIds/services: " + member);
						}
						members.add(member);
						services.put(member, displayService);
					}
				}
				if (members.isEmpty()) {
					throw invalid("group must have routeIds or services: " + id);
				}
				final String color = group.has("color") ? string(group.get("color"), "group.color") : requireRoute(available, members.get(0), worldId).get("color").getAsString();
				if (!color.matches("#[0-9a-fA-F]{6}")) {
					throw invalid("group color must be #rrggbb: " + id);
				}
				final DisplayRoute display = new DisplayRoute("group:" + id, name, color.toLowerCase(Locale.ROOT), true, routeNames, services);
				for (String member : members) {
					requireRoute(available, member, worldId);
					put(routes, member, display);
				}
			}
			worlds.put(worldId, new WorldSelection(routes));
		}
		return new BlueMapRoutePolicy(worlds);
	}

	String fingerprint() {
		final JsonObject result = new JsonObject();
		worlds.forEach((worldId, selection) -> {
			final JsonObject routes = new JsonObject();
			selection.routes.forEach((sourceId, display) -> {
				final JsonArray values = new JsonArray();
				values.add(display.id);
				values.add(display.name);
				values.add(display.color);
				final Service service = display.service(Long.parseUnsignedLong(sourceId), "");
				values.add(service.id);
				values.add(service.name);
				values.add(service.showName);
				routes.add(sourceId, values);
			});
			result.add(worldId, routes);
		});
		return result.toString();
	}

	private static void put(Map<String, DisplayRoute> routes, String id, DisplayRoute display) {
		if (routes.putIfAbsent(id, display) != null) {
			throw invalid("route occurs more than once in routes/groups: " + id);
		}
	}

	private static JsonObject requireRoute(Map<String, JsonObject> routes, String id, String worldId) {
		final JsonObject route = routes.get(id);
		if (route == null) {
			throw invalid("unknown route " + id + " in " + worldId);
		}
		return route;
	}

	private static List<String> ids(JsonObject object, String key, boolean required) {
		final List<String> result = new ArrayList<>();
		for (JsonElement value : array(object, key, required)) {
			final String id = string(value, key);
			try {
				if (!id.matches("[1-9][0-9]{0,19}") || !Long.toUnsignedString(Long.parseUnsignedLong(id)).equals(id)) {
					throw invalid("route id must be a nonzero unsigned decimal string: " + id);
				}
			} catch (NumberFormatException e) {
				throw invalid("route id is outside unsigned 64-bit range: " + id);
			}
			result.add(id);
		}
		return result;
	}

	private static JsonArray array(JsonObject object, String key, boolean required) {
		if (!object.has(key) && !required) {
			return new JsonArray();
		}
		if (!object.has(key) || !object.get(key).isJsonArray()) {
			throw invalid(key + " must be an array");
		}
		return object.getAsJsonArray(key);
	}

	private static JsonObject object(JsonElement value, String label) {
		if (value == null || !value.isJsonObject()) {
			throw invalid(label + " must be an object");
		}
		return value.getAsJsonObject();
	}

	private static String string(JsonElement value, String label) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw invalid(label + " must be a string");
		}
		return value.getAsString();
	}

	private static void keys(JsonObject object, String... allowed) {
		final Set<String> keys = new HashSet<>(Arrays.asList(allowed));
		for (String key : object.keySet()) {
			if (!keys.contains(key)) {
				throw invalid("unknown setting: " + key);
			}
		}
	}

	/** Gson's tree parser silently overwrites duplicate keys, which is unsafe for allowlists. */
	private static JsonElement parseStrict(String value) {
		try (JsonReader reader = new JsonReader(new StringReader(value))) {
			reader.setLenient(false);
			final JsonElement result = read(reader, 0);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw invalid("trailing JSON content");
			}
			return result;
		} catch (IOException | IllegalStateException e) {
			throw invalid("invalid JSON: " + e.getMessage());
		}
	}

	private static JsonElement read(JsonReader reader, int depth) throws IOException {
		if (depth > 16) {
			throw invalid("JSON nesting is too deep");
		}
		switch (reader.peek()) {
			case BEGIN_OBJECT:
				reader.beginObject();
				final JsonObject object = new JsonObject();
				while (reader.hasNext()) {
					final String key = reader.nextName();
					if (object.has(key)) {
						throw invalid("duplicate JSON key: " + key);
					}
					object.add(key, read(reader, depth + 1));
				}
				reader.endObject();
				return object;
			case BEGIN_ARRAY:
				reader.beginArray();
				final JsonArray array = new JsonArray();
				while (reader.hasNext()) {
					array.add(read(reader, depth + 1));
				}
				reader.endArray();
				return array;
			case STRING:
				return new JsonPrimitive(reader.nextString());
			case NUMBER:
				return new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
			case BOOLEAN:
				return new JsonPrimitive(reader.nextBoolean());
			case NULL:
				reader.nextNull();
				return JsonNull.INSTANCE;
			default:
				throw invalid("unexpected JSON token: " + reader.peek());
		}
	}

	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException("Invalid BlueMap public routes: " + message);
	}

	static final class WorldSelection {
		static final WorldSelection EMPTY = new WorldSelection(Collections.emptyMap());
		private final Map<String, DisplayRoute> routes;

		private WorldSelection(Map<String, DisplayRoute> routes) {
			this.routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
		}

		DisplayRoute route(long id) {
			return routes.get(Long.toUnsignedString(id));
		}
	}

	static final class DisplayRoute {
		final String id;
		final String name;
		final String color;
		final boolean grouped;
		private final Map<String, String> routeNames;
		private final Map<String, Service> services;

		private DisplayRoute(String id, String name, String color, boolean grouped, Map<String, String> routeNames, Map<String, Service> services) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.grouped = grouped;
			this.routeNames = Collections.unmodifiableMap(new LinkedHashMap<>(routeNames));
			this.services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
		}

		String serviceName(long sourceId, String fallback) {
			return service(sourceId, fallback).name;
		}

		Service service(long sourceId, String fallback) {
			final String key = Long.toUnsignedString(sourceId);
			final Service service = services.get(key);
			return service == null ? new Service(key, routeNames.getOrDefault(key, fallback), true) : service;
		}
	}

	static final class Service {
		final String id;
		final String name;
		final boolean showName;

		private Service(String id, String name, boolean showName) {
			this.id = id;
			this.name = name;
			this.showName = showName;
		}
	}
}
