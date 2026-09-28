package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.cache.ArrayOcclusionCache;
import com.logisticscraft.occlusionculling.cache.OcclusionCache;
import com.logisticscraft.occlusionculling.util.Vec3d;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class OcclusionCullingEquivalenceTest {

	@Test
	public void dirtyBlockCacheMatchesArrayCacheIncludingInvalidIndices() throws ReflectiveOperationException {
		final Random random = new Random(3);
		for (final int reach : new int[]{0, 1, 2, 5, 16, 33}) {
			final ArrayOcclusionCache original = new ArrayOcclusionCache(reach);
			final DirtyBlockOcclusionCache optimized = new DirtyBlockOcclusionCache(reach);
			final byte[] originalBytes = bytes(original);
			final byte[] optimizedBytes = bytes(optimized);
			assertEquals(originalBytes.length, optimizedBytes.length);
			for (int step = 0; step < 200_000; step++) {
				final int operation = random.nextInt(20);
				final int range = reach * 2 + 4;
				final int x = random.nextInt(range) - 2;
				final int y = random.nextInt(range) - 2;
				final int z = random.nextInt(range) - 2;
				assertSame(outcome(original, operation, x, y, z), outcome(optimized, operation, x, y, z), "reach " + reach + " step " + step);
				if (operation == 0 || step % 97 == 0) {
					assertArrayEquals(originalBytes, optimizedBytes, "reach " + reach + " step " + step);
				}
			}
			original.resetCache();
			optimized.resetCache();
			assertArrayEquals(new byte[originalBytes.length], optimizedBytes);
		}
	}

	@Test
	public void cullingResultsMatchTheLibraryInstance() {
		final Random random = new Random(4);
		for (final int reach : new int[]{32, 48, 64}) {
			final TestDataProvider provider = new TestDataProvider(random.nextLong());
			final OcclusionCullingInstance original = new OcclusionCullingInstance(reach, provider);
			final ReachLimitedOcclusionCullingInstance optimized = new ReachLimitedOcclusionCullingInstance(reach, provider);
			int skipped = 0;
			int visible = 0;
			for (int step = 0; step < 60_000; step++) {
				if (random.nextInt(40) == 0) {
					original.resetCache();
					optimized.resetCache();
				}
				final Vec3d camera = new Vec3d(random.nextInt(8) + random.nextDouble(), 60 + random.nextInt(8) + random.nextDouble(), random.nextInt(8) + random.nextDouble());
				final double scale = random.nextInt(4) == 0 ? reach * 4 : reach;
				final double x = camera.x + (random.nextDouble() - 0.5) * scale * 2;
				final double y = camera.y + (random.nextDouble() - 0.5) * scale;
				final double z = camera.z + (random.nextDouble() - 0.5) * scale * 2;
				final double sizeX = random.nextInt(10) == 0 ? random.nextDouble() * reach * 3 : random.nextDouble() * 6;
				final double sizeY = random.nextDouble() * 6;
				final double sizeZ = random.nextInt(10) == 0 ? random.nextDouble() * reach * 3 : random.nextDouble() * 6;
				final Vec3d min = new Vec3d(x, y, z);
				// Some callers pass unsorted rail end points
				final Vec3d max = random.nextInt(20) == 0 ? new Vec3d(x - sizeX, y + sizeY, z - sizeZ) : new Vec3d(x + sizeX, y + sizeY, z + sizeZ);
				final boolean expected = original.isAABBVisible(min, max, camera);
				final int[] cells = cells(min, max, camera);
				if (ReachLimitedOcclusionCullingInstance.isOutsideReach(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5], cells[6], cells[7], cells[8], reach)) {
					assertFalse(expected);
					skipped++;
				}
				visible += expected ? 1 : 0;
				assertEquals(expected, optimized.isAABBVisible(min, max, camera), "reach " + reach + " step " + step);
			}
			assertTrue(skipped > 1000, "Boxes outside the cache cube must use the early return: " + skipped);
			assertTrue(visible > 1000 && visible < 59_000, "The fixture must contain visible and hidden boxes: " + visible);
		}
		assertTrue(new ReachLimitedOcclusionCullingInstance(32, new TestDataProvider(0)).isAABBVisible(new Vec3d(-1, -1, -1), new Vec3d(1, 1, 1), new Vec3d(0, 0, 0)));
		assertTrue(new ReachLimitedOcclusionCullingInstance(32, new TestDataProvider(0)).isAABBVisible(null, new Vec3d(1, 1, 1), new Vec3d(0, 0, 0)));
	}

	@Test
	public void earlyReturnRequiresEveryCellOutsideTheCacheCube() {
		assertTrue(ReachLimitedOcclusionCullingInstance.isOutsideReach(31, 40, 0, 0, 1, 0, 0, 1, 0, 32));
		assertFalse(ReachLimitedOcclusionCullingInstance.isOutsideReach(30, 40, 0, 0, 1, 0, 0, 1, 0, 32));
		assertTrue(ReachLimitedOcclusionCullingInstance.isOutsideReach(-40, -31, 0, 0, 1, 0, 0, 1, 0, 32));
		assertFalse(ReachLimitedOcclusionCullingInstance.isOutsideReach(-40, -30, 0, 0, 1, 0, 0, 1, 0, 32));
		// Camera inside the box
		assertFalse(ReachLimitedOcclusionCullingInstance.isOutsideReach(-1, 1, 0, -1, 1, 0, -1, 1, 0, 1));
		// Unsorted, oversized or near-overflow boxes are left to the library
		assertFalse(ReachLimitedOcclusionCullingInstance.isOutsideReach(40, 31, 0, 0, 1, 0, 0, 1, 0, 32));
		assertFalse(ReachLimitedOcclusionCullingInstance.isOutsideReach(100, 5000, 0, 0, 5000, 0, 0, 5000, 0, 32));
		assertFalse(ReachLimitedOcclusionCullingInstance.isOutsideReach(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 0, 0, 1, 0, 0, 1, 0, 32));
	}

	private static int[] cells(Vec3d min, Vec3d max, Vec3d camera) {
		return new int[]{
				floor(min.x - 0.5), floor(max.x + 0.5), floor(camera.x),
				floor(min.y - 0.5), floor(max.y + 0.5), floor(camera.y),
				floor(min.z - 0.5), floor(max.z + 0.5), floor(camera.z),
		};
	}

	private static int floor(double value) {
		final int intValue = (int) value;
		return value < intValue ? intValue - 1 : intValue;
	}

	private static Object outcome(OcclusionCache cache, int operation, int x, int y, int z) {
		try {
			switch (operation) {
				case 0:
					cache.resetCache();
					return "reset";
				case 1:
				case 2:
				case 3:
					cache.setVisible(x, y, z);
					return "visible";
				case 4:
				case 5:
				case 6:
					cache.setHidden(x, y, z);
					return "hidden";
				case 7:
					cache.setLastVisible();
					return "last visible";
				case 8:
					cache.setLastHidden();
					return "last hidden";
				default:
					return STATES[cache.getState(x, y, z) & 3];
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			return "out of bounds";
		}
	}

	private static final String[] STATES = {"0", "1", "2", "3"};

	private static byte[] bytes(OcclusionCache cache) throws ReflectiveOperationException {
		final Field field = cache.getClass().getDeclaredField("cache");
		field.setAccessible(true);
		return (byte[]) field.get(cache);
	}

	private static final class TestDataProvider implements DataProvider {

		private final long seed;

		private TestDataProvider(long seed) {
			this.seed = seed;
		}

		@Override
		public boolean prepareChunk(int chunkX, int chunkZ) {
			return hash(chunkX, 0, chunkZ) % 50 != 0;
		}

		@Override
		public boolean isOpaqueFullCube(int x, int y, int z) {
			return y < 58 || hash(x, y, z) % 12 == 0;
		}

		private long hash(int x, int y, int z) {
			long hash = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
			hash ^= hash >>> 29;
			hash *= 0xBF58476D1CE4E5B9L;
			return Math.abs(hash ^ hash >>> 32);
		}
	}
}
