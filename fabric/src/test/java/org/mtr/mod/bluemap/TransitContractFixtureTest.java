package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Emits real Java producer output for the Go API's cross-language contract test. */
public final class TransitContractFixtureTest {

	@Test
	public void exportsRealPublicSnapshotForApiRoundTrip() throws Exception {
		final long now = System.currentTimeMillis();
		final String worldId = "minecraft/overworld";
		final String publisherId = UUID.randomUUID().toString();
		final String revision = publisherId + ":1";
		final ClientData data = new ClientData();
		final Position a = new Position(0, 64, 0), b = new Position(32, 64, 0);
		final Position c = new Position(168, 64, 0), d = new Position(200, 64, 0);
		final Station firstStation = station(data, "Central West");
		final Station lastStation = station(data, "Central East");
		final Station secretStation = station(data, "PRIVATE_STATION");
		final Platform first = platform(data, firstStation, a, b);
		final Platform last = platform(data, lastStation, c, d);
		final Platform secret = platform(data, secretStation, new Position(1000, 64, 0), new Position(1032, 64, 0));
		final Route publicRoute = route(data, "Source express", first, last);
		final Route privateRoute = route(data, "PRIVATE_ROUTE", first, secret);
		final Depot depot = new Depot(TransportMode.TRAIN, data);
		depot.getPath().addAll(List.of(
				new PathData(rail(a, b), first.getId(), 100, 0, a, b),
				new PathData(rail(b, c), 0, 0, 1, b, c),
				new PathData(rail(c, d), last.getId(), 100, 1, c, d)
		));
		publicRoute.depots.add(depot);
		addVehicle(data, publicRoute, 11, "public-car", a, d);
		addVehicle(data, privateRoute, 12, "PRIVATE_CAR", a, d);

		final String policy = "{\"schema\":1,\"operators\":{\"minefed\":{\"name\":\"Minefed Railway\"}},\"worlds\":{\"" + worldId + "\":{"
				+ "\"groups\":[{\"id\":\"central\",\"name\":\"Central Line\",\"color\":\"#317fce\",\"operatorId\":\"minefed\","
				+ "\"services\":[{\"id\":\"express\",\"name\":\"Express\",\"showName\":false,\"routeIds\":[\"" + Long.toUnsignedString(publicRoute.getId()) + "\"]}]}],"
				+ "\"interchanges\":[{\"id\":\"central\",\"name\":\"Central\",\"stationIds\":[\"" + Long.toUnsignedString(firstStation.getId()) + "\",\"" + Long.toUnsignedString(lastStation.getId()) + "\"]}]}}}";
		final BlueMapRoutePolicy.WorldSelection selected = BlueMapRoutePolicy.parse(policy, Map.of(worldId, BlueMapSnapshot.routeCatalog(data, worldId))).world(worldId);
		final JsonObject vehicles = new BlueMapSnapshot().vehicles(data, now, selected);
		final JsonArray arrivals = BlueMapSnapshot.filterArrivals(List.of(
				new ArrivalResponse("Central East", 11000, 11500, 0, true, 0, 0, publicRoute, first),
				new ArrivalResponse("PRIVATE_DESTINATION", 10500, 10900, 0, true, 0, 0, privateRoute, first)
		), Map.of(first.getId(), first, last.getId(), last), selected, 10000, now);
		vehicles.add("arrivals", arrivals);
		vehicles.addProperty("arrivalsTimestamp", now);
		vehicles.addProperty("publicationRevision", revision);
		final JsonObject infrastructure = BlueMapSnapshot.infrastructure(data, now, selected);
		infrastructure.addProperty("publicationRevision", revision);

		assertEquals(1, vehicles.getAsJsonArray("vehicles").size());
		final JsonObject train = vehicles.getAsJsonArray("vehicles").get(0).getAsJsonObject();
		assertEquals("11", train.get("id").getAsString());
		assertEquals("group:central", train.get("routeId").getAsString());
		assertEquals("service:central:express", train.get("serviceId").getAsString());
		assertFalse(train.get("showServiceName").getAsBoolean());
		assertEquals("minefed", train.get("operatorId").getAsString());
		assertEquals("Minefed Railway", train.get("operatorName").getAsString());
		final JsonObject car = train.getAsJsonArray("cars").get(0).getAsJsonObject();
		assertEquals(3, car.getAsJsonArray("position").size());
		for (String field : List.of("yaw", "pitch", "length", "width")) assertTrue(Double.isFinite(car.get(field).getAsDouble()));
		assertEquals(1, arrivals.size());
		assertEquals(now + 1000, arrivals.get(0).getAsJsonObject().get("arrivalTime").getAsLong());
		assertFalse(arrivals.get(0).getAsJsonObject().get("showServiceName").getAsBoolean());
		assertEquals(1, infrastructure.getAsJsonArray("routes").size());
		assertEquals(3, infrastructure.getAsJsonArray("rails").size());
		assertEquals(2, infrastructure.getAsJsonArray("stations").size());
		for (var element : infrastructure.getAsJsonArray("stations")) {
			final JsonObject station = element.getAsJsonObject();
			assertEquals("interchange:central", station.get("interchangeId").getAsString());
			assertEquals(1, station.getAsJsonArray("transferStationIds").size());
		}

		final JsonObject world = new JsonObject();
		world.addProperty("id", UUID.nameUUIDFromBytes(worldId.getBytes(StandardCharsets.UTF_8)).toString());
		world.addProperty("worldId", worldId);
		world.add("vehicles", vehicles);
		world.add("infrastructure", infrastructure);
		final JsonArray worlds = new JsonArray();
		worlds.add(world);
		final JsonObject catalog = new JsonObject();
		catalog.addProperty("schema", 1);
		catalog.add("vehicles", new JsonObject());
		final JsonObject envelope = new JsonObject();
		envelope.addProperty("schema", 1);
		envelope.addProperty("publisherId", publisherId);
		envelope.addProperty("publisherStartedAt", now);
		envelope.addProperty("sequence", 1);
		envelope.addProperty("publicationRevision", revision);
		envelope.add("worlds", worlds);
		envelope.add("catalog", catalog);
		assertFalse(envelope.toString().contains("PRIVATE_"));
		assertFalse(envelope.toString().contains(Long.toUnsignedString(privateRoute.getId())));
		assertFalse(envelope.toString().contains(Long.toUnsignedString(secretStation.getId())));
		final Path fixture = Path.of("build/transit-contract.json");
		Files.createDirectories(fixture.getParent());
		Files.writeString(fixture, envelope.toString(), StandardCharsets.UTF_8);
	}

