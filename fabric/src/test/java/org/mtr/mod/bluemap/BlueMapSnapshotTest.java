package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class BlueMapSnapshotTest {

	@TempDir
	Path directory;

	@Test
	public void asymmetricBogiePivotAndThreeJsPitchPreserveCarGeometry() {
		final VehicleCar vehicle = new VehicleCar("train", 20, 3.2, -6, 2, 0, 0);
		final JsonObject result = BlueMapSnapshot.car(vehicle, List.of(
				pair(new Vector(10, 64, 0), new Vector(10, 64, 0)),
				pair(new Vector(18, 68, 0), new Vector(18, 68, 0))
		), true);
		assertNotNull(result);
		assertEquals(Math.PI / 2, result.get("yaw").getAsDouble(), 1E-10);
		assertEquals(-Math.atan2(4, 8), result.get("pitch").getAsDouble(), 1E-10);
		assertEquals(14 + 2 * 8 / Math.sqrt(80), result.getAsJsonArray("position").get(0).getAsDouble(), 1E-9);
		assertEquals(66 + 2 * 4 / Math.sqrt(80), result.getAsJsonArray("position").get(1).getAsDouble(), 1E-9);
		assertEquals(20, result.get("length").getAsDouble());
		assertEquals(3.2, result.get("width").getAsDouble());
		assertNull(BlueMapSnapshot.car(vehicle, List.of(), true));
	}

	@Test
	public void singleBogieAndReversedCarsUseTheirActualDirection() {
		final VehicleCar vehicle = new VehicleCar("single", 12, 2.5, 0, 0, 0, 0);
		final JsonObject forward = BlueMapSnapshot.car(vehicle, List.of(pair(new Vector(0, 1, 0), new Vector(0, 3, 8))), true);
		final JsonObject reverse = BlueMapSnapshot.car(vehicle, List.of(pair(new Vector(0, 3, 8), new Vector(0, 1, 0))), true);
		assertEquals(forward.get("position"), reverse.get("position"));
		assertEquals(Math.PI, Math.abs(reverse.get("yaw").getAsDouble()), 1E-10);
		assertEquals(-forward.get("pitch").getAsDouble(), reverse.get("pitch").getAsDouble(), 1E-10);
	}

	@Test
	public void curvesAndSlopesUseCoreGeometryAndInvisibleRailsStayHidden() {
		final Rail curve = rail(new Position(0, 64, 0), Angle.E, new Position(32, 80, 48), Angle.S, true);
		final Rail invisible = rail(new Position(0, 64, 0), Angle.E, new Position(64, 64, 0), Angle.W, false);
		final JsonArray rails = BlueMapSnapshot.rails(List.of(curve, invisible), 123).getAsJsonArray("rails");
		assertEquals(1, rails.size());
		final JsonArray points = rails.get(0).getAsJsonObject().getAsJsonArray("points");
		assertTrue(points.size() > 40);
		assertPoint(curve.railMath.getPosition(0, false), points.get(0).getAsJsonArray());
		assertPoint(curve.railMath.getPosition(curve.railMath.getLength(), false), points.get(points.size() - 1).getAsJsonArray());
		final int index = points.size() / 2;
		assertPoint(curve.railMath.getPosition(curve.railMath.getLength() * index / (points.size() - 1), false), points.get(index).getAsJsonArray());
	}

	@Test
	public void generatedRouteLegFollowsIntermediateRailsAndDoesNotInventMissingLinks() {
		final Position a = new Position(0, 64, 0);
		final Position b = new Position(32, 64, 0);
		final Position c = new Position(64, 76, 32);
		final Position d = new Position(64, 76, 64);
		final List<PathData> paths = List.of(
				new PathData(rail(a, Angle.E, b, Angle.W, true), 11, 100, 0, a, b),
				new PathData(rail(b, Angle.E, c, Angle.S, true), 0, 0, 1, b, c),
				new PathData(rail(c, Angle.N, d, Angle.S, true), 22, 100, 1, c, d)
		);
		final JsonArray result = BlueMapSnapshot.routeLeg(paths, 11, 22);
		assertTrue(result.size() > 50);
		assertPoint(paths.get(0).getPosition(paths.get(0).getRailLength() / 2), result.get(0).getAsJsonArray());
		assertPoint(paths.get(2).getPosition(paths.get(2).getRailLength() / 2), result.get(result.size() - 1).getAsJsonArray());
		assertTrue(BlueMapSnapshot.routeLeg(paths, 11, 33).isEmpty());
		assertTrue(BlueMapSnapshot.routeLeg(paths, 22, 11).isEmpty());
	}

	@Test
	public void currentRouteColorWinsOverAnotherRouteAndNextIsOnlyAFallback() {
		final JsonObject result = new JsonObject();
		BlueMapSnapshot.addRoute(result, new VehicleExtraData(reader("{\"thisRouteId\":7,\"thisRouteName\":\"Current\",\"thisRouteColor\":1193046,\"nextRouteId\":8,\"nextRouteName\":\"Next\",\"nextRouteColor\":16711680}")));
		assertEquals("7", result.get("routeId").getAsString());
		assertEquals("#123456", result.get("color").getAsString());
		final JsonObject fallback = new JsonObject();
		BlueMapSnapshot.addRoute(fallback, new VehicleExtraData(reader("{\"nextRouteId\":8,\"nextRouteName\":\"Next\",\"nextRouteColor\":16711680}")));
		assertEquals("8", fallback.get("routeId").getAsString());
		assertEquals("#ff0000", fallback.get("color").getAsString());
	}

	@Test
	public void sharedStationsListAllVisibleRoutesAndUsePlatformElevation() {
		final ClientData data = new ClientData();
		final Station station = new Station(data);
		station.setName("Shared");
		final Platform platform = new Platform(new Position(0, 73, 0), new Position(20, 73, 0), TransportMode.TRAIN, data);
		platform.area = station;
		station.savedRails.add(platform);
		data.stations.add(station);
		for (int i = 0; i < 3; i++) {
			final Route route = new Route(TransportMode.TRAIN, data);
			route.setName("Route " + i);
			route.setHidden(i == 2);
			final RoutePlatformData stop = new RoutePlatformData(platform.getId());
			stop.platform = platform;
			route.getRoutePlatforms().add(stop);
			data.routes.add(route);
		}
		final List<Route> selected = new ArrayList<>();
		data.routes.forEach(route -> { if (!route.getHidden()) selected.add(route); });
		final JsonObject result = BlueMapSnapshot.infrastructure(data, 50, selection(data, selected, false));
		assertEquals(2, result.getAsJsonArray("routes").size());
		final JsonObject exported = result.getAsJsonArray("stations").get(0).getAsJsonObject();
		assertEquals(2, exported.getAsJsonArray("routes").size());
		assertEquals(73, exported.getAsJsonArray("position").get(1).getAsDouble());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void realCoreVehiclesExportWithoutAnyClientsIncludingParkedAndMultipleCars() throws Exception {
		final ClientData data = new ClientData();
		final Route route = new Route(TransportMode.TRAIN, data);
		route.setName("Public express");
		data.routes.add(route);
		final Position a = new Position(0, 64, 0);
		final Position b = new Position(200, 70, 0);
		final Rail rail = rail(a, Angle.E, b, Angle.W, true);
		final PathData path = new PathData(rail, 0, 0, 0, a, b);
		final JsonObject extraJson = new JsonObject();
		final JsonArray pathJson = new JsonArray();
		pathJson.add(Utilities.getJsonObjectFromData(new PathData(path, 0, rail.railMath.getLength())));
		extraJson.add("path", pathJson);
		final JsonArray carJson = new JsonArray();
		carJson.add(Utilities.getJsonObjectFromData(new VehicleCar("car-a", 20, 3, -7, 7, 0, 0)));
		carJson.add(Utilities.getJsonObjectFromData(new VehicleCar("car-b", 24, 3.4, -8, 8, 0, 0)));
		extraJson.add("vehicleCars", carJson);
		extraJson.addProperty("railLength", rail.railMath.getLength());
		extraJson.addProperty("totalVehicleLength", 44);
		extraJson.addProperty("defaultPosition", 100);
		extraJson.addProperty("thisRouteId", route.getId());
		extraJson.addProperty("thisRouteName", route.getName());
		final VehicleExtraData extra = new VehicleExtraData(new JsonReader(extraJson));
		PathData.writePathCache(extra.immutablePath, data, TransportMode.TRAIN);
		final Field field = Siding.class.getDeclaredField("vehicles");
		field.setAccessible(true);
		for (int i = 0; i < 4; i++) {
			final Siding siding = new Siding(a, b, rail.railMath.getLength(), TransportMode.TRAIN, data);
			data.sidings.add(siding);
			final JsonObject privateExtra = extraJson.deepCopy();
			privateExtra.addProperty("thisRouteId", i == 2 ? 99 : 0);
			final VehicleExtraData vehicleExtra = i < 2 ? extra : new VehicleExtraData(new JsonReader(privateExtra));
			if (i >= 2) PathData.writePathCache(vehicleExtra.immutablePath, data, TransportMode.TRAIN);
			final Vehicle vehicle = new Vehicle(vehicleExtra, siding, reader("{\"id\":" + (i + 1) + ",\"railProgress\":" + (i == 0 ? 80 : 130) + ",\"transportMode\":\"TRAIN\"}"), data);
			assertEquals(i != 0, vehicle.getIsOnRoute());
			((Collection<Vehicle>) field.get(siding)).add(vehicle);
		}
		final JsonArray result = new BlueMapSnapshot().vehicles(data, 1234, selection(data, List.of(route), true)).getAsJsonArray("vehicles");
		assertEquals(2, result.size());
		assertEquals("group:line", result.get(0).getAsJsonObject().get("routeId").getAsString());
		assertEquals("Public express", result.get(0).getAsJsonObject().get("serviceName").getAsString());
		assertEquals(Long.toUnsignedString(route.getId()), result.get(0).getAsJsonObject().get("sourceRouteId").getAsString());
		assertEquals("#123456", result.get(0).getAsJsonObject().get("color").getAsString());
		assertEquals(2, result.get(0).getAsJsonObject().getAsJsonArray("cars").size());
		assertEquals(24, result.get(1).getAsJsonObject().getAsJsonArray("cars").get(1).getAsJsonObject().get("length").getAsDouble());
		assertTrue(new BlueMapSnapshot().vehicles(data, 1234, BlueMapRoutePolicy.WorldSelection.EMPTY).getAsJsonArray("vehicles").isEmpty());
	}

	@Test
	public void groupedHiddenServicesPublishOnlyTheirRealRailsAndPublicPlatformCenters() {
		final ClientData data = new ClientData();
		final Position a = new Position(0, 73, 0);
		final Position b = new Position(32, 73, 0);
		final Position c = new Position(64, 80, 32);
		final Position d = new Position(64, 80, 64);
		final Station shared = station(data, "Shared");
		final Station terminus = station(data, "Public terminus");
		final Platform first = platform(data, shared, a, b);
		final Platform last = platform(data, terminus, c, d);
		final Platform privatePlatform = platform(data, shared, new Position(1000, 1, 0), new Position(1020, 1, 0));
		final Station secret = station(data, "Secret station");
		final Platform secretPlatform = platform(data, secret, new Position(2000, 5, 0), new Position(2020, 5, 0));
		final Route local = route(data, "Local", first, last);
		final Route express = route(data, "Express", first, last);
		express.setHidden(true); // Explicit publication is independent of MTR's hidden flag.
		final Route privateRoute = route(data, "Private", privatePlatform, secretPlatform);
		final Depot depot = new Depot(TransportMode.TRAIN, data);
		final List<PathData> path = List.of(
				new PathData(rail(a, Angle.E, b, Angle.W, true), first.getId(), 100, 0, a, b),
				new PathData(rail(b, Angle.E, c, Angle.S, true), 0, 0, 1, b, c),
				new PathData(rail(c, Angle.N, d, Angle.S, true), last.getId(), 100, 1, c, d)
		);
		depot.getPath().addAll(path);
		local.depots.add(depot);
		express.depots.add(depot);
		path.forEach(segment -> data.rails.add(segment.getRail()));
		data.rails.add(rail(new Position(500, 5, 0), Angle.E, new Position(600, 5, 0), Angle.W, true));
		final JsonObject output = BlueMapSnapshot.infrastructure(data, 100, selection(data, List.of(local, express), true));
		assertEquals(3, output.getAsJsonArray("rails").size());
		assertEquals(1, output.getAsJsonArray("routes").size());
		final JsonObject group = output.getAsJsonArray("routes").get(0).getAsJsonObject();
		assertEquals("group:line", group.get("id").getAsString());
		assertEquals(3, group.getAsJsonArray("paths").size()); // Shared normal/express geometry deduplicates.
		assertEquals(2, group.getAsJsonArray("stations").size());
		assertEquals(2, group.getAsJsonArray("services").size());
		assertEquals(0, group.get("missingLegs").getAsInt());
		assertEquals(2, output.getAsJsonArray("stations").size());
		for (var element : output.getAsJsonArray("stations")) {
			final JsonObject station = element.getAsJsonObject();
			assertEquals(1, station.getAsJsonArray("routes").size());
			assertEquals(2, station.getAsJsonArray("services").size());
			if (station.get("name").getAsString().equals("Shared")) {
				assertEquals(73, station.getAsJsonArray("position").get(1).getAsDouble());
				assertEquals(16, station.getAsJsonArray("position").get(0).getAsDouble());
			}
		}
		assertFalse(output.toString().contains(Long.toUnsignedString(privateRoute.getId())));
		assertFalse(output.toString().contains("Secret"));
		final JsonObject privateCatalog = BlueMapSnapshot.routeCatalog(data, "minecraft/overworld");
		assertEquals(3, privateCatalog.getAsJsonArray("routes").size());
		assertTrue(privateCatalog.toString().contains("\"hidden\":true"));
		final JsonObject closed = BlueMapSnapshot.infrastructure(data, 101, BlueMapRoutePolicy.WorldSelection.EMPTY);
		assertTrue(closed.getAsJsonArray("rails").isEmpty());
		assertTrue(closed.getAsJsonArray("routes").isEmpty());
		assertTrue(closed.getAsJsonArray("stations").isEmpty());
	}

	@Test
	public void arrivalsFilterBothRouteAndPlatformPreserveDwellAndDoNotDoubleAddDeviation() {
		final ClientData data = new ClientData();
		final Station station = station(data, "Shared");
		final Platform platform = platform(data, station, new Position(0, 64, 0), new Position(20, 64, 0));
		final Platform other = platform(data, station, new Position(50, 64, 0), new Position(70, 64, 0));
		final Route local = route(data, "Local", platform);
		final Route express = route(data, "Express", platform);
		final Route privateRoute = route(data, "Private", platform);
		final List<ArrivalResponse> responses = List.of(
				new ArrivalResponse("Dwelling", 9000, 11000, 500, true, 0, 0, local, platform),
				new ArrivalResponse("Scheduled", 12000, 12500, 0, false, 0, 0, express, platform),
				new ArrivalResponse("Departed", 5000, 9999, 0, true, 0, 0, local, platform),
				new ArrivalResponse("Hidden service", 10500, 11500, 0, true, 0, 0, privateRoute, platform),
				new ArrivalResponse("Hidden platform", 10000, 11000, 0, true, 0, 0, local, other)
		);
		final var selection = selection(data, List.of(local, express), true);
		final JsonArray arrivals = BlueMapSnapshot.filterArrivals(responses, Map.of(platform.getId(), platform), selection, 10000, 1000000);
		assertEquals(2, arrivals.size());
		final JsonObject dwelling = arrivals.get(0).getAsJsonObject();
		assertEquals(999000, dwelling.get("arrivalTime").getAsLong());
		assertEquals(1001000, dwelling.get("departureTime").getAsLong());
		assertEquals("group:line", dwelling.get("routeId").getAsString());
		assertEquals("Local", dwelling.get("serviceName").getAsString());
		assertTrue(dwelling.get("realtime").getAsBoolean());
		assertTrue(dwelling.get("terminating").getAsBoolean());
		assertFalse(arrivals.get(1).getAsJsonObject().get("realtime").getAsBoolean());
		final List<ArrivalResponse> many = new ArrayList<>();
		for (int i = 9; i >= 0; i--) many.add(new ArrivalResponse("Train " + i, 20000 + i, 21000 + i, 0, true, i, 0, local, platform));
		final JsonArray bounded = BlueMapSnapshot.filterArrivals(many, Map.of(platform.getId(), platform), selection, 10000, 1000000);
		assertEquals(8, bounded.size());
		assertEquals("Train 0", bounded.get(0).getAsJsonObject().get("destination").getAsString());
		assertEquals(999, BlueMapSnapshot.arrivalData(arrivals, 999).get("arrivalsTimestamp").getAsLong());
	}

	@Test
	public void publishedJsonReplacementIsCompleteAndLeavesNoTemporaryFile() throws Exception {
		final Path file = directory.resolve("mtr-live/vehicles.json");
		BlueMapLiveBridge.atomicWrite(file, "{\"vehicles\":[1,2,3]}");
		BlueMapLiveBridge.atomicWrite(file, "{\"vehicles\":[]}");
		assertEquals(0, JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("vehicles").size());
		assertFalse(Files.exists(file.resolveSibling("vehicles.json.tmp")));
	}

	@Test
	public void initialPrivateSettingsNeverOverwriteExistingUserConfiguration() throws Exception {
		final Path file = directory.resolve("config/mtr/bluemap-public-routes.json");
		BlueMapLiveBridge.createInitial(file, BlueMapRoutePolicy.INITIAL);
		assertEquals(BlueMapRoutePolicy.INITIAL, Files.readString(file));
		Files.writeString(file, "administrator contents");
		BlueMapLiveBridge.createInitial(file, BlueMapRoutePolicy.INITIAL);
		assertEquals("administrator contents", Files.readString(file));
		try (var files = Files.list(file.getParent())) {
			assertEquals(1, files.count());
		}
	}

	private static BlueMapRoutePolicy.WorldSelection selection(ClientData data, List<Route> routes, boolean group) {
		final JsonArray ids = new JsonArray();
		routes.forEach(route -> ids.add(Long.toUnsignedString(route.getId())));
		final JsonObject settings = new JsonObject();
		if (group) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", "line");
			entry.addProperty("name", "Combined line");
			entry.addProperty("color", "#123456");
			entry.add("routeIds", ids);
			final JsonArray groups = new JsonArray();
			groups.add(entry);
			settings.add("groups", groups);
		} else {
			settings.add("routes", ids);
		}
		final JsonObject worlds = new JsonObject();
		worlds.add("minecraft/overworld", settings);
		final JsonObject policy = new JsonObject();
		policy.addProperty("schema", 1);
		policy.add("worlds", worlds);
		return BlueMapRoutePolicy.parse(policy.toString(), Map.of("minecraft/overworld", BlueMapSnapshot.routeCatalog(data, "minecraft/overworld"))).world("minecraft/overworld");
	}

	@Test
	public void revocationClearsRemovedWorldsAndCrashTempsEvenWhenOneFileCannotBeReplaced() throws Exception {
		final String removed = "11111111-1111-1111-1111-111111111111";
		final String broken = "22222222-2222-2222-2222-222222222222";
		final Path root = directory.resolve("web/mtr-live");
		Files.createDirectories(root);
		Files.writeString(root.resolve(removed + "-rails.json.tmp"), "old private geometry");
		Files.writeString(root.resolve("other-plugin.json"), "preserve unrelated files");
		Files.createDirectories(root.resolve(broken + "-vehicles.json"));
		Files.writeString(root.resolve(broken + "-vehicles.json/obstacle"), "cannot replace this directory");
		assertThrows(java.io.IOException.class, () -> BlueMapLiveBridge.clearPublicSnapshots(root, List.of(broken), "new-process:2", 100));
		final JsonObject vehicles = JsonParser.parseString(Files.readString(root.resolve(removed + "-vehicles.json"))).getAsJsonObject();
		assertTrue(vehicles.getAsJsonArray("vehicles").isEmpty());
		assertEquals("new-process:2", vehicles.get("publicationRevision").getAsString());
		assertEquals(0, vehicles.get("arrivalsTimestamp").getAsLong());
		assertFalse(Files.exists(root.resolve(removed + "-rails.json.tmp")));
		assertTrue(JsonParser.parseString(Files.readString(root.resolve(broken + "-rails.json"))).getAsJsonObject().getAsJsonArray("routes").isEmpty());
		assertEquals("preserve unrelated files", Files.readString(root.resolve("other-plugin.json")));
	}

	private static Station station(ClientData data, String name) {
		final Station station = new Station(data);
		station.setName(name);
		data.stations.add(station);
		return station;
	}

	private static Platform platform(ClientData data, Station station, Position a, Position b) {
		final Platform platform = new Platform(a, b, TransportMode.TRAIN, data);
		platform.area = station;
		station.savedRails.add(platform);
		return platform;
	}

	private static Route route(ClientData data, String name, Platform... platforms) {
		final Route route = new Route(TransportMode.TRAIN, data);
		route.setName(name);
		for (Platform platform : platforms) {
			final RoutePlatformData stop = new RoutePlatformData(platform.getId());
			stop.platform = platform;
			route.getRoutePlatforms().add(stop);
		}
		data.routes.add(route);
		return route;
	}

	private static Rail rail(Position a, Angle aAngle, Position b, Angle bAngle, boolean visible) {
		return Rail.newRail(a, aAngle, b, bAngle, Rail.Shape.QUADRATIC, 0, visible ? ObjectArrayList.of("default") : new ObjectArrayList<>(), 80, 80, false, false, true, false, false, TransportMode.TRAIN);
	}

	private static ObjectObjectImmutablePair<Vector, Vector> pair(Vector first, Vector second) {
		return new ObjectObjectImmutablePair<>(first, second);
	}

	private static JsonReader reader(String value) {
		return new JsonReader(JsonParser.parseString(value).getAsJsonObject());
	}

	private static void assertPoint(Vector expected, JsonArray actual) {
		assertEquals(expected.x, actual.get(0).getAsDouble(), 1E-9);
		assertEquals(expected.y, actual.get(1).getAsDouble(), 1E-9);
		assertEquals(expected.z, actual.get(2).getAsDouble(), 1E-9);
	}
}
