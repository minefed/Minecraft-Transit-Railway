package org.mtr.mod.bluemap;

import org.mtr.core.Main;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Geometry-only bridge. Call capture methods on the owning simulator's run queue. */
final class BlueMapSnapshot {

	// The pinned Core does not expose all simulators or unattended vehicles through its public API.
	// Keep this small, checked adapter separate from the client-only resource/rendering classes.
	private final Field simulators = field(Main.class, "simulators");
	private final Field vehicles = field(Siding.class, "vehicles");

	List<Simulator> simulators(Main main) throws IllegalAccessException {
		final List<Simulator> result = new ArrayList<>();
		for (Object value : (Iterable<?>) simulators.get(main)) {
			result.add((Simulator) value);
		}
		return result;
	}

	JsonObject vehicles(Data simulator, long timestamp) throws IllegalAccessException {
		final JsonArray result = new JsonArray();
		for (Siding siding : simulator.sidings) {
			for (Object value : (Iterable<?>) vehicles.get(siding)) {
				final Vehicle vehicle = (Vehicle) value;
				final JsonArray cars = new JsonArray();
				for (ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>>> car : vehicle.getVehicleCarsAndPositions()) {
					final JsonObject pose = car(car.left(), car.right(), vehicle.getTransportMode().hasPitchAscending || vehicle.getTransportMode().hasPitchDescending);
					if (pose != null) {
						cars.add(pose);
					}
				}
				if (!cars.isEmpty()) {
					final JsonObject entry = new JsonObject();
					entry.addProperty("id", Long.toUnsignedString(vehicle.getId()));
					addRoute(entry, vehicle.vehicleExtraData);
					entry.add("cars", cars);
					result.add(entry);
				}
			}
		}
		final JsonObject response = empty("vehicles", timestamp);
		response.add("vehicles", result);
		return response;
	}

	static void addRoute(JsonObject entry, VehicleExtraData data) {
		final boolean current = data.getThisRouteId() != 0;
		final long routeId = current ? data.getThisRouteId() : data.getNextRouteId();
		if (routeId != 0) {
			entry.addProperty("routeId", Long.toUnsignedString(routeId));
			entry.addProperty("routeName", current ? data.getThisRouteName() : data.getNextRouteName());
			entry.addProperty("color", color(current ? data.getThisRouteColor() : data.getNextRouteColor()));
		} else {
			entry.addProperty("color", "#8b949e");
		}
	}

