package org.mtr.mod.bluemap;

import org.mtr.core.Main;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.operation.ArrivalsRequest;
import org.mtr.core.operation.ArrivalsResponse;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongImmutableList;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
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

	JsonObject vehicles(Data simulator, long timestamp, BlueMapRoutePolicy.WorldSelection selection) throws IllegalAccessException {
		final JsonArray result = new JsonArray();
		for (Siding siding : simulator.sidings) {
			for (Object value : (Iterable<?>) vehicles.get(siding)) {
				final Vehicle vehicle = (Vehicle) value;
				// Core keeps parked depot vehicles at their default position; station dwells remain on route.
				if (!vehicle.getIsOnRoute()) {
					continue;
				}
				final long sourceId = vehicle.vehicleExtraData.getThisRouteId() != 0 ? vehicle.vehicleExtraData.getThisRouteId() : vehicle.vehicleExtraData.getNextRouteId();
				final BlueMapRoutePolicy.DisplayRoute display = selection.route(sourceId);
				if (display == null) {
					continue;
				}
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
					if (display.grouped) {
						final BlueMapRoutePolicy.Service service = display.service(sourceId, entry.get("routeName").getAsString());
						entry.addProperty("sourceRouteId", Long.toUnsignedString(sourceId));
						entry.addProperty("serviceId", service.id);
						entry.addProperty("serviceName", service.name);
						entry.addProperty("showServiceName", service.showName);
					}
					entry.addProperty("routeId", display.id);
					entry.addProperty("routeName", display.name);
					entry.addProperty("color", display.color);
					addOperator(entry, display.operator(sourceId));
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

	static JsonObject routeCatalog(Data simulator, String worldId) {
		final JsonObject result = new JsonObject();
		result.addProperty("worldId", worldId);
		final JsonArray routes = new JsonArray();
		final Map<Long, Set<String>> stationRoutes = new LinkedHashMap<>();
		for (Route route : simulator.routes) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", Long.toUnsignedString(route.getId()));
			entry.addProperty("name", route.getName());
			entry.addProperty("color", color(route.getColor()));
			entry.addProperty("hidden", route.getHidden());
			entry.addProperty("number", route.getRouteNumber());
			entry.addProperty("transportMode", route.getTransportMode().name());
			routes.add(entry);
			for (RoutePlatformData stop : route.getRoutePlatforms()) {
				final Platform platform = stop.getPlatform();
				if (platform != null && platform.area != null) {
					stationRoutes.computeIfAbsent(platform.area.getId(), ignored -> new LinkedHashSet<>()).add(Long.toUnsignedString(route.getId()));
				}
			}
		}
		result.add("routes", routes);
		final JsonArray stations = new JsonArray();
		for (Station station : simulator.stations) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", Long.toUnsignedString(station.getId()));
			entry.addProperty("name", station.getName());
			final JsonArray routeIds = new JsonArray();
			stationRoutes.getOrDefault(station.getId(), java.util.Collections.emptySet()).forEach(routeIds::add);
			entry.add("routeIds", routeIds);
			stations.add(entry);
		}
		result.add("stations", stations);
		return result;
	}

	static JsonObject infrastructure(Data simulator, long timestamp, BlueMapRoutePolicy.WorldSelection selection) {
		return infrastructure(simulator, timestamp, selection, new RailGeometryCache());
	}

	static JsonObject infrastructure(Data simulator, long timestamp, BlueMapRoutePolicy.WorldSelection selection, RailGeometryCache geometryCache) {
		geometryCache.beginCapture();
		try {
			return captureInfrastructure(simulator, timestamp, selection, geometryCache);
		} finally {
			geometryCache.endCapture();
		}
	}

	private static JsonObject captureInfrastructure(Data simulator, long timestamp, BlueMapRoutePolicy.WorldSelection selection, RailGeometryCache geometryCache) {
		final Map<Depot, DepotPathIndex> depotPaths = new IdentityHashMap<>();
		final Map<String, RouteGeometry> displayedRoutes = new LinkedHashMap<>();
		final Map<String, Rail> selectedRails = new LinkedHashMap<>();
		final Map<Long, Set<String>> stationRoutes = new LinkedHashMap<>();
		final Map<Long, Map<Long, Platform>> stationPlatforms = new LinkedHashMap<>();
		final Map<Long, Map<String, JsonObject>> stationServices = new LinkedHashMap<>();
		for (Route route : simulator.routes) {
			final BlueMapRoutePolicy.DisplayRoute display = selection.route(route.getId());
			if (display == null) {
				continue;
			}
			final RouteGeometry geometry = displayedRoutes.computeIfAbsent(display.id, ignored -> new RouteGeometry(display));
			final JsonObject service = service(route, display);
			final String serviceId = service.get("id").getAsString();
			mergeService(geometry.services, serviceId, service);
			addOperators(geometry.operators, display.operator(route.getId()));
			final List<Platform> platforms = new ArrayList<>();
			for (RoutePlatformData platformData : route.getRoutePlatforms()) {
				final Platform platform = platformData.getPlatform();
				platforms.add(platform); // Preserve missing stops: never connect across one.
				if (platform != null && platform.area != null) {
					final long stationId = platform.area.getId();
					stationRoutes.computeIfAbsent(stationId, ignored -> new LinkedHashSet<>()).add(display.id);
					stationPlatforms.computeIfAbsent(stationId, ignored -> new LinkedHashMap<>()).put(platform.getId(), platform);
					mergeService(stationServices.computeIfAbsent(stationId, ignored -> new LinkedHashMap<>()), serviceId, service);
					geometry.stations.add(Long.toUnsignedString(stationId));
				}
			}
			for (int i = 1; i < platforms.size(); i++) {
				final Platform from = platforms.get(i - 1);
				final Platform to = platforms.get(i);
				boolean found = false;
				if (from != null && to != null) {
					for (Depot depot : route.depots) {
						final List<PathData> leg = depotPaths.computeIfAbsent(depot, value -> new DepotPathIndex(value.getPath())).leg(from.getId(), to.getId());
						if (!leg.isEmpty()) {
							found = true;
							for (int segmentIndex = 0; segmentIndex < leg.size(); segmentIndex++) {
								final PathData segment = leg.get(segmentIndex);
								final Rail rail = segment.getRail();
								selectedRails.put(rail.getHexId(), rail);
								double start = segmentIndex == 0 ? 0.5 : 0;
								double end = segmentIndex == leg.size() - 1 ? 0.5 : 1;
								if (segment.reversePositions) {
									final double previousStart = start;
									start = 1 - end;
									end = 1 - previousStart;
								}
								geometry.add(rail, start, end);
							}
						}
					}
				}
				if (!found) {
					geometry.missingLegs++;
				}
			}
		}
		final JsonObject result = rails(selectedRails.values(), timestamp, geometryCache);
		final JsonArray routes = new JsonArray();
		displayedRoutes.values().forEach(geometry -> routes.add(geometry.json(geometryCache)));
		final JsonArray stations = new JsonArray();
		final Map<String, JsonObject> publicStations = new LinkedHashMap<>();
		for (Station station : simulator.stations) {
			final Map<Long, Platform> servedPlatforms = stationPlatforms.get(station.getId());
			if (servedPlatforms == null || servedPlatforms.isEmpty()) {
				continue;
			}
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", Long.toUnsignedString(station.getId()));
			entry.addProperty("name", station.getName());
			Vector center = new Vector(0, 0, 0);
			for (Platform platform : servedPlatforms.values()) {
				final Position position = platform.getMidPosition();
				center = center.add(position.getX(), position.getY(), position.getZ());
			}
			final double scale = 1.0 / servedPlatforms.size();
			center = center.multiply(scale, scale, scale);
			entry.add("position", point(center));
			final JsonArray servedRoutes = new JsonArray();
			stationRoutes.getOrDefault(station.getId(), java.util.Collections.emptySet()).forEach(servedRoutes::add);
			entry.add("routes", servedRoutes);
			final JsonArray services = new JsonArray();
			stationServices.get(station.getId()).values().forEach(services::add);
			entry.add("services", services);
			stations.add(entry);
			publicStations.put(Long.toUnsignedString(station.getId()), entry);
		}
		// Links only decorate already-published stations; never publish a private member or merge platforms.
		for (BlueMapRoutePolicy.Interchange interchange : selection.interchanges()) {
			final List<String> members = new ArrayList<>();
			for (String stationId : interchange.stationIds) {
				if (publicStations.containsKey(stationId)) members.add(stationId);
			}
			if (members.size() < 2) continue;
			for (String stationId : members) {
				final JsonObject station = publicStations.get(stationId);
				station.addProperty("interchangeId", "interchange:" + interchange.id);
				station.addProperty("interchangeName", interchange.name);
				final JsonArray peers = new JsonArray();
				for (String peer : members) {
					if (!peer.equals(stationId)) peers.add(peer);
				}
				station.add("transferStationIds", peers);
			}
		}
		result.add("routes", routes);
		result.add("stations", stations);
		return result;
	}

	private static JsonObject service(Route route, BlueMapRoutePolicy.DisplayRoute display) {
		final BlueMapRoutePolicy.Service label = display.service(route.getId(), route.getName());
		final JsonObject service = new JsonObject();
		service.addProperty("id", label.id);
		service.addProperty("name", label.name);
		service.addProperty("showName", label.showName);
		service.addProperty("color", color(route.getColor()));
		service.addProperty("routeId", display.id);
		final JsonArray operators = new JsonArray();
		addOperators(operators, display.operator(route.getId()));
		service.add("operators", operators);
		return service;
	}

	private static void addOperator(JsonObject entry, BlueMapRoutePolicy.Operator operator) {
		if (operator != null) {
			entry.addProperty("operatorId", operator.id);
			entry.addProperty("operatorName", operator.name);
		}
	}

	private static void addOperators(JsonArray operators, BlueMapRoutePolicy.Operator operator) {
		if (operator == null) return;
		for (var existing : operators) {
			if (existing.getAsJsonObject().get("id").getAsString().equals(operator.id)) return;
		}
		final JsonObject entry = new JsonObject();
		entry.addProperty("id", operator.id);
		entry.addProperty("name", operator.name);
		operators.add(entry);
	}

	private static void mergeService(Map<String, JsonObject> services, String id, JsonObject service) {
		// Each station owns its operator union: a route serving another station must not contaminate it.
		final JsonObject existing = services.get(id);
		if (existing == null) {
			services.put(id, service.deepCopy());
			return;
		}
		final JsonArray operators = existing.getAsJsonArray("operators");
		for (var candidate : service.getAsJsonArray("operators")) {
			boolean present = false;
			for (var operator : operators) {
				if (operator.getAsJsonObject().get("id").equals(candidate.getAsJsonObject().get("id"))) present = true;
			}
			if (!present) operators.add(candidate.deepCopy());
		}
	}

	static JsonObject arrivals(Simulator simulator, long timestamp, BlueMapRoutePolicy.WorldSelection selection) {
		final Map<Long, Platform> platforms = selectedPlatforms(simulator, selection);
		if (platforms.isEmpty()) {
			return arrivalData(new JsonArray(), timestamp);
		}
		final LongAVLTreeSet ids = new LongAVLTreeSet();
		platforms.keySet().forEach(ids::add);
		// Core limits each siding/platform before we can filter private services. Oversample
		// with a finite cap so a few private departures cannot starve the public board.
		final ArrivalsResponse response = new ArrivalsRequest(new LongImmutableList(ids), 128, -1).getArrivals(simulator);
		// The pinned Core already sorts by arrival time (then its own tie breakers).
		return arrivalData(filterSortedArrivals(response.getArrivals(), platforms, selection, response.getCurrentTime(), timestamp), timestamp);
	}

	static JsonArray filterArrivals(Iterable<ArrivalResponse> arrivals, Map<Long, Platform> platforms, BlueMapRoutePolicy.WorldSelection selection, long coreTime, long wallTime) {
		final List<ArrivalResponse> sorted = new ArrayList<>();
		arrivals.forEach(sorted::add);
		sorted.sort(java.util.Comparator.comparingLong(ArrivalResponse::getArrival));
		return filterSortedArrivals(sorted, platforms, selection, coreTime, wallTime);
	}

	/** Preserve the Core's order, including tied arrivals, without copying its whole response. */
	static JsonArray filterSortedArrivals(Iterable<ArrivalResponse> arrivals, Map<Long, Platform> platforms, BlueMapRoutePolicy.WorldSelection selection, long coreTime, long wallTime) {
		final JsonArray result = new JsonArray();
		final Long2IntOpenHashMap stationCounts = new Long2IntOpenHashMap();
		for (ArrivalResponse arrival : arrivals) {
			final Platform platform = platforms.get(arrival.getPlatformId());
			if (platform == null || platform.area == null || arrival.getDeparture() < coreTime) {
				continue;
			}
			final long stationId = platform.area.getId();
			final int count = stationCounts.get(stationId);
			if (count >= 8) {
				continue;
			}
			final BlueMapRoutePolicy.DisplayRoute display = selection.route(arrival.getRouteId());
			if (display == null) {
				continue;
			}
			stationCounts.put(stationId, count + 1);
			final JsonObject entry = new JsonObject();
			entry.addProperty("stationId", Long.toUnsignedString(stationId));
			entry.addProperty("platformId", Long.toUnsignedString(platform.getId()));
			entry.addProperty("platformName", arrival.getPlatformName());
			entry.addProperty("routeId", display.id);
			entry.addProperty("routeName", display.name);
			entry.addProperty("color", display.color);
			addOperator(entry, display.operator(arrival.getRouteId()));
			entry.addProperty("sourceRouteId", Long.toUnsignedString(arrival.getRouteId()));
			final BlueMapRoutePolicy.Service service = display.service(arrival.getRouteId(), arrival.getRouteName());
			entry.addProperty("serviceId", service.id);
			entry.addProperty("serviceName", service.name);
			entry.addProperty("showServiceName", service.showName);
			entry.addProperty("destination", arrival.getDestination());
			entry.addProperty("arrivalTime", wallTime + (arrival.getArrival() - coreTime));
			entry.addProperty("departureTime", wallTime + (arrival.getDeparture() - coreTime));
			entry.addProperty("realtime", arrival.getRealtime());
			entry.addProperty("terminating", arrival.getIsTerminating());
			result.add(entry);
		}
		return result;
	}

	private static Map<Long, Platform> selectedPlatforms(Data data, BlueMapRoutePolicy.WorldSelection selection) {
		final Map<Long, Platform> result = new LinkedHashMap<>();
		for (Route route : data.routes) {
			if (selection.route(route.getId()) != null) {
				for (RoutePlatformData platformData : route.getRoutePlatforms()) {
					final Platform platform = platformData.getPlatform();
					if (platform != null && platform.area != null) {
						result.put(platform.getId(), platform);
					}
				}
			}
		}
		return result;
	}

	static JsonObject arrivalData(JsonArray arrivals, long timestamp) {
		final JsonObject result = new JsonObject();
		result.add("arrivals", arrivals);
		result.addProperty("arrivalsTimestamp", timestamp);
		return result;
	}

	private static final class RouteGeometry {
		private final BlueMapRoutePolicy.DisplayRoute display;
		private final Map<String, RailSpan> spans = new LinkedHashMap<>();
		private final Set<String> stations = new LinkedHashSet<>();
		private final Map<String, JsonObject> services = new LinkedHashMap<>();
		private final JsonArray operators = new JsonArray();
		private int missingLegs;

		private RouteGeometry(BlueMapRoutePolicy.DisplayRoute display) {
			this.display = display;
		}

		private void add(Rail rail, double start, double end) {
			final RailSpan span = spans.computeIfAbsent(rail.getHexId(), ignored -> new RailSpan(rail, start, end));
			span.start = Math.min(span.start, start);
			span.end = Math.max(span.end, end);
		}

		private JsonObject json(RailGeometryCache geometryCache) {
			final JsonObject result = new JsonObject();
			result.addProperty("id", display.id);
			result.addProperty("name", display.name);
			result.addProperty("color", display.color);
			result.add("operators", operators);
			result.addProperty("geometry", "rail-path");
			result.addProperty("missingLegs", missingLegs);
			final JsonArray paths = new JsonArray();
			for (RailSpan span : spans.values()) {
				paths.add(geometryCache.span(span.rail.railMath, span.start, span.end));
			}
			final JsonArray stationIds = new JsonArray();
			stations.forEach(stationIds::add);
			result.add("paths", paths);
			result.add("stations", stationIds);
			final JsonArray serviceList = new JsonArray();
			services.values().forEach(serviceList::add);
			result.add("services", serviceList);
			return result;
		}
	}

	private static final class RailSpan {
		private final Rail rail;
		private double start;
		private double end;

		private RailSpan(Rail rail, double start, double end) {
			this.rail = rail;
			this.start = start;
			this.end = end;
		}
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
		return rails(rails, timestamp, new RailGeometryCache());
	}

	private static JsonObject rails(Iterable<Rail> rails, long timestamp, RailGeometryCache geometryCache) {
		final JsonArray result = new JsonArray();
		for (Rail rail : rails) {
			// An empty style list is MTR's intentionally invisible track.
			if (rail.getTransportMode() != TransportMode.TRAIN || rail.getStyles().isEmpty() || !rail.isValid()) {
				continue;
			}
			final JsonArray points = geometryCache.rail(rail.railMath);
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
