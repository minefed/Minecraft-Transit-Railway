package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.cache.ArrayOcclusionCache;
import com.logisticscraft.occlusionculling.cache.OcclusionCache;

import java.util.Arrays;

/**
 * Behaves exactly like {@link ArrayOcclusionCache}: the same position keys, the same byte and bit arithmetic
 * (including negative offsets) and the same {@link ArrayIndexOutOfBoundsException} for invalid indices.
 * <p>
 * Instead of one array for the whole cube ({@code (2 * reach)³ / 4} bytes, 256 MiB at render distance 32),
 * the bytes are stored in 16×16×16-cell bricks that are allocated on the first write. A missing brick reads
 * as zero, which is also the value of every byte after a reset. A reset zeroes only the bricks written since
 * the previous reset, and bricks that stay unused for a while are released after a reset, when they are zero.
 */
final class DirtyBlockOcclusionCache implements OcclusionCache {

	private static final int BRICK_SHIFT = 4;
	private static final int BRICK_MASK = (1 << BRICK_SHIFT) - 1;
	/**
	 * 4096 cells at 2 bits each
	 */
	private static final int BRICK_BYTES = 1 << (BRICK_SHIFT * 3 - 2);
	private static final int SWEEP_INTERVAL = 64;
	private static final int IDLE_RESETS = 256;

	private final int reachX2;
	private final int length;
	private final boolean alignedRows;
	private final int bricksPerAxis;
	private final byte[][] bricks;
	private final int[] lastWrite;
	private final int[] dirtyBricks;
	private final int[] allocatedBricks;
	private int dirtyCount;
	private int allocatedCount;
	private int epoch = 1;

	private int positionKey;
	private int entry;
	private int offset;
	private int brickIndex = -1;
	private int innerIndex;

	DirtyBlockOcclusionCache(int reach) {
		reachX2 = reach * 2;
		length = (reachX2 * reachX2 * reachX2) / 4;
		if (length < 0) {
			throw new NegativeArraySizeException(String.valueOf(length));
		}
		// With rows of a multiple of 4 cells, the 4 cells of a byte share y and z
		alignedRows = (reachX2 & 3) == 0;
		bricksPerAxis = (reachX2 + BRICK_MASK) >> BRICK_SHIFT;
		final int brickCount = bricksPerAxis * bricksPerAxis * bricksPerAxis;
		bricks = new byte[brickCount][];
		lastWrite = new int[brickCount];
		dirtyBricks = new int[brickCount];
		allocatedBricks = new int[brickCount];
		// The array cache starts with entry 0 and offset 0 for setLastVisible and setLastHidden
		locateEntry(0);
	}

	@Override
	public void resetCache() {
		for (int i = 0; i < dirtyCount; i++) {
			Arrays.fill(bricks[dirtyBricks[i]], (byte) 0);
		}
		dirtyCount = 0;
		epoch++;
		if (epoch % SWEEP_INTERVAL == 0) {
			// Every byte is zero here, so dropping a brick does not change any value
			for (int i = allocatedCount - 1; i >= 0; i--) {
				final int brick = allocatedBricks[i];
				if (epoch - lastWrite[brick] > IDLE_RESETS) {
					bricks[brick] = null;
					allocatedBricks[i] = allocatedBricks[--allocatedCount];
				}
			}
		}
	}

	@Override
	public void setVisible(int x, int y, int z) {
		locate(x, y, z);
		set(1 << offset);
	}

	@Override
	public void setHidden(int x, int y, int z) {
		locate(x, y, z);
		set(1 << offset + 1);
	}

	@Override
	public int getState(int x, int y, int z) {
		locate(x, y, z);
		return get() >> offset & 3;
	}

	@Override
	public void setLastVisible() {
		set(1 << offset);
	}

	@Override
	public void setLastHidden() {
		set(1 << offset + 1);
	}

	/**
	 * @return the number of bytes the equivalent {@link ArrayOcclusionCache} would allocate
	 */
	int byteLength() {
		return length;
	}

	/**
	 * @return the byte at the given index of the equivalent {@link ArrayOcclusionCache} array
	 */
	byte byteAt(int index) {
		// Keep the position used by setLastVisible and setLastHidden
		final int savedBrickIndex = brickIndex;
		final int savedInnerIndex = innerIndex;
		locateEntry(index);
		final byte value = get();
		brickIndex = savedBrickIndex;
		innerIndex = savedInnerIndex;
		return value;
	}

	/**
	 * @return the number of allocated bricks
	 */
	int allocatedBrickCount() {
		return allocatedCount;
	}

	private void locate(int x, int y, int z) {
		positionKey = x + y * reachX2 + z * reachX2 * reachX2;
		entry = positionKey / 4;
		offset = (positionKey % 4) * 2;
		if (alignedRows && x >= 0 && x < reachX2 && y >= 0 && y < reachX2 && z >= 0 && z < reachX2) {
			// Same result as locateEntry(entry) without divisions
			brickIndex = ((z >> BRICK_SHIFT) * bricksPerAxis + (y >> BRICK_SHIFT)) * bricksPerAxis + (x >> BRICK_SHIFT);
			innerIndex = ((z & BRICK_MASK) << (BRICK_SHIFT + 2)) | ((y & BRICK_MASK) << 2) | ((x & BRICK_MASK) >> 2);
		} else {
			locateEntry(entry);
		}
	}

	/**
	 * Maps a byte index of the equivalent array to its brick. Byte {@code index} holds cells {@code 4 * index} to {@code 4 * index + 3},
	 * so the first of these cells decides the brick; the mapping is one-to-one for every valid index.
	 */
	private void locateEntry(int index) {
		if (index < 0 || index >= length) {
			brickIndex = -1;
			innerIndex = index;
			return;
		}
		final long cell = 4L * index;
		final int x = (int) (cell % reachX2);
		final long rest = cell / reachX2;
		final int y = (int) (rest % reachX2);
		final int z = (int) (rest / reachX2);
		brickIndex = ((z >> BRICK_SHIFT) * bricksPerAxis + (y >> BRICK_SHIFT)) * bricksPerAxis + (x >> BRICK_SHIFT);
		innerIndex = ((z & BRICK_MASK) << (BRICK_SHIFT + 2)) | ((y & BRICK_MASK) << 2) | ((x & BRICK_MASK) >> 2);
	}

	private byte get() {
		if (brickIndex < 0) {
			throw new ArrayIndexOutOfBoundsException(innerIndex);
		}
		final byte[] brick = bricks[brickIndex];
		return brick == null ? 0 : brick[innerIndex];
	}

	/**
	 * Equivalent to {@code cache[entry] |= bits}; an invalid index throws before anything is written.
	 */
	private void set(int bits) {
		final byte oldValue = get();
		final byte newValue = (byte) (oldValue | bits);
		if (newValue != oldValue) {
			byte[] brick = bricks[brickIndex];
			if (brick == null) {
				brick = new byte[BRICK_BYTES];
				bricks[brickIndex] = brick;
				allocatedBricks[allocatedCount++] = brickIndex;
			}
			brick[innerIndex] = newValue;
			if (lastWrite[brickIndex] != epoch) {
				lastWrite[brickIndex] = epoch;
				dirtyBricks[dirtyCount++] = brickIndex;
			}
		}
	}
}
