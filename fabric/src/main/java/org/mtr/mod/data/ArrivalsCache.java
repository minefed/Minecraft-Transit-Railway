package org.mtr.mod.data;

import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;

import java.util.function.Consumer;

public abstract class ArrivalsCache {

	private long nextRequest;
	private final Long2IntOpenHashMap queuedPlatformIdsWithAge = new Long2IntOpenHashMap();
	private final ObjectArrayList<ArrivalResponse> arrivalResponseCache = new ObjectArrayList<>();
	private final int cachedMillis;
	private static final int PERSISTENT_AGE = 5;
	private static final int HASH_LOOKUP_THRESHOLD = 8;

	protected ArrivalsCache(int cachedMillis) {
		this.cachedMillis = cachedMillis;
	}

	public final ObjectArrayList<ArrivalResponse> requestArrivals(LongCollection platformIds) {
		if (queuedPlatformIdsWithAge.isEmpty() && canSendRequest()) {
			nextRequest = System.currentTimeMillis() + 100;
		}

		platformIds.forEach(platformId -> queuedPlatformIdsWithAge.put(platformId, 0));

		final LongCollection platformIdsForLookup = platformIds.size() > HASH_LOOKUP_THRESHOLD && !(platformIds instanceof LongOpenHashSet) ? new LongOpenHashSet(platformIds) : platformIds;
		final ObjectArrayList<ArrivalResponse> arrivals = new ObjectArrayList<>();
		arrivalResponseCache.forEach(arrivalResponse -> {
			if (platformIdsForLookup.contains(arrivalResponse.getPlatformId())) {
				arrivals.add(arrivalResponse);
			}
		});

		return arrivals;
	}

	public final void tick() {
		if (!queuedPlatformIdsWithAge.isEmpty() && canSendRequest()) {
			final LongAVLTreeSet platformIds = new LongAVLTreeSet(queuedPlatformIdsWithAge.keySet());

			requestArrivalsFromServer(platformIds, arrivalResponseList -> {
				arrivalResponseCache.clear();
				arrivalResponseCache.addAll(arrivalResponseList);
				onArrivalsUpdated();
			});

			platformIds.forEach(platformId -> queuedPlatformIdsWithAge.compute(platformId, (key, age) -> age > PERSISTENT_AGE ? null : age + 1));
			nextRequest = System.currentTimeMillis() + cachedMillis;
		}
	}

	private boolean canSendRequest() {
		return System.currentTimeMillis() >= nextRequest;
	}

	public abstract long getMillisOffset();

	protected void onArrivalsUpdated() {
	}

	protected abstract void requestArrivalsFromServer(LongAVLTreeSet platformIds, Consumer<ObjectList<ArrivalResponse>> callback);
}
