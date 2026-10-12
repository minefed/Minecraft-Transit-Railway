package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.*;
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
import java.util.List;

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
		final JsonObject result = BlueMapSnapshot.infrastructure(data, 50);
		assertEquals(2, result.getAsJsonArray("routes").size());
		final JsonObject exported = result.getAsJsonArray("stations").get(0).getAsJsonObject();
		assertEquals(2, exported.getAsJsonArray("routes").size());
		assertEquals(73, exported.getAsJsonArray("position").get(1).getAsDouble());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void realCoreVehiclesExportWithoutAnyClientsIncludingParkedAndMultipleCars() throws Exception {
		final ClientData data = new ClientData();
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
		final VehicleExtraData extra = new VehicleExtraData(new JsonReader(extraJson));
		PathData.writePathCache(extra.immutablePath, data, TransportMode.TRAIN);
		final Field field = Siding.class.getDeclaredField("vehicles");
		field.setAccessible(true);
		for (int i = 0; i < 2; i++) {
			final Siding siding = new Siding(a, b, rail.railMath.getLength(), TransportMode.TRAIN, data);
			data.sidings.add(siding);
			final Vehicle vehicle = new Vehicle(extra, siding, reader("{\"id\":" + (i + 1) + ",\"railProgress\":" + (i == 0 ? 80 : 130) + ",\"transportMode\":\"TRAIN\"}"), data);
			assertEquals(i == 1, vehicle.getIsOnRoute());
			((Collection<Vehicle>) field.get(siding)).add(vehicle);
		}
		final JsonArray result = new BlueMapSnapshot().vehicles(data, 1234).getAsJsonArray("vehicles");
		assertEquals(2, result.size());
		assertEquals(2, result.get(0).getAsJsonObject().getAsJsonArray("cars").size());
		assertEquals(24, result.get(1).getAsJsonObject().getAsJsonArray("cars").get(1).getAsJsonObject().get("length").getAsDouble());
	}

	@Test
	public void publishedJsonReplacementIsCompleteAndLeavesNoTemporaryFile() throws Exception {
		final Path file = directory.resolve("mtr-live/vehicles.json");
		BlueMapLiveBridge.atomicWrite(file, "{\"vehicles\":[1,2,3]}");
		BlueMapLiveBridge.atomicWrite(file, "{\"vehicles\":[]}");
		assertEquals(0, JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("vehicles").size());
		assertFalse(Files.exists(file.resolveSibling("vehicles.json.tmp")));
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
