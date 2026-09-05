package org.mtr.mod.resource;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.function.Supplier;

public final class CachedResource<T> {

	@Nullable
	private T data;
	private long expiry;

	private final Supplier<T> dataSupplier;
	private final long lifespan;

	private static boolean canFetchCache;
	private static final ObjectArrayList<WeakReference<CachedResource<?>>> CACHED_RESOURCES = new ObjectArrayList<>();

	public CachedResource(final Supplier<T> dataSupplier, final long lifespan) {
		this.dataSupplier = dataSupplier;
		this.lifespan = lifespan;
		synchronized (CACHED_RESOURCES) {
			CACHED_RESOURCES.add(new WeakReference<>(this));
		}
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
		}
		return data;
	}

	public static void tick() {
		canFetchCache = true;
		final long currentMillis = System.currentTimeMillis();
		synchronized (CACHED_RESOURCES) {
			for (int i = CACHED_RESOURCES.size() - 1; i >= 0; i--) {
				final CachedResource<?> cachedResource = CACHED_RESOURCES.get(i).get();
				if (cachedResource == null) {
					CACHED_RESOURCES.remove(i);
				} else if (currentMillis > cachedResource.expiry) {
					cachedResource.data = null;
				}
			}
		}
	}
}
