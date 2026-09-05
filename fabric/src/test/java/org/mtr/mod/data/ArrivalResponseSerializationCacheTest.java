package org.mtr.mod.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import static org.junit.jupiter.api.Assertions.*;

public final class ArrivalResponseSerializationCacheTest {

	@Test
	public void reusesExactContentAndInvalidatesMutableCarDetails() {
		final ArrivalResponseSerializationCache cache = new ArrivalResponseSerializationCache();
		final ArrivalResponse arrival = new ArrivalResponse(reader("{\"platformId\":42,\"destination\":\"\uC11C\uC6B8 | Seoul\",\"arrival\":9007199254740993,\"cars\":[{\"vehicleId\":\"a\",\"occupancy\":0.25}]}"));
		final String first = cache.get(arrival);
		assertEquals(serialize(arrival), first);
		assertSame(first, cache.get(arrival));

		// The same response instance can legally replace its cars through ReaderBase.
		arrival.updateData(reader("{\"cars\":[{\"vehicleId\":\"b\",\"occupancy\":0.75}]}"));
		final String changed = cache.get(arrival);
		assertEquals(serialize(arrival), changed);
		assertNotEquals(first, changed);
		assertSame(changed, cache.get(arrival));

		arrival.updateData(reader("{\"cars\":[]}"));
		assertEquals(serialize(arrival), cache.get(arrival));
	}

	@Test
	public void snapshotClearAndDifferentResponseObjectsDoNotReuseStaleStrings() {
		final ArrivalResponseSerializationCache cache = new ArrivalResponseSerializationCache();
		final ArrivalResponse firstArrival = new ArrivalResponse(reader("{\"platformId\":7,\"arrival\":1000}"));
		final ArrivalResponse secondArrival = new ArrivalResponse(reader("{\"platformId\":7,\"arrival\":2000}"));
		final String first = cache.get(firstArrival);
		assertNotEquals(first, cache.get(secondArrival));
		cache.clear();
		final String refreshed = cache.get(firstArrival);
		assertEquals(first, refreshed);
		assertNotSame(first, refreshed);
	}

	private static JsonReader reader(String json) {
		return new JsonReader(Utilities.parseJson(json));
	}

	private static String serialize(ArrivalResponse arrival) {
		return Utilities.getJsonObjectFromData(arrival).toString();
	}
}
