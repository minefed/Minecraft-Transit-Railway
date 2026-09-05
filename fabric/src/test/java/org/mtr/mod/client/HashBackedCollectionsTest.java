package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;

import java.io.*;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public final class HashBackedCollectionsTest {

	@Test
	public void mapPreservesArrayMapOrderAndMutationViews() throws Exception {
		final Object2ObjectArrayMap<String, Integer> expected = new Object2ObjectArrayMap<>();
		final HashBackedArrayMap<String, Integer> actual = new HashBackedArrayMap<>();
		expected.defaultReturnValue(-1);
		actual.defaultReturnValue(-1);
		final Random random = new Random(92738);
		for (int i = 0; i < 2000; i++) {
			final String key = i % 19 == 0 ? null : "rail-" + random.nextInt(100);
			final Integer value = i % 17 == 0 ? null : random.nextInt(20);
			switch (random.nextInt(7)) {
				case 0:
					assertEquals(expected.remove(key), actual.remove(key));
					break;
				case 1:
					assertEquals(expected.keySet().remove(key), actual.keySet().remove(key));
					break;
				case 2:
					assertEquals(expected.values().remove(value), actual.values().remove(value));
					break;
				case 3:
					assertEquals(expected.putIfAbsent(key, value), actual.putIfAbsent(key, value));
					break;
				default:
					assertEquals(expected.put(key, value), actual.put(key, value));
			}
			assertEquals(expected.get(key), actual.get(key));
			assertEquals(expected.containsKey(key), actual.containsKey(key));
			assertEquals(expected.getOrDefault(key, -2), actual.getOrDefault(key, -2));
			assertEquals(expected, actual);
			assertArrayEquals(expected.keySet().toArray(), actual.keySet().toArray());
			assertArrayEquals(expected.values().toArray(), actual.values().toArray());
		}
		assertEquals(expected, roundTrip(actual));
		assertArrayEquals(expected.keySet().toArray(), roundTrip(actual).keySet().toArray());
		final HashBackedArrayMap<String, Integer> copy = actual.clone();
		actual.clear();
		assertEquals(expected, copy);
		assertEquals(-1, copy.get("missing"));
		assertTrue(actual.isEmpty());
	}

	@Test
	public void setPreservesArraySetOrderAndIteratorRemoval() throws Exception {
		final ObjectArraySet<String> expected = new ObjectArraySet<>();
		final HashBackedArraySet<String> actual = new HashBackedArraySet<>();
		final Random random = new Random(92138);
		for (int i = 0; i < 2000; i++) {
			final String value = i % 19 == 0 ? null : "rail-" + random.nextInt(100);
			if (i % 5 == 0) {
				assertEquals(expected.remove(value), actual.remove(value));
			} else {
				assertEquals(expected.add(value), actual.add(value));
			}
			if (i % 31 == 0 && !expected.isEmpty()) {
				final java.util.Iterator<String> oldIterator = expected.iterator();
				final java.util.Iterator<String> newIterator = actual.iterator();
				assertEquals(oldIterator.next(), newIterator.next());
				oldIterator.remove();
				newIterator.remove();
			}
			assertEquals(expected.contains(value), actual.contains(value));
			assertEquals(expected, actual);
			assertArrayEquals(expected.toArray(), actual.toArray());
			assertArrayEquals(expected.toArray(new String[0]), actual.toArray(new String[0]));
			assertEquals(Arrays.asList(expected.toArray()), actual.stream().collect(java.util.stream.Collectors.toList()));
		}
		assertArrayEquals(expected.toArray(), roundTrip(actual).toArray());
		final HashBackedArraySet<String> copy = actual.clone();
		actual.clear();
		assertEquals(expected, copy);
		assertTrue(actual.isEmpty());
	}

	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(T value) throws IOException, ClassNotFoundException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (final ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(value);
		}
		try (final ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (T) input.readObject();
		}
	}
}
