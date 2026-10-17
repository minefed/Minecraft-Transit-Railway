package org.mtr.mod.bluemap;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class DepotPathIndexTest {

	@Test
	void repeatedMissingAndIntermediateStopsKeepTheOriginalFirstMatch() {
		final Random random = new Random(1704);
		for (int run = 0; run < 100; run++) {
			final List<PathData> path = new ArrayList<>();
			for (int i = 0; i < 50; i++) path.add(segment(i, random.nextInt(8)));
			final DepotPathIndex index = new DepotPathIndex(path);
			for (long from = 0; from <= 8; from++) {
				for (long to = 0; to <= 8; to++) {
					assertEquals(legacy(path, from, to), index.leg(from, to));
				}
			}
		}
	}

	@Test
	void invalidFirstMatchDoesNotFallThroughToALaterValidLeg() throws ReflectiveOperationException {
		final PathData detached = segment(1, 0);
		final var railField = PathData.class.getDeclaredField("rail");
		railField.setAccessible(true);
		railField.set(detached, null);
		final List<PathData> path = List.of(segment(0, 1), detached, segment(2, 2), segment(3, 1), segment(4, 2));
		assertTrue(legacy(path, 1, 2).isEmpty());
		assertTrue(new DepotPathIndex(path).leg(1, 2).isEmpty());
	}

	@Test
	void rebuiltCaptureSeesPathReplacementAndReordering() {
		final List<PathData> path = new ArrayList<>(List.of(segment(0, 1), segment(1, 0), segment(2, 2)));
		assertEquals(path, new DepotPathIndex(path).leg(1, 2));
		Collections.reverse(path);
		assertTrue(new DepotPathIndex(path).leg(1, 2).isEmpty());
		assertEquals(path, new DepotPathIndex(path).leg(2, 1));
		path.clear();
		assertTrue(new DepotPathIndex(path).leg(2, 1).isEmpty());
	}

	@Test
	void sharedLongDepotPathsAreIndexedOncePerCapture() {
		final CountingPath path = new CountingPath();
		for (int stop = 1; stop <= 250; stop++) {
			path.add(segment(stop * 2, stop));
			path.add(segment(stop * 2 + 1, 0));
		}
		for (int route = 0; route < 4; route++) for (int stop = 1; stop < 250; stop++) legacy(path, stop, stop + 1);
		final int oldReads = path.reads;
		path.reads = 0;
		final DepotPathIndex index = new DepotPathIndex(path);
		for (int route = 0; route < 4; route++) for (int stop = 1; stop < 250; stop++) assertEquals(3, index.leg(stop, stop + 1).size());
		assertTrue(path.reads < oldReads / 20);
		System.out.printf("Depot path kernel: 500 segments, 250 stops, 4 overlapping routes; old indexed reads=%d, new=%d%n", oldReads, path.reads);
	}

	private static PathData segment(int index, long stop) {
		final Position start = new Position(index * 16, 64, 0);
		final Position end = new Position(index * 16 + 16, 64, 0);
		final Rail rail = Rail.newRail(start, Angle.E, end, Angle.W, Rail.Shape.QUADRATIC, 0,
				ObjectArrayList.of("default"), 80, 80, false, false, true, false, false, TransportMode.TRAIN);
		return new PathData(rail, stop, 100, index * 16, start, end);
	}

	private static List<PathData> legacy(List<PathData> path, long from, long to) {
		for (int start = 0; start < path.size(); start++) {
			if (path.get(start).getSavedRailBaseId() != from) continue;
			for (int end = start + 1; end < path.size(); end++) {
				if (path.get(end).getSavedRailBaseId() == to) {
					final List<PathData> result = new ArrayList<>(path.subList(start, end + 1));
					for (PathData segment : result) {
						if (segment.getRail() == null || !segment.getRail().isValid() || !Double.isFinite(segment.getRailLength()) || segment.getRailLength() <= 0) return Collections.emptyList();
					}
					return result;
				} else if (path.get(end).getSavedRailBaseId() != 0) break;
			}
		}
		return Collections.emptyList();
	}

	private static final class CountingPath extends ArrayList<PathData> {
		private int reads;
		@Override public PathData get(int index) { reads++; return super.get(index); }
	}
}
