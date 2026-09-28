package org.mtr.mod.render;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-thread LRU for the platform/door block scan around a doorway.
 * <p>
 * A key is the exact list of cells visited by the original {@code double} loops. An entry
 * is reused only while every chunk column it read is the same chunk object with the same
 * door-scan epoch. A client-only chunk mixin bumps that epoch whenever a platform or
 * platform door block is placed, removed or changed, and whenever a chunk is reloaded
 * from a packet. Unloaded, replaced or recentered chunks resolve to a different object.
 * Block entities are not cached; callers look them up again on every use.
 */
public final class DoorScanCache<P> {

	public static final int OTHER = 0;
	public static final int PLATFORM = 1;
	public static final int UNLOCKED_DOOR = 2;
	/** Returned by {@link BlockSource#getChunkEpoch(Object)} when a chunk cannot be tracked. */
	public static final long UNTRACKED = Long.MIN_VALUE;
	static final int MAX_CELLS_PER_AXIS = 64;

	private final int maxEntries;
	private final Map<Key, CachedScan<P>> entries;
	private final Key probe = new Key(new int[MAX_CELLS_PER_AXIS], new int[MAX_CELLS_PER_AXIS], new int[MAX_CELLS_PER_AXIS]);

	public DoorScanCache(int maxEntries) {
		this.maxEntries = maxEntries;
		entries = new LinkedHashMap<Key, CachedScan<P>>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Key, CachedScan<P>> eldest) {
				return size() > DoorScanCache.this.maxEntries;
			}
		};
	}

	/**
	 * Scans the same cells as {@code for (double c = min; c <= max; c++)} on each axis, X outermost and Z innermost.
	 *
	 * @return the scan result, or {@code null} if the range is too large to cache and the caller must scan directly
	 */
	@Nullable
	public Result<P> get(double minX, double maxX, double minY, double maxY, double minZ, double maxZ, BlockSource<P> blockSource) {
		final Key key = probe;
		key.countX = cells(minX, maxX, key.xs);
		key.countY = cells(minY, maxY, key.ys);
		key.countZ = cells(minZ, maxZ, key.zs);
		if (key.countX < 0 || key.countY < 0 || key.countZ < 0) {
			return null;
		}
		key.updateHash();

		final CachedScan<P> cachedEntry = entries.get(key);
		if (cachedEntry != null && cachedEntry.isValid(blockSource)) {
			return cachedEntry.result;
		}

		final Key storedKey = key.copy();
		final CachedScan<P> entry = new CachedScan<>(storedKey, blockSource);
		final Result<P> result = scan(storedKey, blockSource);
		entry.result = result;
		if (entry.tracked) {
			entries.put(storedKey, entry);
		} else if (cachedEntry != null) {
			entries.remove(storedKey);
		}
		return result;
	}

	public void clear() {
		entries.clear();
	}

	int size() {
		return entries.size();
	}

	/**
	 * Writes the floored cells that the original loop visits.
	 *
	 * @return the number of cells, or {@code -1} if more than {@link #MAX_CELLS_PER_AXIS} would be visited
	 */
	static int cells(double min, double max, int[] cells) {
		int count = 0;
		for (double value = min; value <= max; value++) {
			if (count == MAX_CELLS_PER_AXIS) {
				return -1;
			}
			cells[count++] = floor(value);
		}
		return count;
	}

	/** Same as Minecraft's {@code MathHelper.floor(double)}, used by {@code Init.newBlockPos}. */
	static int floor(double value) {
		final int intValue = (int) value;
		return value < intValue ? intValue - 1 : intValue;
	}

	private static <P> Result<P> scan(Key key, BlockSource<P> blockSource) {
		boolean canOpenDoors = false;
		List<P> unlockedDoors = null;
		for (int i = 0; i < key.countX; i++) {
			for (int j = 0; j < key.countY; j++) {
				for (int k = 0; k < key.countZ; k++) {
					final P position = blockSource.createPosition(key.xs[i], key.ys[j], key.zs[k]);
					final int blockType = blockSource.getBlockType(position);
					if (blockType == PLATFORM) {
						canOpenDoors = true;
					} else if (blockType == UNLOCKED_DOOR) {
						canOpenDoors = true;
						if (unlockedDoors == null) {
							unlockedDoors = new ArrayList<>();
						}
						unlockedDoors.add(position);
					}
				}
			}
		}
		return new Result<>(canOpenDoors, unlockedDoors == null ? Collections.emptyList() : Collections.unmodifiableList(unlockedDoors));
	}

	public interface BlockSource<P> {

		P createPosition(int x, int y, int z);

		/**
		 * @return {@link #PLATFORM}, {@link #UNLOCKED_DOOR} or {@link #OTHER}
		 */
		int getBlockType(P position);

		/**
		 * @return the chunk object that currently supplies block states for this column
		 */
		@Nullable
		Object getChunk(int chunkX, int chunkZ);

		/**
		 * @return the chunk's door-scan epoch, or {@link #UNTRACKED}
		 */
		long getChunkEpoch(Object chunk);
	}

	public static final class Result<P> {

		public final boolean canOpenDoors;
		/** Unlocked platform screen door and automatic platform gate cells in visiting order. */
		public final List<P> unlockedDoors;

		private Result(boolean canOpenDoors, List<P> unlockedDoors) {
			this.canOpenDoors = canOpenDoors;
			this.unlockedDoors = unlockedDoors;
		}
	}

	private static final class CachedScan<P> {

		private final int[] chunkXs;
		private final int[] chunkZs;
		private final WeakReference<?>[] chunks;
		private final long[] epochs;
		private final boolean tracked;
		private Result<P> result;

		private CachedScan(Key key, BlockSource<P> blockSource) {
			chunkXs = chunkCoordinates(key.xs, key.countX);
			chunkZs = chunkCoordinates(key.zs, key.countZ);
			chunks = new WeakReference<?>[chunkXs.length * chunkZs.length];
			epochs = new long[chunks.length];
			boolean tracked = true;
			int index = 0;
			for (final int chunkX : chunkXs) {
				for (final int chunkZ : chunkZs) {
					final Object chunk = blockSource.getChunk(chunkX, chunkZ);
					final long epoch = chunk == null ? UNTRACKED : blockSource.getChunkEpoch(chunk);
					tracked &= epoch != UNTRACKED;
					chunks[index] = new WeakReference<>(chunk);
					epochs[index] = epoch;
					index++;
				}
			}
			this.tracked = tracked;
		}

		private boolean isValid(BlockSource<P> blockSource) {
			int index = 0;
			for (final int chunkX : chunkXs) {
				for (final int chunkZ : chunkZs) {
					final Object chunk = blockSource.getChunk(chunkX, chunkZ);
					if (chunk == null || chunks[index].get() != chunk || blockSource.getChunkEpoch(chunk) != epochs[index]) {
						return false;
					}
					index++;
				}
			}
			return true;
		}

		private static int[] chunkCoordinates(int[] cells, int count) {
			final int[] chunkCoordinates = new int[count];
			int chunkCount = 0;
			for (int i = 0; i < count; i++) {
				final int chunkCoordinate = cells[i] >> 4;
				boolean found = false;
				for (int j = 0; j < chunkCount; j++) {
					found |= chunkCoordinates[j] == chunkCoordinate;
				}
				if (!found) {
					chunkCoordinates[chunkCount++] = chunkCoordinate;
				}
			}
			return Arrays.copyOf(chunkCoordinates, chunkCount);
		}
	}

	private static final class Key {

		private final int[] xs, ys, zs;
		private int countX, countY, countZ;
		private int hash;

		private Key(int[] xs, int[] ys, int[] zs) {
			this.xs = xs;
			this.ys = ys;
			this.zs = zs;
		}

		private Key copy() {
			final Key key = new Key(Arrays.copyOf(xs, countX), Arrays.copyOf(ys, countY), Arrays.copyOf(zs, countZ));
			key.countX = countX;
			key.countY = countY;
			key.countZ = countZ;
			key.hash = hash;
			return key;
		}

		private void updateHash() {
			int result = 1;
			result = 31 * result + hash(xs, countX);
			result = 31 * result + hash(ys, countY);
			hash = 31 * result + hash(zs, countZ);
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof Key)) {
				return false;
			}
			final Key key = (Key) object;
			return hash == key.hash && equals(xs, countX, key.xs, key.countX) && equals(ys, countY, key.ys, key.countY) && equals(zs, countZ, key.zs, key.countZ);
		}

		@Override
		public int hashCode() {
			return hash;
		}

		private static int hash(int[] values, int count) {
			int result = count;
			for (int i = 0; i < count; i++) {
				result = 31 * result + values[i];
			}
			return result;
		}

		private static boolean equals(int[] values1, int count1, int[] values2, int count2) {
			return Arrays.equals(values1, 0, count1, values2, 0, count2);
		}
	}
}
