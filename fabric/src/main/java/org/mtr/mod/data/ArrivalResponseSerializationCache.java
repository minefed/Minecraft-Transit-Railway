package org.mtr.mod.data;

import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.operation.CarDetails;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.IdentityHashMap;
import java.util.Map;

/** Serialized strings belong to one arrivals snapshot, never to a time-based expiry. */
final class ArrivalResponseSerializationCache {

	private final Map<ArrivalResponse, Entry> entries = new IdentityHashMap<>();

	String get(ArrivalResponse arrival) {
		// ArrivalResponse's scalar fields and CarDetails are immutable. Its cars
		// list can be replaced by updateData or appended by setCarDetails.
		final ObjectArrayList<CarDetails> cars = new ObjectArrayList<>();
		arrival.iterateCarDetails(cars::add);
		final Entry previous = entries.get(arrival);
		if (previous != null && previous.cars.equals(cars)) {
			return previous.content;
		}
		final String content = Utilities.getJsonObjectFromData(arrival).toString();
		entries.put(arrival, new Entry(cars, content));
		return content;
	}

	void clear() {
		entries.clear();
	}

	private static final class Entry {

		private final ObjectArrayList<CarDetails> cars;
		private final String content;

		private Entry(ObjectArrayList<CarDetails> cars, String content) {
			this.cars = cars;
			this.content = content;
		}
	}
}
