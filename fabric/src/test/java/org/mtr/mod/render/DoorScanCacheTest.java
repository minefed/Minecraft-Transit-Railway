package org.mtr.mod.render;

import net.minecraft.util.math.MathHelper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class DoorScanCacheTest {

	private static final int AIR = 0;
	private static final int PLATFORM = 1;
	private static final int LOCKED_DOOR = 2;
	private static final int UNLOCKED_DOOR = 3;
	private static final int STONE = 4;

	@Test
	public void cellsMatchTheOriginalDoubleLoopAndMinecraftFloor() {
		final Random random = new Random(1);
		final double[] special = {-1E-17, 1E-17, -0.5, 0.5, -1, 1, 0.9999999999999999, -0.9999999999999999, 29_999_999.5, -29_999_999.5, 4503599627370495.5, Double.NaN};
		for (int i = 0; i < 100_000; i++) {
			final double min;
			final double max;
			if (i < special.length * special.length) {
				min = special[i % special.length];
				max = special[i / special.length] + random.nextInt(4);
			} else {
				min = (random.nextDouble() - 0.5) * (random.nextBoolean() ? 20 : 6E7);
				max = min + random.nextDouble() * (random.nextInt(10) == 0 ? 80 : 8) - 0.5;
			}
			final List<Integer> expected = new ArrayList<>();
			for (double value = min; value <= max && expected.size() <= DoorScanCache.MAX_CELLS_PER_AXIS; value++) {
				expected.add(MathHelper.floor(value));
			}
			final int[] cells = new int[DoorScanCache.MAX_CELLS_PER_AXIS];
			final int count = DoorScanCache.cells(min, max, cells);
			if (expected.size() > DoorScanCache.MAX_CELLS_PER_AXIS) {
				assertEquals(-1, count);
			} else {
				assertEquals(expected.size(), count, min + " to " + max);
				for (int j = 0; j < count; j++) {
					assertEquals(expected.get(j), cells[j]);
				}
			}
		}
	}

	@Test
	public void cachedScansMatchUncachedScansAfterRandomEditsAndChunkChanges() {
		final Random random = new Random(2);
		final FakeWorld world = new FakeWorld();
		final DoorScanCache<Position> cache = new DoorScanCache<>(64);
		final double[][] doorways = new double[40][];
		for (int i = 0; i < doorways.length; i++) {
			doorways[i] = randomDoorway(random);
		}
		for (int i = 0; i < 3000; i++) {
			world.setBlock(random.nextInt(48) - 24, random.nextInt(12) - 4, random.nextInt(48) - 24, random.nextInt(5));
		}

		int hits = 0;
		for (int step = 0; step < 200_000; step++) {
			final int action = random.nextInt(100);
			if (action < 6) {
				world.setBlock(random.nextInt(48) - 24, random.nextInt(12) - 4, random.nextInt(48) - 24, random.nextInt(5));
			} else if (action == 6) {
				world.unload(random.nextInt(4) - 2, random.nextInt(4) - 2);
			} else if (action == 7) {
				world.load(random.nextInt(4) - 2, random.nextInt(4) - 2, random, random.nextBoolean());
			} else if (action == 8) {
				doorways[random.nextInt(doorways.length)] = randomDoorway(random);
			} else {
				final double[] doorway = doorways[random.nextInt(doorways.length)];
				final int scansBefore = world.scans;
				final DoorScanCache.Result<Position> result = cache.get(doorway[0], doorway[1], doorway[2], doorway[3], doorway[4], doorway[5], world);
				assertNotNull(result);
				if (world.scans == scansBefore) {
					hits++;
				}
				final List<Position> expectedDoors = new ArrayList<>();
				final boolean expected = uncached(world, doorway, expectedDoors);
				assertEquals(expected, result.canOpenDoors, "step " + step);
				assertEquals(expectedDoors, result.unlockedDoors, "step " + step);
			}
			assertTrue(cache.size() <= 64);
		}
		assertTrue(hits > 50_000, "The cache must be reused between unrelated edits: " + hits);
	}

	@Test
	public void untrackedChunksAndOversizedRangesAreNotCached() {
		final FakeWorld world = new FakeWorld();
		world.setBlock(0, 0, 0, PLATFORM);
		final DoorScanCache<Position> cache = new DoorScanCache<>(8);
		world.tracked = false;
		for (int i = 0; i < 3; i++) {
			assertTrue(cache.get(-1, 1, -1, 1, -1, 1, world).canOpenDoors);
		}
		assertEquals(81, world.scans);
		assertEquals(0, cache.size());
		assertNull(cache.get(0, 100, 0, 1, 0, 1, world));
		world.tracked = true;
		assertTrue(cache.get(-1, 1, -1, 1, -1, 1, world).canOpenDoors);
		assertTrue(cache.get(-1, 1, -1, 1, -1, 1, world).canOpenDoors);
		assertEquals(108, world.scans);
		assertFalse(cache.get(Double.NaN, 1, -1, 1, -1, 1, world).canOpenDoors);
		cache.clear();
		assertEquals(0, cache.size());
	}

	private static double[] randomDoorway(Random random) {
		final double x = random.nextDouble() * 40 - 20;
		final double y = random.nextDouble() * 6 - 2;
		final double z = random.nextDouble() * 40 - 20;
		final double width = random.nextDouble() * 3;
		final double depth = random.nextDouble() * 3;
		return new double[]{x - 1, x + width + 1, y - 2, y + 2, z - 1, z + depth + 1};
	}

	/** The loop of the original {@code RenderVehicleHelper.canOpenDoors}. */
	private static boolean uncached(FakeWorld world, double[] doorway, List<Position> doors) {
		boolean canOpenDoors = false;
		for (double checkX = doorway[0]; checkX <= doorway[1]; checkX++) {
			for (double checkY = doorway[2]; checkY <= doorway[3]; checkY++) {
				for (double checkZ = doorway[4]; checkZ <= doorway[5]; checkZ++) {
					final int block = world.getBlock(MathHelper.floor(checkX), MathHelper.floor(checkY), MathHelper.floor(checkZ));
					if (block == PLATFORM) {
						canOpenDoors = true;
					} else if (block == UNLOCKED_DOOR) {
						canOpenDoors = true;
						doors.add(new Position(MathHelper.floor(checkX), MathHelper.floor(checkY), MathHelper.floor(checkZ)));
					}
				}
			}
		}
		return canOpenDoors;
	}

	private static final class FakeWorld implements DoorScanCache.BlockSource<Position> {

		private final Chunk emptyChunk = new Chunk();
		private final Map<Long, Chunk> chunks = new HashMap<>();
		private boolean tracked = true;
		private int scans;

		private FakeWorld() {
			for (int x = -2; x < 2; x++) {
				for (int z = -2; z < 2; z++) {
					chunks.put(key(x, z), new Chunk());
				}
			}
		}

		/** Mirrors the chunk mixin: only platform and platform door blocks change the epoch. */
		private void setBlock(int x, int y, int z, int block) {
			final Chunk chunk = chunks.get(key(x >> 4, z >> 4));
			if (chunk != null) {
				final long position = key(x, y * 1_000_003L + z);
				final int oldBlock = chunk.blocks.getOrDefault(position, AIR);
				if (relevant(block) || relevant(oldBlock)) {
					chunk.epoch++;
				}
				chunk.blocks.put(position, block);
			}
		}

		private void unload(int chunkX, int chunkZ) {
			chunks.remove(key(chunkX, chunkZ));
		}

		private void load(int chunkX, int chunkZ, Random random, boolean reuse) {
			final Chunk existing = chunks.get(key(chunkX, chunkZ));
			final Chunk chunk;
			if (reuse && existing != null) {
				// Reloading an existing chunk from a packet keeps the object and bumps its epoch
				chunk = existing;
				chunk.epoch++;
				chunk.blocks.clear();
			} else {
				chunk = new Chunk();
				chunks.put(key(chunkX, chunkZ), chunk);
			}
			for (int i = 0; i < 20; i++) {
				final int x = chunkX * 16 + random.nextInt(16);
				final int z = chunkZ * 16 + random.nextInt(16);
				chunk.blocks.put(key(x, (random.nextInt(12) - 4) * 1_000_003L + z), random.nextInt(5));
			}
		}

		private int getBlock(int x, int y, int z) {
			final Chunk chunk = chunks.get(key(x >> 4, z >> 4));
			return chunk == null ? AIR : chunk.blocks.getOrDefault(key(x, y * 1_000_003L + z), AIR);
		}

		@Override
		public Position createPosition(int x, int y, int z) {
			return new Position(x, y, z);
		}

		@Override
		public int getBlockType(Position position) {
			scans++;
			final int block = getBlock(position.x, position.y, position.z);
			return block == PLATFORM ? DoorScanCache.PLATFORM : block == UNLOCKED_DOOR ? DoorScanCache.UNLOCKED_DOOR : DoorScanCache.OTHER;
		}

		@Override
		public Object getChunk(int chunkX, int chunkZ) {
			return chunks.getOrDefault(key(chunkX, chunkZ), emptyChunk);
		}

		@Override
		public long getChunkEpoch(Object chunk) {
			return tracked ? ((Chunk) chunk).epoch : DoorScanCache.UNTRACKED;
		}

		private static boolean relevant(int block) {
			return block == PLATFORM || block == LOCKED_DOOR || block == UNLOCKED_DOOR;
		}

		private static long key(long a, long b) {
			return a * 4_000_000_037L + b;
		}
	}

	private static final class Chunk {
		private final Map<Long, Integer> blocks = new HashMap<>();
		private long epoch;
	}

	private static final class Position {

		private final int x, y, z;

		private Position(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof Position && ((Position) object).x == x && ((Position) object).y == y && ((Position) object).z == z;
		}

		@Override
		public int hashCode() {
			return (x * 31 + y) * 31 + z;
		}

		@Override
		public String toString() {
			return x + "," + y + "," + z;
		}
	}
}
