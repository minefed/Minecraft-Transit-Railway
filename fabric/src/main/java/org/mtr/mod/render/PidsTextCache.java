package org.mtr.mod.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Caches only string parsing; time, language selection and formatting stay dynamic. */
final class PidsTextCache {

	private static final int MAX_ENTRIES = 4096;
	private static final int MAX_CACHED_TEXT_LENGTH = 4096;
	private final Map<String, String[]> splitCache = createCache();
	private final Map<DestinationKey, String[]> destinationCache = createCache();

	String[] split(String text) {
		if (text.length() > MAX_CACHED_TEXT_LENGTH) {
			return text.split("\\|");
		}
		return splitCache.computeIfAbsent(text, key -> key.split("\\|"));
	}

	String[] getDestinations(String routeNumber, String destination) {
		if (routeNumber.isEmpty()) {
			return split(destination);
		}
		final DestinationKey key = new DestinationKey(routeNumber, destination);
		final String[] cached = destinationCache.get(key);
		if (cached != null) {
			return cached;
		}
		final String[] numbers = split(routeNumber);
		final String[] destinations = split(destination);
		final ArrayList<String> combined = new ArrayList<>();
		final HashSet<String> added = new HashSet<>();
		int index = 0;
		int textLength = 0;
		while (true) {
			final String text = numbers[index % numbers.length] + " " + destinations[index % destinations.length];
			if (!added.add(text)) {
				break;
			}
			combined.add(text);
			textLength = (int) Math.min(MAX_CACHED_TEXT_LENGTH + 1L, (long) textLength + text.length());
			index++;
		}
		final String[] result = combined.toArray(new String[0]);
		if ((long) routeNumber.length() + destination.length() <= MAX_CACHED_TEXT_LENGTH && textLength <= MAX_CACHED_TEXT_LENGTH) {
			destinationCache.put(key, result);
		}
		return result;
	}

	void clear() {
		splitCache.clear();
		destinationCache.clear();
	}

	private static <K> Map<K, String[]> createCache() {
		return new LinkedHashMap<K, String[]>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, String[]> eldest) {
				return size() > MAX_ENTRIES;
			}
		};
	}

	private static final class DestinationKey {
		private final String routeNumber;
		private final String destination;

		private DestinationKey(String routeNumber, String destination) {
			this.routeNumber = routeNumber;
			this.destination = destination;
		}

		@Override
		public boolean equals(Object object) {
			if (!(object instanceof DestinationKey)) {
				return false;
			}
			final DestinationKey key = (DestinationKey) object;
			return routeNumber.equals(key.routeNumber) && destination.equals(key.destination);
		}

		@Override
		public int hashCode() {
			return 31 * routeNumber.hashCode() + destination.hashCode();
		}
	}
}
