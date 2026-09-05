package org.mtr.mod.render;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** A render-thread queue preserving first texture use and callback insertion order. */
final class RenderQueue<K, V> {

	private static final int MAX_POOLED_BUCKETS = 64;
	private static final int MAX_POOLED_CAPACITY = 4096;
	private final Map<K, Bucket<K, V>> buckets = new HashMap<>();
	private final ObjectArrayList<Bucket<K, V>> orderedBuckets = new ObjectArrayList<>();
	private final ArrayDeque<ObjectArrayList<V>> pool = new ArrayDeque<>();
	private int pooledCapacity;

	void add(K key, V value) {
		Bucket<K, V> bucket = buckets.get(key);
		if (bucket == null) {
			ObjectArrayList<V> callbacks = pool.pollFirst();
			if (callbacks == null) {
				callbacks = new ObjectArrayList<>();
			} else {
				pooledCapacity -= callbacks.elements().length;
			}
			bucket = new Bucket<>(key, callbacks);
			buckets.put(key, bucket);
			orderedBuckets.add(bucket);
		}
		bucket.callbacks.add(value);
	}

	void forEach(BiConsumer<K, ObjectArrayList<V>> consumer) {
		// A renderer can synchronously cancel textures. Keep stable order entries
		// instead of iterating a map that fails or moves entries during removal.
		// The active bucket finishes; cancelled buckets not yet started are skipped.
		final int size = orderedBuckets.size();
		for (int i = 0; i < size; i++) {
			final Bucket<K, V> bucket = orderedBuckets.get(i);
			if (!bucket.cancelled) {
				consumer.accept(bucket.key, bucket.callbacks);
			}
		}
	}

	void remove(K key) {
		// A cancelled bucket might still be held by a caller. Only recycle buckets
		// when the owning double buffer is cleared after rendering has finished.
		final Bucket<K, V> bucket = buckets.remove(key);
		if (bucket != null) {
			bucket.cancelled = true;
		}
	}

	void clear() {
		orderedBuckets.forEach(bucket -> {
			bucket.callbacks.clear();
			final int capacity = bucket.callbacks.elements().length;
			if (pool.size() < MAX_POOLED_BUCKETS && capacity <= MAX_POOLED_CAPACITY - pooledCapacity) {
				pool.addLast(bucket.callbacks);
				pooledCapacity += capacity;
			}
		});
		// Clear the keys too: next frame's texture order must follow that frame's
		// first use, and expired dynamic textures must not be retained by the pool.
		buckets.clear();
		orderedBuckets.clear();
	}

	private static final class Bucket<K, V> {
		private final K key;
		private final ObjectArrayList<V> callbacks;
		private boolean cancelled;

		private Bucket(K key, ObjectArrayList<V> callbacks) {
			this.key = key;
			this.callbacks = callbacks;
		}
	}
}
