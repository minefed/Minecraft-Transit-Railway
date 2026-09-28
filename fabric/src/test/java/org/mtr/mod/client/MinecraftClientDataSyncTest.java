package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Lift;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class MinecraftClientDataSyncTest {

	@Test
	public void dynamicSyncMatchesFullSyncForLiftReplacementAndRemoval() {
		final MinecraftClientData data = new MinecraftClientData();
		data.simplifiedRoutes.add(new SimplifiedRoute(reader("{\"id\":500,\"name\":\"unchanged\"}")));
		final Position start = new Position(0, 64, 0);
		final Position end = new Position(100, 64, 0);
		final Rail rail = Rail.newRail(start, Angle.E, end, Angle.W, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 80, false, false, true, false, false, TransportMode.TRAIN);
		data.rails.add(rail);
		final Lift first = lift(1, "first", data);
		final Lift second = lift(2, "second", data);
		data.lifts.add(first);
		data.lifts.add(second);
		data.sync();
		final MinecraftClientData.RailWrapper railWrapper = data.railWrapperList.get(rail.getHexId());
		railWrapper.shouldRender = true;
		final MinecraftClientData.LiftWrapper retainedWrapper = data.liftWrapperList.get(2);
		retainedWrapper.shouldRender = true;
		final Lift replacement = lift(2, "replacement", data);
		data.lifts.clear();
		data.lifts.add(replacement);
		data.lifts.add(lift(3, "new", data));
		data.vehicleIdToPersistentVehicleData.put(99, null);
		data.syncVehiclesAndLifts();
		assertFalse(data.liftIdMap.containsKey(1));
		assertFalse(data.liftWrapperList.containsKey(1));
		assertSame(replacement, data.liftIdMap.get(2));
		assertSame(retainedWrapper, data.liftWrapperList.get(2));
		assertSame(replacement, retainedWrapper.getLift());
		assertTrue(retainedWrapper.shouldRender);
		assertTrue(data.vehicleIdToPersistentVehicleData.isEmpty());
		assertEquals(500, data.simplifiedRouteIds.getLong(0));
		assertSame(rail, data.positionsToRail.get(start).get(end));
		assertSame(railWrapper, data.railWrapperList.get(rail.getHexId()));
		assertTrue(railWrapper.shouldRender);

		final String beforeFullSync = describeLifts(data);
		data.sync();
		assertEquals(beforeFullSync, describeLifts(data));
		assertSame(retainedWrapper, data.liftWrapperList.get(2));
		assertSame(rail, data.positionsToRail.get(start).get(end));
		assertSame(railWrapper, data.railWrapperList.get(rail.getHexId()));
	}

	@Test
	public void cachedStationLookupMatchesStationScanAcrossSyncs() {
		final Random random = new Random(5);
		final MinecraftClientData data = new MinecraftClientData();
		for (int i = 0; i < 30; i++) {
			data.stations.add(station(random, data));
		}
		data.sync();
		int nonNull = 0;
		for (int step = 0; step < 100_000; step++) {
			final int action = random.nextInt(200);
			if (action == 0 && !data.stations.isEmpty()) {
				final Station station = data.stations.stream().skip(random.nextInt(data.stations.size())).findFirst().orElseThrow();
				station.setCorners(randomPosition(random, -250), randomPosition(random, 250));
				data.sync();
			} else if (action == 1) {
				data.stations.add(station(random, data));
				data.sync();
			} else if (action == 2 && !data.stations.isEmpty()) {
				data.stations.remove(data.stations.stream().skip(random.nextInt(data.stations.size())).findFirst().orElseThrow());
				data.sync();
			} else {
				final int x = random.nextInt(10) == 0 ? random.nextInt() : random.nextInt(400) - 200;
				final int y = random.nextInt(10) == 0 ? random.nextInt() : random.nextInt(400) - 200;
				final int z = random.nextInt(400) - 200;
				final Position position = new Position(x, y, z);
				final Station expected = data.stations.stream().filter(station -> station.inArea(position)).findFirst().orElse(null);
				assertSame(expected, data.findStation(x, y, z), "step " + step);
				nonNull += expected == null ? 0 : 1;
			}
		}
		assertTrue(nonNull > 10_000, "The fixture must contain stations: " + nonNull);
	}

	@Test
	public void publicCollectionFieldDescriptorsRemainCompatible() throws Exception {
		assertEquals(Object2ObjectArrayMap.class, MinecraftClientData.class.getField("railWrapperList").getType());
		assertEquals(ObjectArraySet.class, MinecraftClientData.class.getField("blockedRailIds").getType());
	}

	private static Station station(Random random, MinecraftClientData data) {
		final Station station = new Station(data);
		station.setCorners(randomPosition(random, -250), randomPosition(random, 250));
		return station;
	}

	private static Position randomPosition(Random random, long y) {
		return new Position(random.nextInt(400) - 200, y, random.nextInt(400) - 200);
	}

	private static Lift lift(long id, String name, MinecraftClientData data) {
		return new Lift(reader("{\"id\":" + id + ",\"name\":\"" + name + "\"}"), data);
	}

	private static JsonReader reader(String json) {
		return new JsonReader(Utilities.parseJson(json));
	}

	private static String describeLifts(MinecraftClientData data) {
		final StringBuilder result = new StringBuilder();
		data.lifts.forEach(lift -> result.append(lift.getId()).append(':').append(data.liftIdMap.get(lift.getId()).getName()).append(':').append(data.liftWrapperList.get(lift.getId()).getLift().getName()).append(';'));
		return result.toString();
	}
}
