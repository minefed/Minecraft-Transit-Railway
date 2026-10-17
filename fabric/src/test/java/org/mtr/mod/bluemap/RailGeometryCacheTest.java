package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.com.google.gson.JsonArray;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RailGeometryCacheTest {

	@Test
	void pinnedCoreGeometryIsImmutableAndEverySamplingModeMatchesLegacyBytes() {
		Arrays.stream(RailMath.class.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers()))
				.forEach(field -> assertTrue(Modifier.isFinal(field.getModifiers()), field.getName()));
		for (Rail.Shape shape : Rail.Shape.values()) {
			for (long x : new long[]{-192, 0, 128}) {
				final RailMath math = new RailMath(new Position(x, 64, -11), Angle.E, new Position(x + 128, 80, 53), Angle.S, shape, 0);
				final RailGeometryCache cache = new RailGeometryCache();
				cache.beginCapture();
				assertEquals(legacy(math, 0, 1, true).toString(), cache.rail(math).toString());
				for (double[] span : new double[][]{{0, 1}, {0, 0.5}, {0.5, 1}, {0.5, 0.5}}) {
					assertEquals(legacy(math, span[0], span[1], false).toString(), cache.span(math, span[0], span[1]).toString());
				}
				cache.endCapture();
			}
		}
	}

	@Test
	void replacementPruningAndBudgetNeverMutatePublishedPoints() {
		final CountingMath first = new CountingMath(0, 80);
		final RailGeometryCache cache = new RailGeometryCache(500);
		cache.beginCapture();
		final JsonArray published = cache.rail(first);
		final String original = published.toString();
		final int sampled = first.calls;
		assertSame(published, cache.rail(first));
		assertEquals(sampled, first.calls);
		cache.endCapture();
		cache.beginCapture();
		assertSame(published, cache.rail(first));
		cache.endCapture();

		// Same endpoints/rail ID can be replaced by a new vertical shape/radius.
		final CountingMath replacement = new CountingMath(0, 80, Rail.Shape.CABLE);
		cache.beginCapture();
		assertNotEquals(original, cache.rail(replacement).toString());
		cache.endCapture();
		cache.beginCapture();
		assertNotSame(published, cache.rail(first)); // No longer present in the previous capture.
		cache.endCapture();
		assertEquals(original, published.toString());

		final RailGeometryCache limited = new RailGeometryCache(2);
		limited.beginCapture();
		assertNotSame(limited.rail(first), limited.rail(first)); // Oversized curves are not retained.
		limited.endCapture();
		assertEquals(original, published.toString());
		final RailGeometryCache oneCurve = new RailGeometryCache(published.size());
		oneCurve.beginCapture();
		final JsonArray evicted = oneCurve.rail(first);
		oneCurve.rail(new CountingMath(1000, 80));
		assertNotSame(evicted, oneCurve.rail(first));
		oneCurve.endCapture();
		assertEquals(original, evicted.toString());
	}

	@Test
	void sharedRoutesAndUnchangedCapturesAvoidRepeatedSampling() {
		final List<CountingMath> rails = new ArrayList<>();
		for (int i = 0; i < 48; i++) rails.add(new CountingMath(i * 160, 64 + i % 24));
		final RailGeometryCache cache = new RailGeometryCache();
		reset(rails);
		long baselinePoints = capture(rails, null);
		final int baselineCalls = calls(rails);
		reset(rails);
		assertEquals(baselinePoints, capture(rails, cache));
		final int coldCalls = calls(rails);
		reset(rails);
		assertEquals(baselinePoints, capture(rails, cache));
		assertEquals(0, calls(rails));
		assertTrue(coldCalls < baselineCalls); // Four routes share the same immutable curves.

		for (int i = 0; i < 10; i++) { capture(rails, null); capture(rails, cache); }
		final long[] baseline = new long[21];
		final long[] warm = new long[21];
		for (int i = 0; i < baseline.length; i++) {
			long start = System.nanoTime();
			assertEquals(baselinePoints, capture(rails, null));
			baseline[i] = System.nanoTime() - start;
			start = System.nanoTime();
			assertEquals(baselinePoints, capture(rails, cache));
			warm[i] = System.nanoTime() - start;
		}
		Arrays.sort(baseline);
		Arrays.sort(warm);
		System.out.printf("Geometry kernel: 48 curves, 4 overlapping routes; points=%d, old calls=%d, cold calls=%d, warm calls=0; median old=%.3fms warm=%.3fms%n",
				baselinePoints, baselineCalls, coldCalls, baseline[10] / 1E6, warm[10] / 1E6);
	}

	private static long capture(List<CountingMath> rails, RailGeometryCache cache) {
		if (cache != null) cache.beginCapture();
		long points = 0;
		for (CountingMath math : rails) {
			points += (cache == null ? legacy(math, 0, 1, true) : cache.rail(math)).size();
			for (int route = 0; route < 4; route++) points += (cache == null ? legacy(math, 0.5, 1, false) : cache.span(math, 0.5, 1)).size();
		}
		if (cache != null) cache.endCapture();
		return points;
	}

	/** Independent copy of both original loops, including their different floating-point expressions. */
	private static JsonArray legacy(RailMath math, double start, double end, boolean rail) {
		final double length = math.getLength();
		final JsonArray result = new JsonArray();
		if (rail && (!Double.isFinite(length) || length <= 0)) return result;
		final int steps = rail ? (int) Math.ceil(length) : Math.max(1, (int) Math.ceil(length * (end - start)));
		for (int step = 0; step <= steps; step++) {
			final Vector vector = math.getPosition(rail ? length * step / steps : length * (start + (end - start) * step / steps), false);
			if (rail && !(Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z))) continue;
			final JsonArray point = new JsonArray();
			point.add(vector.x);
			point.add(vector.y);
			point.add(vector.z);
			result.add(point);
		}
		return result;
	}

	private static int calls(List<CountingMath> rails) { return rails.stream().mapToInt(math -> math.calls).sum(); }
	private static void reset(List<CountingMath> rails) { rails.forEach(math -> math.calls = 0); }

	private static final class CountingMath extends RailMath {
		private int calls;
		private CountingMath(long x, long y) {
			this(x, y, Rail.Shape.QUADRATIC);
		}
		private CountingMath(long x, long y, Rail.Shape shape) {
			super(new Position(x, 64, 0), Angle.E, new Position(x + 128, y, 64), Angle.S, shape, 0);
		}
		@Override
		public Vector getPosition(double distance, boolean reverse) {
			calls++;
			return super.getPosition(distance, reverse);
		}
	}
}
