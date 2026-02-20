package org.mtr.mod.resource;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.Supplier;

public final class CachedResource<T> {

	@Nullable
	private T data;
	private long expiry;
	@Nullable
	private CacheExpiry cacheExpiry;

	private final Supplier<T> dataSupplier;
	private final long lifespan;
	private final long cacheId;

	private static boolean canFetchCache;
	private static long nextCacheId;
	private static final NavigableSet<CacheExpiry> CACHE_EXPIRIES = new TreeSet<>(Comparator.comparingLong((CacheExpiry value) -> value.expiry).thenComparingLong(value -> value.cacheId));

	public CachedResource(final Supplier<T> dataSupplier, final long lifespan) {
		this.dataSupplier = dataSupplier;
		this.lifespan = lifespan;
		cacheId = nextCacheId++;
	}

	@Nullable
	public T getData(boolean force) {
		if (force || canFetchCache) {
			final long currentMillis = System.currentTimeMillis();
			if (data == null || currentMillis > expiry) {
				data = dataSupplier.get();
				canFetchCache = false;
			}

			expiry = currentMillis + lifespan;
			if (cacheExpiry != null) {
				CACHE_EXPIRIES.remove(cacheExpiry);
			}
			cacheExpiry = new CacheExpiry(this, expiry, cacheId);
			CACHE_EXPIRIES.add(cacheExpiry);
		}
		return data;
	}

	public static void tick() {
		canFetchCache = true;
		final long currentMillis = System.currentTimeMillis();
		while (!CACHE_EXPIRIES.isEmpty()) {
			final CacheExpiry cacheExpiry = CACHE_EXPIRIES.first();
			if (cacheExpiry.expiry > currentMillis) {
				break;
			}
			CACHE_EXPIRIES.pollFirst();
			if (cacheExpiry.cachedResource.cacheExpiry == cacheExpiry) {
				cacheExpiry.cachedResource.data = null;
				cacheExpiry.cachedResource.cacheExpiry = null;
			}
		}
	}

	private static class CacheExpiry {

		private final CachedResource<?> cachedResource;
		private final long expiry;
		private final long cacheId;

		private CacheExpiry(CachedResource<?> cachedResource, long expiry, long cacheId) {
			this.cachedResource = cachedResource;
			this.expiry = expiry;
			this.cacheId = cacheId;
		}
	}
}
