package org.mtr.mod.packet;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.NameColorDataBase;
import org.mtr.core.data.Station;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.util.Arrays;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

public final class VehicleLiftUpdateTest {

	@Test
	public void keepsObjectIdentityAndDisposesOnlyActuallyRemovedIds() {
		final Station keep = station(1, "keep");
		final Station old = station(2, "old");
		final Station remove = station(3, "remove");
		final Station replacement = station(2, "replacement");
		final Station added = station(4, "added");
		final ObjectArraySet<Station> data = new ObjectArraySet<>(Arrays.asList(keep, old, remove));
		final ObjectArrayList<Station> removed = new ObjectArrayList<>();
		assertTrue(PacketUpdateVehiclesLifts.<Station, Station>updateVehiclesOrLifts(data, consumer -> consumer.accept(1), consumer -> {
			consumer.accept(replacement);
			consumer.accept(added);
		}, removed::add, NameColorDataBase::getId, item -> item));
		assertArrayEquals(new Object[]{keep, replacement, added}, data.toArray());
		assertArrayEquals(new Object[]{remove}, removed.toArray());
		assertSame(keep, data.iterator().next());
	}

	@Test
	public void keepOnlyIsUnchangedAndEmptyKeepRemovesEverything() {
		final ObjectArraySet<Station> data = new ObjectArraySet<>(Arrays.asList(station(5, "a"), station(6, "b")));
		final ObjectArrayList<Station> removed = new ObjectArrayList<>();
		assertFalse(PacketUpdateVehiclesLifts.<Station, Station>updateVehiclesOrLifts(data, (LongConsumer consumer) -> {
			consumer.accept(5);
			consumer.accept(6);
		}, consumer -> {}, removed::add, NameColorDataBase::getId, item -> item));
		assertTrue(removed.isEmpty());
		assertTrue(PacketUpdateVehiclesLifts.<Station, Station>updateVehiclesOrLifts(data, consumer -> {}, consumer -> {}, removed::add, NameColorDataBase::getId, item -> item));
		assertTrue(data.isEmpty());
		assertEquals(2, removed.size());
	}

	private static Station station(long id, String name) {
		return new Station(new JsonReader(Utilities.parseJson("{\"id\":" + id + ",\"name\":\"" + name + "\"}")), new ClientData());
	}
}
