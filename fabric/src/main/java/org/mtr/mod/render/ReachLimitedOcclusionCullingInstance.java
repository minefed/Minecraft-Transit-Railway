package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.cache.OcclusionCache;
import com.logisticscraft.occlusionculling.util.MathUtilities;
import com.logisticscraft.occlusionculling.util.Vec3d;

/**
 * Uses a dirty dense cache at common view distances, a sparse cache at larger distances,
 * and skips boxes whose cells are all outside the cache cube.
 * <p>
 * For such boxes, the library marks every cell as skipped because its cache lookup returns
 * {@code -1}, performs no ray or cache access, and returns {@code false}. The camera-inside
 * check still comes first. Boxes near integer overflow or large enough to overflow the
 * library's cell index are passed through unchanged.
 */
final class ReachLimitedOcclusionCullingInstance extends OcclusionCullingInstance {

	private static final double AABB_EXPANSION = 0.5;
	private static final int MAX_COORDINATE = 1 << 29;
	private static final long MAX_CELLS = 1L << 24;
	// Dense lookup avoids sparse-brick addressing in every ray step. Limit its payload
	// to 32 MiB (16 chunks); larger distances retain sparse allocation, including 32 chunks.
	private static final int MAX_DENSE_REACH = 16 * 16;

	private final int reach;

	ReachLimitedOcclusionCullingInstance(int maxDistance, DataProvider provider) {
		super(maxDistance, provider, createCache(maxDistance), AABB_EXPANSION);
		reach = maxDistance;
	}

	static OcclusionCache createCache(int maxDistance) {
		return maxDistance >= 0 && maxDistance <= MAX_DENSE_REACH ? new DenseDirtyOcclusionCache(maxDistance) : new DirtyBlockOcclusionCache(maxDistance);
	}

	@Override
	public boolean isAABBVisible(Vec3d aabbMin, Vec3d aabbMax, Vec3d viewerPosition) {
		return aabbMin != null && aabbMax != null && viewerPosition != null && isOutsideReach(
				MathUtilities.floor(aabbMin.x - AABB_EXPANSION), MathUtilities.floor(aabbMax.x + AABB_EXPANSION), MathUtilities.floor(viewerPosition.x),
				MathUtilities.floor(aabbMin.y - AABB_EXPANSION), MathUtilities.floor(aabbMax.y + AABB_EXPANSION), MathUtilities.floor(viewerPosition.y),
				MathUtilities.floor(aabbMin.z - AABB_EXPANSION), MathUtilities.floor(aabbMax.z + AABB_EXPANSION), MathUtilities.floor(viewerPosition.z),
				reach
		) ? false : super.isAABBVisible(aabbMin, aabbMax, viewerPosition);
	}

	/**
	 * @return {@code true} only if {@link OcclusionCullingInstance#isAABBVisible} is known to return {@code false} without side effects
	 */
	static boolean isOutsideReach(int minX, int maxX, int cameraX, int minY, int maxY, int cameraY, int minZ, int maxZ, int cameraZ, int reach) {
		if (!inRange(minX, maxX, cameraX) || !inRange(minY, maxY, cameraY) || !inRange(minZ, maxZ, cameraZ)) {
			return false;
		}
		// Keep the library's result when the camera is inside the expanded box
		if (inside(minX, maxX, cameraX) && inside(minY, maxY, cameraY) && inside(minZ, maxZ, cameraZ)) {
			return false;
		}
		final long cellsXY = (long) (maxX - minX + 1) * (maxY - minY + 1);
		if (cellsXY > MAX_CELLS || cellsXY * (maxZ - minZ + 1) > MAX_CELLS) {
			return false;
		}
		// A cell is cached only if all three axes are within reach - 2
		final int limit = reach - 2;
		return outside(minX - cameraX, maxX - cameraX, limit) || outside(minY - cameraY, maxY - cameraY, limit) || outside(minZ - cameraZ, maxZ - cameraZ, limit);
	}

	private static boolean inRange(int min, int max, int camera) {
		return min <= max && Math.abs(min) <= MAX_COORDINATE && Math.abs(max) <= MAX_COORDINATE && Math.abs(camera) <= MAX_COORDINATE;
	}

	/** The negation of the library's {@code POSITIVE} and {@code NEGATIVE} cases. */
	private static boolean inside(int min, int max, int camera) {
		return !(max > camera && min > camera) && !(min < camera && max < camera);
	}

	/** Whether every offset in {@code [min, max]} fails {@code Math.abs(offset) <= limit}. */
	private static boolean outside(int min, int max, int limit) {
		return max < -limit || min > limit || limit < 0;
	}
}