	@SuppressWarnings("unchecked")
	private static void addVehicle(ClientData data, Route route, long id, String model, Position a, Position b) throws Exception {
		final Rail rail = rail(a, b);
		final PathData path = new PathData(rail, 0, 0, 0, a, b);
		final JsonObject extra = new JsonObject();
		final JsonArray paths = new JsonArray();
		paths.add(Utilities.getJsonObjectFromData(new PathData(path, 0, rail.railMath.getLength())));
		extra.add("path", paths);
		final JsonArray cars = new JsonArray();
		cars.add(Utilities.getJsonObjectFromData(new VehicleCar(model, 20, 3.2, -7, 7, 0, 0)));
		extra.add("vehicleCars", cars);
		extra.addProperty("railLength", rail.railMath.getLength());
		extra.addProperty("totalVehicleLength", 20);
		extra.addProperty("defaultPosition", 100);
		extra.addProperty("thisRouteId", route.getId());
		extra.addProperty("thisRouteName", route.getName());
		final VehicleExtraData vehicleExtra = new VehicleExtraData(new JsonReader(extra));
		PathData.writePathCache(vehicleExtra.immutablePath, data, TransportMode.TRAIN);
		final Siding siding = new Siding(a, b, rail.railMath.getLength(), TransportMode.TRAIN, data);
		data.sidings.add(siding);
		final Vehicle vehicle = new Vehicle(vehicleExtra, siding, new JsonReader(JsonParser.parseString("{\"id\":" + id + ",\"railProgress\":130,\"speed\":0,\"transportMode\":\"TRAIN\"}").getAsJsonObject()), data);
		assertTrue(vehicle.getIsOnRoute());
		final Field vehicles = Siding.class.getDeclaredField("vehicles");
		vehicles.setAccessible(true);
		((Collection<Vehicle>) vehicles.get(siding)).add(vehicle);
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

	private static Rail rail(Position a, Position b) {
		return Rail.newRail(a, Angle.E, b, Angle.W, Rail.Shape.QUADRATIC, 0, ObjectArrayList.of("default"), 80, 80, false, false, true, false, false, TransportMode.TRAIN);
	}
}
