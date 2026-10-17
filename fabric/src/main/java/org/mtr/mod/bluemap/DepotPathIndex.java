package org.mtr.mod.bluemap;

import org.mtr.core.data.PathData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Built and discarded inside one simulator capture; mutable depot paths are never cached across ticks. */
final class DepotPathIndex {

	private final List<PathData> path;
	private final Map<Long, List<Integer>> starts = new HashMap<>();
	private final Map<Long, Map<Long, List<PathData>>> legs = new HashMap<>();

	DepotPathIndex(List<PathData> path) {
		this.path = path;
		for (int index = 0; index < path.size(); index++) {
			starts.computeIfAbsent(path.get(index).getSavedRailBaseId(), ignored -> new ArrayList<>()).add(index);
		}
	}

	List<PathData> leg(long from, long to) {
		return legs.computeIfAbsent(from, ignored -> new HashMap<>()).computeIfAbsent(to, ignored -> find(from, to));
	}

	private List<PathData> find(long from, long to) {
		for (int start : starts.getOrDefault(from, Collections.emptyList())) {
			for (int end = start + 1; end < path.size(); end++) {
				final long next = path.get(end).getSavedRailBaseId();
				if (next == to) {
					final List<PathData> result = new ArrayList<>(path.subList(start, end + 1));
					for (PathData segment : result) {
						if (segment.getRail() == null || !segment.getRail().isValid() || !Double.isFinite(segment.getRailLength()) || segment.getRailLength() <= 0) {
							return Collections.emptyList();
						}
					}
					return result;
				} else if (next != 0) {
					break;
				}
			}
		}
		return Collections.emptyList();
	}
}
