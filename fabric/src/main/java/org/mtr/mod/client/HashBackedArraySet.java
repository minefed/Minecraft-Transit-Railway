package org.mtr.mod.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectSpliterator;

/** Keeps the public ArraySet type and insertion order while avoiding linear membership checks. */
final class HashBackedArraySet<K> extends ObjectArraySet<K> {

	private ObjectLinkedOpenHashSet<K> delegate = new ObjectLinkedOpenHashSet<>();

	@Override
	public ObjectIterator<K> iterator() {
		return delegate.iterator();
	}

	@Override
	public ObjectSpliterator<K> spliterator() {
		return delegate.spliterator();
	}

	@Override
	public boolean contains(Object value) {
		return delegate.contains(value);
	}

	@Override
	public int size() {
		return delegate.size();
	}

	@Override
	public boolean remove(Object value) {
		return delegate.remove(value);
	}

	@Override
	public boolean add(K value) {
		return delegate.add(value);
	}

	@Override
	public void clear() {
		delegate.clear();
	}

	@Override
	public boolean isEmpty() {
		return delegate.isEmpty();
	}

	@Override
	public Object[] toArray() {
		return delegate.toArray();
	}

	@Override
	public <T> T[] toArray(T[] array) {
		return delegate.toArray(array);
	}

	@Override
	public HashBackedArraySet<K> clone() {
		final HashBackedArraySet<K> copy = new HashBackedArraySet<>();
		copy.delegate = delegate.clone();
		return copy;
	}
}
