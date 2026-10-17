package org.mtr.mod.bluemap;

import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Simulator-thread cache of immutable sampled curves. The pinned Core's RailMath
 * contains only final fields; a geometry edit replaces that object, even if the
 * rail ID is unchanged. Published arrays are never modified, including on eviction.
 */
final class RailGeometryCache {

	private static final int MAX_POINTS = 131072;
	private static final int MAX_ENTRIES = 4096;
	private final int pointLimit;
	private final Map<Key, JsonArray> entries = new LinkedHashMap<>(16, 0.75F, true);
	private final Set<Key> used = new HashSet<>();
	private int retainedPoints;

	RailGeometryCache() {
		this(MAX_POINTS);
	}

	RailGeometryCache(int pointLimit) {
		this.pointLimit = pointLimit;
	}

	void beginCapture() {
		used.clear();
	}

	void endCapture() {
		final Iterator<Map.Entry<Key, JsonArray>> iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Key, JsonArray> entry = iterator.next();
			if (!used.contains(entry.getKey())) {
				retainedPoints -= entry.getValue().size();
				iterator.remove();
			}
		}
		used.clear();
	}

	JsonArray rail(RailMath math) {
		return points(new Key(math, 0, 1, true));
	}

	JsonArray span(RailMath math, double start, double end) {
		return points(new Key(math, start, end, false));
	}

	private JsonArray points(Key key) {
		used.add(key);
		final JsonArray existing = entries.get(key);
		if (existing != null) return existing;
		final JsonArray points = sample(key);
		if (points.size() <= pointLimit) {
			final Iterator<Map.Entry<Key, JsonArray>> iterator = entries.entrySet().iterator();
			while (iterator.hasNext() && (retainedPoints + points.size() > pointLimit || entries.size() >= MAX_ENTRIES)) {
				retainedPoints -= iterator.next().getValue().size();
				iterator.remove();
			}
			entries.put(key, points);
			retainedPoints += points.size();
		}
		return points;
	}

	private static JsonArray sample(Key key) {
		final JsonArray points = new JsonArray();
		final double length = key.math.getLength();
		if (key.rail && (!Double.isFinite(length) || length <= 0)) return points;
		final int steps = key.rail ? (int) Math.ceil(length) : Math.max(1, (int) Math.ceil(length * (key.end - key.start)));
		for (int step = 0; step <= steps; step++) {
			// Preserve both old expressions exactly: reassociation changes floating-point JSON.
			final double distance = key.rail ? length * step / steps : length * (key.start + (key.end - key.start) * step / steps);
			final Vector position = key.math.getPosition(distance, false);
			if (!key.rail || Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)) {
				final JsonArray point = new JsonArray();
				point.add(position.x);
				point.add(position.y);
				point.add(position.z);
				points.add(point);
			}
		}
		return points;
	}

	private static final class Key {
		private final RailMath math;
		private final double start;
		private final double end;
		private final boolean rail;

		private Key(RailMath math, double start, double end, boolean rail) {
			this.math = math;
			this.start = start;
			this.end = end;
			this.rail = rail;
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof Key)) return false;
			final Key key = (Key) other;
			return math == key.math && Double.doubleToLongBits(start) == Double.doubleToLongBits(key.start)
					&& Double.doubleToLongBits(end) == Double.doubleToLongBits(key.end) && rail == key.rail;
		}

		@Override
		public int hashCode() {
			return 31 * (31 * (31 * System.identityHashCode(math) + Double.hashCode(start)) + Double.hashCode(end)) + (rail ? 1 : 0);
		}
	}
}
