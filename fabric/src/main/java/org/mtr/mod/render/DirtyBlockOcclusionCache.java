package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.cache.ArrayOcclusionCache;
import com.logisticscraft.occlusionculling.cache.OcclusionCache;

import java.util.Arrays;

/**
 * The same byte layout and index arithmetic as {@link ArrayOcclusionCache}, including its exceptions.
 * Each 64-byte block that becomes non-zero is marked, so a reset only zeroes marked blocks
 * instead of the whole cube. Every byte is zero after a reset, exactly as before.
 */
final class DirtyBlockOcclusionCache implements OcclusionCache {

	private static final int BLOCK_SHIFT = 6;

	private final int reachX2;
	private final byte[] cache;
	private final long[] dirtyBlocks;
	private int positionKey;
	private int entry;
	private int offset;

	DirtyBlockOcclusionCache(int reach) {
		reachX2 = reach * 2;
		cache = new byte[(reachX2 * reachX2 * reachX2) / 4];
		final int blocks = (cache.length >> BLOCK_SHIFT) + 1;
		dirtyBlocks = new long[(blocks >> 6) + 1];
	}

	@Override
	public void resetCache() {
		for (int i = 0; i < dirtyBlocks.length; i++) {
			long word = dirtyBlocks[i];
			if (word != 0) {
				dirtyBlocks[i] = 0;
				while (word != 0) {
					final int block = (i << 6) + Long.numberOfTrailingZeros(word);
					final int start = block << BLOCK_SHIFT;
					Arrays.fill(cache, start, Math.min(start + (1 << BLOCK_SHIFT), cache.length), (byte) 0);
					word &= word - 1;
				}
			}
		}
	}

	@Override
	public void setVisible(int x, int y, int z) {
		positionKey = x + y * reachX2 + z * reachX2 * reachX2;
		entry = positionKey / 4;
		offset = (positionKey % 4) * 2;
		set(entry, 1 << offset);
	}

	@Override
	public void setHidden(int x, int y, int z) {
		positionKey = x + y * reachX2 + z * reachX2 * reachX2;
		entry = positionKey / 4;
		offset = (positionKey % 4) * 2;
		set(entry, 1 << offset + 1);
	}

	@Override
	public int getState(int x, int y, int z) {
		positionKey = x + y * reachX2 + z * reachX2 * reachX2;
		entry = positionKey / 4;
		offset = (positionKey % 4) * 2;
		return cache[entry] >> offset & 3;
	}

	@Override
	public void setLastVisible() {
		set(entry, 1 << offset);
	}

	@Override
	public void setLastHidden() {
		set(entry, 1 << offset + 1);
	}

	/** Equivalent to {@code cache[index] |= bits}; an invalid index throws before anything is written. */
	private void set(int index, int bits) {
		final byte oldValue = cache[index];
		final byte newValue = (byte) (oldValue | bits);
		cache[index] = newValue;
		if (oldValue == 0 && newValue != 0) {
			final int block = index >> BLOCK_SHIFT;
			dirtyBlocks[block >> 6] |= 1L << block;
		}
	}
}
