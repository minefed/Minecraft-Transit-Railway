package org.mtr.mod.resource;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import javax.annotation.Nullable;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class CachedResource<T> {

	@Nullable
	private T data;
	private long expiry;

	private final Supplier<T> dataSupplier;
	private final long lifespan;

	private static boolean canFetchCache;
	private static final ReferenceQueue<CachedResource<?>> COLLECTED_RESOURCES = new ReferenceQueue<>();
	private static final ObjectArrayList<WeakReference<CachedResource<?>>> CACHED_RESOURCES = new ObjectArrayList<>();
	private static final AtomicLong EXPIRY_REVISION = new AtomicLong();
	private static long scannedExpiryRevision = -1;
	private static long nextExpiry = Long.MIN_VALUE;

	public CachedResource(final Supplier<T> dataSupplier, final long lifespan) {
		this.dataSupplier = dataSupplier;
		this.lifespan = lifespan;
		synchronized (CACHED_RESOURCES) {
			CACHED_RESOURCES.add(new WeakReference<>(this, COLLECTED_RESOURCES));
		}
	}

	@Nullable
	public T getData(boolean force) {
		if (force || canFetchCache) {
			final long currentMillis = System.currentTimeMillis();
			final long previousExpiry = expiry;
			boolean loaded = false;
			if (data == null || currentMillis > expiry) {
				data = dataSupplier.get();
				loaded = true;
				canFetchCache = false;
			}
			expiry = currentMillis + lifespan;
			// Ordinary refreshes only extend the deadline, so the earlier sweep remains safe.
			// New data and a backwards clock (or overflow) can introduce an earlier deadline.
			if (data != null && (loaded || expiry < previousExpiry)) {
				EXPIRY_REVISION.incrementAndGet();
			}
		}
		return data;
	}

	public static void tick() {
		tick(System.currentTimeMillis());
	}

	static void tick(long currentMillis) {
		// The loading budget is renewed every tick, including those with no expiry work.
		canFetchCache = true;
		final long revision = EXPIRY_REVISION.get();
		if (currentMillis <= nextExpiry && revision == scannedExpiryRevision && COLLECTED_RESOURCES.poll() == null) {
			return;
		}
		// Drain before scanning: a collection after its entry was visited must wake the next tick.
		while (COLLECTED_RESOURCES.poll() != null) {
		}
		synchronized (CACHED_RESOURCES) {
			long earliest = Long.MAX_VALUE;
			int retained = 0;
			for (int i = 0; i < CACHED_RESOURCES.size(); i++) {
				final WeakReference<CachedResource<?>> reference = CACHED_RESOURCES.get(i);
				final CachedResource<?> cachedResource = reference.get();
				if (cachedResource != null) {
					// Compact once rather than shifting the array for every collected reference.
					if (retained != i) {
						CACHED_RESOURCES.set(retained, reference);
					}
					retained++;
					if (cachedResource.data != null) {
						if (currentMillis > cachedResource.expiry) {
							cachedResource.data = null;
						} else {
							earliest = Math.min(earliest, cachedResource.expiry);
						}
					}
				}
			}
			CACHED_RESOURCES.size(retained);
			nextExpiry = earliest;
			// A load while this scan ran must invalidate the next tick, not get lost here.
			scannedExpiryRevision = revision;
		}
	}
}
