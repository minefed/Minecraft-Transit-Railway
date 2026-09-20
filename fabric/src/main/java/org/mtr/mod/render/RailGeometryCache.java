package org.mtr.mod.render;

import org.mtr.core.data.RailMath;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread LRU, bounded by both placements and rail/style combinations. */
final class RailGeometryCache {

	private final int maxSegments;
	private final int maxEntries;
	private final Map<Key, RailModelGeometry> entries = new LinkedHashMap<>(16, 0.75F, true);
	private int segmentCount;

	RailGeometryCache(int maxSegments, int maxEntries) {
		this.maxSegments = maxSegments;
		this.maxEntries = maxEntries;
	}

	@Nullable
	RailModelGeometry get(RailMath math, double interval, double yOffset, boolean flip) {
		// Pathological resources/very long rails keep the streaming renderer instead of
		// creating an unbounded array. The caller excludes one-frame ghost rails.
		if (!Double.isFinite(interval) || interval <= 0 || !Double.isFinite(math.getLength()) || Math.ceil(math.getLength() / interval) + 2 > maxSegments) {
			return null;
		}
		final Key key = new Key(math, interval, yOffset, flip);
		RailModelGeometry result = entries.get(key);
		if (result == null) {
			result = new RailModelGeometry(math, interval, yOffset, flip);
			if (result.segments.length > maxSegments) {
				return null;
			}
			final Iterator<RailModelGeometry> iterator = entries.values().iterator();
			while (iterator.hasNext() && (segmentCount + result.segments.length > maxSegments || entries.size() >= maxEntries)) {
				segmentCount -= iterator.next().segments.length;
				iterator.remove();
			}
			entries.put(key, result);
			segmentCount += result.segments.length;
		}
		return result;
	}

	void clear() {
		entries.clear();
		segmentCount = 0;
	}

	int segmentCount() {
		return segmentCount;
	}

	private static final class Key {
		private final RailMath math;
		private final double interval, yOffset;
		private final boolean flip;

		private Key(RailMath math, double interval, double yOffset, boolean flip) {
			this.math = math;
			this.interval = interval;
			this.yOffset = yOffset;
			this.flip = flip;
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof Key)) {
				return false;
			}
			final Key key = (Key) other;
			return math == key.math && Double.compare(interval, key.interval) == 0 && Double.compare(yOffset, key.yOffset) == 0 && flip == key.flip;
		}

		@Override
		public int hashCode() {
			return 31 * (31 * (31 * System.identityHashCode(math) + Double.hashCode(interval)) + Double.hashCode(yOffset)) + (flip ? 1 : 0);
		}
	}
}