	static JsonObject infrastructure(Data simulator, long timestamp) {
		final JsonObject result = rails(simulator.rails, timestamp);
		final JsonArray routes = new JsonArray();
		final Map<Long, Set<String>> stationRoutes = new LinkedHashMap<>();
		for (Route route : simulator.routes) {
			if (route.getHidden()) {
				continue;
			}
			final String routeId = Long.toUnsignedString(route.getId());
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", routeId);
			entry.addProperty("name", route.getName());
			entry.addProperty("color", color(route.getColor()));
			entry.addProperty("geometry", "rail-path");
			final JsonArray routeStations = new JsonArray();
			final Set<Long> seenStations = new LinkedHashSet<>();
			final List<Platform> platforms = new ArrayList<>();
			for (RoutePlatformData platformData : route.getRoutePlatforms()) {
				final Platform platform = platformData.getPlatform();
				platforms.add(platform); // Preserve missing stops: never connect across one.
				if (platform != null && platform.area != null) {
					final long stationId = platform.area.getId();
					stationRoutes.computeIfAbsent(stationId, ignored -> new LinkedHashSet<>()).add(routeId);
					if (seenStations.add(stationId)) {
						routeStations.add(Long.toUnsignedString(stationId));
					}
				}
			}
			final JsonArray paths = new JsonArray();
			int missing = 0;
			for (int i = 1; i < platforms.size(); i++) {
				final Platform from = platforms.get(i - 1);
				final Platform to = platforms.get(i);
				boolean found = false;
				final Set<String> seenPaths = new LinkedHashSet<>();
				if (from != null && to != null) {
					for (Depot depot : route.depots) {
						final JsonArray path = routeLeg(depot.getPath(), from.getId(), to.getId());
						if (path.size() > 1) {
							found = true;
							if (seenPaths.add(path.toString())) {
								paths.add(path);
							}
						}
					}
				}
				if (!found) {
					missing++;
				}
			}
			entry.add("paths", paths);
			entry.add("stations", routeStations);
			entry.addProperty("missingLegs", missing);
			routes.add(entry);
		}
		final JsonArray stations = new JsonArray();
		for (Station station : simulator.stations) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", Long.toUnsignedString(station.getId()));
			entry.addProperty("name", station.getName());
			Vector center = new Vector(0, 0, 0);
			for (Platform platform : station.savedRails) {
				final Position position = platform.getMidPosition();
				center = center.add(position.getX(), position.getY(), position.getZ());
			}
			if (station.savedRails.isEmpty()) {
				final Position position = station.getCenter();
				if (position == null) {
					continue;
				}
				center = new Vector(position.getX(), position.getY(), position.getZ());
			} else {
				final double scale = 1.0 / station.savedRails.size();
				center = center.multiply(scale, scale, scale);
			}
			entry.add("position", point(center));
			final JsonArray servedRoutes = new JsonArray();
			stationRoutes.getOrDefault(station.getId(), java.util.Collections.emptySet()).forEach(servedRoutes::add);
			entry.add("routes", servedRoutes);
			stations.add(entry);
		}
		result.add("routes", routes);
		result.add("stations", stations);
		return result;
	}

	/** Match a generated, directed depot path between successive route stops. Never guess straight lines. */
	static JsonArray routeLeg(List<PathData> path, long from, long to) {
		for (int start = 0; start < path.size(); start++) {
			if (path.get(start).getSavedRailBaseId() != from) {
				continue;
			}
			for (int end = start + 1; end < path.size(); end++) {
				if (path.get(end).getSavedRailBaseId() == to) {
					final JsonArray points = new JsonArray();
					for (int index = start; index <= end; index++) {
						final PathData segment = path.get(index);
						if (segment.getRail() == null || !segment.getRail().isValid()) {
							return new JsonArray();
						}
						final double length = segment.getRailLength();
						final double first = index == start ? length / 2 : 0;
						final double last = index == end ? length / 2 : length;
						final int count = Math.max(1, (int) Math.ceil(last - first));
						for (int step = index == start ? 0 : 1; step <= count; step++) {
							final Vector position = segment.getPosition(first + (last - first) * step / count);
							if (!finite(position)) {
								return new JsonArray();
							}
							points.add(point(position));
						}
					}
					return points;
				} else if (path.get(end).getSavedRailBaseId() != 0) {
					break; // This depot follows another sequence of stops.
				}
			}
		}
		return new JsonArray();
	}

	private static String color(int color) {
		return String.format(Locale.ROOT, "#%06x", color & 0xffffff);
	}

	static JsonObject car(VehicleCar car, List<ObjectObjectImmutablePair<Vector, Vector>> bogies, boolean hasPitch) {
		if (bogies.size() < 1 || bogies.size() > 2) {
			return null;
		}
		final Vector first;
		final Vector second;
		if (bogies.size() == 1) {
			first = bogies.get(0).left();
			second = bogies.get(0).right();
		} else {
			first = Vector.getAverage(bogies.get(0).left(), bogies.get(0).right());
			second = Vector.getAverage(bogies.get(1).left(), bogies.get(1).right());
		}
		final Vector midpoint = Vector.getAverage(first, second);
		final double dx = second.x - first.x;
		final double dy = second.y - first.y;
		final double dz = second.z - first.z;
		final double yaw = Math.atan2(dx, dz);
		final double mtrPitch = hasPitch ? Math.atan2(dy, Math.hypot(dx, dz)) : 0;
		final double pivot = (car.getBogie1Position() + car.getBogie2Position()) / 2;
		// Matches PositionAndRotation, without loading MinecraftClient or sampling lighting.
		final Vector direction = new Vector(0, 0, 1).rotateX(mtrPitch).rotateY(yaw);
		final Vector center = midpoint.add(direction.multiply(-pivot, -pivot, -pivot));
		if (!finite(center) || !Double.isFinite(car.getLength()) || car.getLength() <= 0 || !Double.isFinite(car.getWidth()) || car.getWidth() <= 0) {
			return null;
		}
		final JsonObject result = new JsonObject();
		result.addProperty("id", car.getVehicleId());
		result.add("position", point(center));
		result.addProperty("yaw", yaw);
		// Core's rotateX uses the opposite sign to Three.js Euler X. +Z is the model axis.
		result.addProperty("pitch", -mtrPitch);
		result.addProperty("length", car.getLength());
		result.addProperty("width", car.getWidth());
		return result;
	}

	static JsonObject rails(Iterable<Rail> rails, long timestamp) {
		final JsonArray result = new JsonArray();
		for (Rail rail : rails) {
			// An empty style list is MTR's intentionally invisible track.
			if (rail.getTransportMode() != TransportMode.TRAIN || rail.getStyles().isEmpty() || !rail.isValid()) {
				continue;
			}
			final JsonArray points = new JsonArray();
			final double length = rail.railMath.getLength();
			if (!Double.isFinite(length) || length <= 0) {
				continue;
			}
			final int segments = (int) Math.ceil(length);
			for (int i = 0; i <= segments; i++) {
				final Vector position = rail.railMath.getPosition(length * i / segments, false);
				if (finite(position)) {
					points.add(point(position));
				}
			}
			if (points.size() >= 2) {
				final JsonObject entry = new JsonObject();
				entry.addProperty("id", rail.getHexId());
				entry.add("points", points);
				result.add(entry);
			}
		}
		final JsonObject response = empty("rails", timestamp);
		response.add("rails", result);
		return response;
	}

	static JsonObject empty(String key, long timestamp) {
		final JsonObject result = new JsonObject();
		result.addProperty("schema", 1);
		result.addProperty("timestamp", timestamp);
		result.add(key, new JsonArray());
		return result;
	}

	private static JsonArray point(Vector value) {
		final JsonArray result = new JsonArray();
		result.add(value.x);
		result.add(value.y);
		result.add(value.z);
		return result;
	}

	private static boolean finite(Vector value) {
		return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
	}

	private static Field field(Class<?> type, String name) {
		try {
			final Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Incompatible Transport Simulation Core: " + type.getSimpleName() + "." + name, e);
		}
	}
}
