package org.mtr.mod.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.*;

/** Keeps the public ArrayMap type and insertion order while avoiding linear key lookups. */
final class HashBackedArrayMap<K, V> extends Object2ObjectArrayMap<K, V> {

	private Object2ObjectLinkedOpenHashMap<K, V> delegate = new Object2ObjectLinkedOpenHashMap<>();

	@Override
	public FastEntrySet<K, V> object2ObjectEntrySet() {
		return delegate.object2ObjectEntrySet();
	}

	@Override
	public V get(Object key) {
		return delegate.get(key);
	}

	@Override
	public int size() {
		return delegate.size();
	}

	@Override
	public void clear() {
		delegate.clear();
	}

	@Override
	public boolean containsKey(Object key) {
		return delegate.containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		return delegate.containsValue(value);
	}

	@Override
	public boolean isEmpty() {
		return delegate.isEmpty();
	}

	@Override
	public V put(K key, V value) {
		return delegate.put(key, value);
	}

	@Override
	public V remove(Object key) {
		return delegate.remove(key);
	}

	@Override
	public ObjectSet<K> keySet() {
		return delegate.keySet();
	}

	@Override
	public ObjectCollection<V> values() {
		return delegate.values();
	}

	@Override
	public void defaultReturnValue(V value) {
		super.defaultReturnValue(value);
		delegate.defaultReturnValue(value);
	}

	@Override
	public HashBackedArrayMap<K, V> clone() {
		final HashBackedArrayMap<K, V> copy = new HashBackedArrayMap<>();
		copy.defaultReturnValue(defaultReturnValue());
		copy.delegate = delegate.clone();
		return copy;
	}
}
