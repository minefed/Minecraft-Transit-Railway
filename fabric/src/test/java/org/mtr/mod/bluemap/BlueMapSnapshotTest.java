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
	public void realCoreVehiclesHideDepotParkingButKeepStationDwellAndMultipleCarsWithoutClients() throws Exception {
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
			final Vehicle vehicle = new Vehicle(vehicleExtra, siding, reader("{\"id\":" + (i + 1) + ",\"railProgress\":" + (i == 0 ? 100 : 130) + ",\"speed\":0,\"transportMode\":\"TRAIN\"}"), data);
			assertEquals(i != 0, vehicle.getIsOnRoute());
			assertFalse(vehicle.isMoving()); // Station dwell is not depot parking.
			((Collection<Vehicle>) field.get(siding)).add(vehicle);
		}
		final JsonArray result = new BlueMapSnapshot().vehicles(data, 1234, selection(data, List.of(route), true)).getAsJsonArray("vehicles");
		assertEquals(1, result.size());
		assertEquals("2", result.get(0).getAsJsonObject().get("id").getAsString());
		assertEquals("group:line", result.get(0).getAsJsonObject().get("routeId").getAsString());
		assertEquals("Public express", result.get(0).getAsJsonObject().get("serviceName").getAsString());
		assertEquals(Long.toUnsignedString(route.getId()), result.get(0).getAsJsonObject().get("sourceRouteId").getAsString());
		assertEquals("#123456", result.get(0).getAsJsonObject().get("color").getAsString());
		assertEquals(2, result.get(0).getAsJsonObject().getAsJsonArray("cars").size());
		assertEquals(24, result.get(0).getAsJsonObject().getAsJsonArray("cars").get(1).getAsJsonObject().get("length").getAsDouble());
		assertTrue(new BlueMapSnapshot().vehicles(data, 1234, BlueMapRoutePolicy.WorldSelection.EMPTY).getAsJsonArray("vehicles").isEmpty());
		final JsonArray renamed = new BlueMapSnapshot().vehicles(data, 1235, selection(data, List.of(route), true, Map.of(route.getId(), "급행"))).getAsJsonArray("vehicles");
		assertEquals(1, renamed.size());
		for (var element : renamed) {
			final JsonObject train = element.getAsJsonObject();
			assertEquals("급행", train.get("serviceName").getAsString());
			assertEquals(Long.toUnsignedString(route.getId()), train.get("sourceRouteId").getAsString());
			assertEquals("group:line", train.get("routeId").getAsString());
			assertEquals("Combined line", train.get("routeName").getAsString());
		}
		assertEquals("Public express", route.getName());
		final JsonArray explicit = new BlueMapSnapshot().vehicles(data, 1236, selection(data, service("rapid", "쾌속", false, route))).getAsJsonArray("vehicles");
		assertEquals(1, explicit.size());
		final JsonObject train = explicit.get(0).getAsJsonObject();
		assertEquals("service:line:rapid", train.get("serviceId").getAsString());
		assertEquals("쾌속", train.get("serviceName").getAsString());
		assertFalse(train.get("showServiceName").getAsBoolean());
		assertEquals(Long.toUnsignedString(route.getId()), train.get("sourceRouteId").getAsString());
		final var operated = configuredSelection(data, "{\"routes\":[\"" + Long.toUnsignedString(route.getId()) + "\"],\"routeOperators\":{\"" + Long.toUnsignedString(route.getId()) + "\":\"a\"}}");
		final JsonObject operatedTrain = new BlueMapSnapshot().vehicles(data, 1237, operated).getAsJsonArray("vehicles").get(0).getAsJsonObject();
		assertEquals("a", operatedTrain.get("operatorId").getAsString());
		assertEquals("Operator A", operatedTrain.get("operatorName").getAsString());
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
		final RailGeometryCache geometryCache = new RailGeometryCache();
		final JsonObject output = BlueMapSnapshot.infrastructure(data, 100, selection(data, List.of(local, express), true), geometryCache);
		assertEquals(output.toString(), BlueMapSnapshot.infrastructure(data, 100, selection(data, List.of(local, express), true), geometryCache).toString());
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
		final JsonObject closed = BlueMapSnapshot.infrastructure(data, 101, BlueMapRoutePolicy.WorldSelection.EMPTY, geometryCache);
		assertTrue(closed.getAsJsonArray("rails").isEmpty());
		assertTrue(closed.getAsJsonArray("routes").isEmpty());
		assertTrue(closed.getAsJsonArray("stations").isEmpty());
		shared.setName("Renamed public station");
		final var mergedSelection = selection(data, service("rapid", "쾌속", true, local, express));
		final JsonObject merged = BlueMapSnapshot.infrastructure(data, 102, mergedSelection, geometryCache);
		assertEquals(BlueMapSnapshot.infrastructure(data, 102, mergedSelection).toString(), merged.toString());
		assertTrue(merged.toString().contains("Renamed public station"));
		assertTrue(output.toString().contains("Shared")); // A later capture must not mutate an already published snapshot.
		final JsonObject mergedGroup = merged.getAsJsonArray("routes").get(0).getAsJsonObject();
		assertEquals(3, merged.getAsJsonArray("rails").size());
		assertEquals(3, mergedGroup.getAsJsonArray("paths").size());
		assertEquals(1, mergedGroup.getAsJsonArray("services").size());
		for (var element : merged.getAsJsonArray("stations")) {
			final JsonArray services = element.getAsJsonObject().getAsJsonArray("services");
			assertEquals(1, services.size());
			assertEquals("service:line:rapid", services.get(0).getAsJsonObject().get("id").getAsString());
		}
		final Rail changedRail = Rail.copy(path.get(1).getRail(), Rail.Shape.CABLE, 0);
		assertEquals(path.get(1).getRail().getHexId(), changedRail.getHexId());
		depot.getPath().set(1, new PathData(changedRail, 0, 0, 1, b, c));
		final JsonObject edited = BlueMapSnapshot.infrastructure(data, 102, mergedSelection, geometryCache);
		assertEquals(BlueMapSnapshot.infrastructure(data, 102, mergedSelection).toString(), edited.toString());
		assertNotEquals(merged.toString(), edited.toString());
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
	public void sortedCoreArrivalsPreserveTiesAndCapEachStationAcrossPlatforms() {
		final ClientData data = new ClientData();
		final Station first = station(data, "First");
		final Station second = station(data, "Second");
		final Platform firstA = platform(data, first, new Position(0, 64, 0), new Position(20, 64, 0));
		final Platform firstB = platform(data, first, new Position(40, 64, 0), new Position(60, 64, 0));
		final Platform secondA = platform(data, second, new Position(80, 64, 0), new Position(100, 64, 0));
		final Route selected = route(data, "Public", firstA, firstB, secondA);
		final Route hidden = route(data, "Private", firstA);
		final var policy = selection(data, List.of(selected), false);
		final Map<Long, Platform> platforms = Map.of(firstA.getId(), firstA, firstB.getId(), firstB, secondA.getId(), secondA);
		final List<ArrivalResponse> responses = new ArrayList<>();
		responses.add(new ArrivalResponse("Expired", 8000, 9999, 0, true, 0, 0, selected, firstA));
		responses.add(new ArrivalResponse("Private", 9000, 12000, 0, true, 0, 0, hidden, firstA));
		for (int i = 0; i < 128; i++) {
			// Equal arrival times exercise the Core comparator's tie order and stable publication.
			responses.add(new ArrivalResponse("First " + i, 11000 + i / 2, 12000 + i, 0, i % 2 == 0, i, 0, selected, i % 2 == 0 ? firstA : firstB));
			responses.add(new ArrivalResponse("Second " + i, 11000 + i / 2, 12000 + i, 0, i % 2 != 0, i, 0, selected, secondA));
		}
		java.util.Collections.shuffle(responses, new java.util.Random(141013));
		java.util.Collections.sort(responses); // ArrivalsRequest.getArrivals uses this Core order.
		final JsonArray result = BlueMapSnapshot.filterSortedArrivals(responses, platforms, policy, 10000, 1000000);
		assertEquals(BlueMapSnapshot.filterArrivals(responses, platforms, policy, 10000, 1000000), result);
		assertEquals(16, result.size());
		final Map<String, Integer> counts = new java.util.HashMap<>();
		for (var element : result) {
			final JsonObject arrival = element.getAsJsonObject();
			counts.merge(arrival.get("stationId").getAsString(), 1, Integer::sum);
			assertFalse(arrival.get("destination").getAsString().equals("Expired"));
			assertFalse(arrival.get("destination").getAsString().equals("Private"));
			assertTrue(Integer.parseInt(arrival.get("destination").getAsString().split(" ")[1]) < 8);
		}
		assertEquals(Map.of(Long.toUnsignedString(first.getId()), 8, Long.toUnsignedString(second.getId()), 8), counts);
		assertTrue(BlueMapSnapshot.filterSortedArrivals(responses, platforms, BlueMapRoutePolicy.CLOSED.world("unknown"), 10000, 1000000).isEmpty());
	}

	@Test
	public void configuredServiceNamesReachRoutesStationsAndArrivalsWithoutRenamingThePrivateCatalog() {
		final ClientData data = new ClientData();
		final Station station = station(data, "Shared");
		final Platform platform = platform(data, station, new Position(0, 64, 0), new Position(20, 64, 0));
		final Route local = route(data, "MTR original local", platform);
		final Route express = route(data, "MTR original express", platform);
		final String originalCatalog = BlueMapSnapshot.routeCatalog(data, "minecraft/overworld").toString();
		final var selected = selection(data, List.of(local, express), true, Map.of(express.getId(), "급행"));
		final JsonObject infrastructure = BlueMapSnapshot.infrastructure(data, 100, selected);
		final JsonObject group = infrastructure.getAsJsonArray("routes").get(0).getAsJsonObject();
		assertEquals("Combined line", group.get("name").getAsString());
		assertEquals("group:line", group.get("id").getAsString());
		assertServiceNames(group.getAsJsonArray("services"), local, express);
		final JsonObject exportedStation = infrastructure.getAsJsonArray("stations").get(0).getAsJsonObject();
		assertServiceNames(exportedStation.getAsJsonArray("services"), local, express);
		assertEquals(1, exportedStation.getAsJsonArray("routes").size());
		final List<ArrivalResponse> responses = List.of(
				new ArrivalResponse("Local destination", 11000, 11500, 0, true, 0, 0, local, platform),
				new ArrivalResponse("Express destination", 12000, 12500, 0, false, 0, 0, express, platform)
		);
		final JsonArray arrivals = BlueMapSnapshot.filterArrivals(responses, Map.of(platform.getId(), platform), selected, 10000, 1000000);
		assertEquals(2, arrivals.size());
		assertEquals("MTR original local", arrivals.get(0).getAsJsonObject().get("serviceName").getAsString());
		final JsonObject expressArrival = arrivals.get(1).getAsJsonObject();
		assertEquals("급행", expressArrival.get("serviceName").getAsString());
		assertEquals(Long.toUnsignedString(express.getId()), expressArrival.get("sourceRouteId").getAsString());
		assertEquals("Combined line", expressArrival.get("routeName").getAsString());
		assertEquals("group:line", expressArrival.get("routeId").getAsString());
		assertEquals("Express destination", expressArrival.get("destination").getAsString());
		assertEquals(originalCatalog, BlueMapSnapshot.routeCatalog(data, "minecraft/overworld").toString());
		assertEquals("MTR original local", local.getName());
		assertEquals("MTR original express", express.getName());
	}

	private static void assertServiceNames(JsonArray services, Route local, Route express) {
		assertEquals(2, services.size());
		final Map<String, JsonObject> byId = new java.util.HashMap<>();
		services.forEach(service -> byId.put(service.getAsJsonObject().get("id").getAsString(), service.getAsJsonObject()));
		assertEquals("MTR original local", byId.get(Long.toUnsignedString(local.getId())).get("name").getAsString());
		assertEquals("급행", byId.get(Long.toUnsignedString(express.getId())).get("name").getAsString());
		byId.values().forEach(service -> assertEquals("group:line", service.get("routeId").getAsString()));
	}

	@Test
	public void explicitServicesDeduplicateStationBadgesWithoutCollapsingActualArrivals() {
		final ClientData data = new ClientData();
		final Station station = station(data, "Shared");
		final Platform platform = platform(data, station, new Position(0, 64, 0), new Position(20, 64, 0));
		final Route local = route(data, "Original local", platform);
		final Route rapidA = route(data, "Original rapid A", platform);
		final Route rapidB = route(data, "Original rapid B", platform);
		final String originalCatalog = BlueMapSnapshot.routeCatalog(data, "minecraft/overworld").toString();
		final var selected = selection(data, service("local", "일반", true, local), service("rapid", "쾌속", false, rapidA, rapidB));
		final JsonObject infrastructure = BlueMapSnapshot.infrastructure(data, 100, selected);
		assertEquals(2, infrastructure.getAsJsonArray("routes").get(0).getAsJsonObject().getAsJsonArray("services").size());
		final JsonArray badges = infrastructure.getAsJsonArray("stations").get(0).getAsJsonObject().getAsJsonArray("services");
		assertEquals(2, badges.size());
		final Map<String, JsonObject> byId = new java.util.HashMap<>();
		badges.forEach(badge -> byId.put(badge.getAsJsonObject().get("id").getAsString(), badge.getAsJsonObject()));
		assertTrue(byId.get("service:line:local").get("showName").getAsBoolean());
		assertFalse(byId.get("service:line:rapid").get("showName").getAsBoolean());
		assertEquals("쾌속", byId.get("service:line:rapid").get("name").getAsString());
		final List<ArrivalResponse> responses = List.of(
				new ArrivalResponse("A destination", 11000, 11500, 0, true, 0, 0, rapidA, platform),
				new ArrivalResponse("B destination", 12000, 12500, 0, false, 0, 0, rapidB, platform)
		);
		final JsonArray arrivals = BlueMapSnapshot.filterArrivals(responses, Map.of(platform.getId(), platform), selected, 10000, 1000000);
		assertEquals(2, arrivals.size());
		assertNotEquals(arrivals.get(0).getAsJsonObject().get("sourceRouteId"), arrivals.get(1).getAsJsonObject().get("sourceRouteId"));
		for (var element : arrivals) {
			final JsonObject arrival = element.getAsJsonObject();
			assertEquals("service:line:rapid", arrival.get("serviceId").getAsString());
			assertEquals("쾌속", arrival.get("serviceName").getAsString());
			assertFalse(arrival.get("showServiceName").getAsBoolean());
		}
		assertEquals(originalCatalog, BlueMapSnapshot.routeCatalog(data, "minecraft/overworld").toString());
	}

	@Test
	public void groupedOperatorsUnionWithoutLeakingOtherStationsOperatorsAndArrivalsUseSourceOverride() {
		final ClientData data = new ClientData();
		final Station first = station(data, "A only");
		final Station second = station(data, "B only");
		final Station shared = station(data, "Both");
		final Platform pa = platform(data, first, new Position(0, 60, 0), new Position(20, 60, 0));
		final Platform pb = platform(data, second, new Position(30, 70, 0), new Position(50, 70, 0));
		final Platform ps = platform(data, shared, new Position(60, 80, 0), new Position(80, 80, 0));
		final Route a = route(data, "A", pa, ps);
		final Route b = route(data, "B", pb, ps);
		final var selection = configuredSelection(data, "{\"groups\":[{\"id\":\"line\",\"name\":\"Line\",\"operatorId\":\"a\",\"services\":[{\"id\":\"local\",\"name\":\"Local\",\"routeIds\":[\"" + Long.toUnsignedString(a.getId()) + "\",\"" + Long.toUnsignedString(b.getId()) + "\"]}]}],\"routeOperators\":{\"" + Long.toUnsignedString(b.getId()) + "\":\"b\"}}");
		final JsonObject output = BlueMapSnapshot.infrastructure(data, 100, selection);
		final JsonObject line = output.getAsJsonArray("routes").get(0).getAsJsonObject();
		assertEquals(2, line.getAsJsonArray("operators").size());
		assertEquals(2, line.getAsJsonArray("services").get(0).getAsJsonObject().getAsJsonArray("operators").size());
		for (var element : output.getAsJsonArray("stations")) {
			final JsonObject station = element.getAsJsonObject();
			final JsonArray operators = station.getAsJsonArray("services").get(0).getAsJsonObject().getAsJsonArray("operators");
			final String name = station.get("name").getAsString();
			assertEquals(name.equals("Both") ? 2 : 1, operators.size());
			if (!name.equals("Both")) assertEquals(name.startsWith("A") ? "a" : "b", operators.get(0).getAsJsonObject().get("id").getAsString());
		}
		final JsonArray arrivals = BlueMapSnapshot.filterArrivals(List.of(
				new ArrivalResponse("A destination", 11000, 11500, 0, true, 0, 0, a, pa),
				new ArrivalResponse("B destination", 12000, 12500, 0, true, 0, 0, b, pb)
		), Map.of(pa.getId(), pa, pb.getId(), pb), selection, 10000, 1000000);
		assertEquals("a", arrivals.get(0).getAsJsonObject().get("operatorId").getAsString());
		assertEquals("b", arrivals.get(1).getAsJsonObject().get("operatorId").getAsString());
		assertEquals("Operator B", arrivals.get(1).getAsJsonObject().get("operatorName").getAsString());
		assertFalse(output.toString().contains("Unused operator"));
	}

	@Test
	public void explicitInterchangesLinkOnlyPublicMembersAndRetainPhysicalStationsAndArrivals() {
		final ClientData data = new ClientData();
		final Station first = station(data, "수인");
		final Station second = station(data, "수인");
		final Station secret = station(data, "Private member");
		final Platform pa = platform(data, first, new Position(0, 60, 0), new Position(20, 60, 0));
		final Platform pb = platform(data, second, new Position(100, 80, 0), new Position(120, 80, 0));
		final Platform pc = platform(data, secret, new Position(200, 90, 0), new Position(220, 90, 0));
		final Route a = route(data, "Line A", pa);
		final Route b = route(data, "Line B", pb);
		route(data, "Private line", pc);
		final String aId = Long.toUnsignedString(a.getId());
		final String bId = Long.toUnsignedString(b.getId());
		final String firstId = Long.toUnsignedString(first.getId());
		final String secondId = Long.toUnsignedString(second.getId());
		final String secretId = Long.toUnsignedString(secret.getId());
		final String links = "\"interchanges\":[{\"id\":\"suin\",\"name\":\"수인 환승\",\"stationIds\":[\"" + firstId + "\",\"" + secondId + "\",\"" + secretId + "\"]}]";
		final var selected = configuredSelection(data, "{\"routes\":[\"" + aId + "\",\"" + bId + "\"]," + links + "}");
		final JsonObject output = BlueMapSnapshot.infrastructure(data, 100, selected);
		assertEquals(2, output.getAsJsonArray("stations").size());
		for (var element : output.getAsJsonArray("stations")) {
			final JsonObject station = element.getAsJsonObject();
			final boolean isFirst = station.get("id").getAsString().equals(firstId);
			assertEquals("interchange:suin", station.get("interchangeId").getAsString());
			assertEquals("수인 환승", station.get("interchangeName").getAsString());
			assertEquals(1, station.getAsJsonArray("routes").size());
			assertEquals(isFirst ? aId : bId, station.getAsJsonArray("routes").get(0).getAsString());
			assertEquals(1, station.getAsJsonArray("transferStationIds").size());
			assertEquals(isFirst ? secondId : firstId, station.getAsJsonArray("transferStationIds").get(0).getAsString());
			assertEquals(isFirst ? 60 : 80, station.getAsJsonArray("position").get(1).getAsDouble());
		}
		assertFalse(output.toString().contains(secretId));
		assertFalse(output.toString().contains("Private"));
		final JsonArray arrivals = BlueMapSnapshot.filterArrivals(List.of(
				new ArrivalResponse("A", 11000, 11500, 0, true, 0, 0, a, pa),
				new ArrivalResponse("B", 12000, 12500, 0, true, 0, 0, b, pb)
		), Map.of(pa.getId(), pa, pb.getId(), pb), selected, 10000, 1000000);
		assertEquals(firstId, arrivals.get(0).getAsJsonObject().get("stationId").getAsString());
		assertEquals(secondId, arrivals.get(1).getAsJsonObject().get("stationId").getAsString());
		final JsonObject legacy = BlueMapSnapshot.infrastructure(data, 101, selection(data, List.of(a, b), false));
		assertFalse(legacy.toString().contains("interchange")); // Same name alone is not a link.
		final JsonObject revoked = BlueMapSnapshot.infrastructure(data, 102, configuredSelection(data, "{\"routes\":[\"" + aId + "\"]," + links + "}"));
		assertEquals(1, revoked.getAsJsonArray("stations").size());
		assertFalse(revoked.toString().contains("interchange"));
		assertFalse(revoked.toString().contains(secondId));
		final JsonObject catalog = BlueMapSnapshot.routeCatalog(data, "minecraft/overworld");
		assertEquals(3, catalog.getAsJsonArray("stations").size());
		for (var element : catalog.getAsJsonArray("stations")) {
			final JsonObject station = element.getAsJsonObject();
			assertEquals(1, station.getAsJsonArray("routeIds").size());
		}
	}

	private static BlueMapRoutePolicy.WorldSelection configuredSelection(ClientData data, String worldSettings) {
		final String content = "{\"schema\":1,\"operators\":{\"a\":{\"name\":\"Operator A\"},\"b\":{\"name\":\"Operator B\"},\"unused\":{\"name\":\"Unused operator\"}},\"worlds\":{\"minecraft/overworld\":" + worldSettings + "}}";
		return BlueMapRoutePolicy.parse(content, Map.of("minecraft/overworld", BlueMapSnapshot.routeCatalog(data, "minecraft/overworld"))).world("minecraft/overworld");
	}

	private static JsonObject service(String id, String name, boolean showName, Route... routes) {
		final JsonObject service = new JsonObject();
		service.addProperty("id", id);
		service.addProperty("name", name);
		service.addProperty("showName", showName);
		final JsonArray ids = new JsonArray();
		for (Route route : routes) ids.add(Long.toUnsignedString(route.getId()));
		service.add("routeIds", ids);
		return service;
	}

	private static BlueMapRoutePolicy.WorldSelection selection(ClientData data, JsonObject... services) {
		final JsonArray entries = new JsonArray();
		for (JsonObject service : services) entries.add(service);
		final JsonObject group = new JsonObject();
		group.addProperty("id", "line");
		group.addProperty("name", "Combined line");
		group.add("services", entries);
		final JsonArray groups = new JsonArray();
		groups.add(group);
		final JsonObject settings = new JsonObject();
		settings.add("groups", groups);
		final JsonObject worlds = new JsonObject();
		worlds.add("minecraft/overworld", settings);
		final JsonObject policy = new JsonObject();
		policy.addProperty("schema", 1);
		policy.add("worlds", worlds);
		return BlueMapRoutePolicy.parse(policy.toString(), Map.of("minecraft/overworld", BlueMapSnapshot.routeCatalog(data, "minecraft/overworld"))).world("minecraft/overworld");
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
		return selection(data, routes, group, Map.of());
	}

	private static BlueMapRoutePolicy.WorldSelection selection(ClientData data, List<Route> routes, boolean group, Map<Long, String> routeNames) {
		final JsonArray ids = new JsonArray();
		routes.forEach(route -> ids.add(Long.toUnsignedString(route.getId())));
		final JsonObject settings = new JsonObject();
		if (group) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("id", "line");
			entry.addProperty("name", "Combined line");
			entry.addProperty("color", "#123456");
			entry.add("routeIds", ids);
			if (!routeNames.isEmpty()) {
				final JsonObject names = new JsonObject();
				routeNames.forEach((id, name) -> names.addProperty(Long.toUnsignedString(id), name));
				entry.add("routeNames", names);
			}
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
