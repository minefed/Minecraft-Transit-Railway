package org.mtr.mod.render;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RenderQueueTest {

	@Test
	public void preservesTextureAndCallbackOrderAcrossEqualKeysAndHashCollisions() {
		final RenderQueue<String, Integer> queue = new RenderQueue<>();
		queue.add("Aa", 1);
		queue.add("BB", 2); // Same String hash as Aa, but a different texture key.
		queue.add(new String("Aa"), 3);
		Assertions.assertEquals(Arrays.asList("Aa:1", "Aa:3", "BB:2"), snapshot(queue));
		queue.remove(new String("Aa"));
		queue.add("Aa", 4);
		Assertions.assertEquals(Arrays.asList("BB:2", "Aa:4"), snapshot(queue));
	}

	@Test
	public void recyclingKeepsFramesIndependentAndFollowsEachFramesFirstUse() {
		RenderQueue<String, Integer> pending = new RenderQueue<>();
		RenderQueue<String, Integer> current = new RenderQueue<>();
		pending.add("first", 1);
		pending.add("second", 2);
		for (int frame = 0; frame < 10; frame++) {
			final RenderQueue<String, Integer> swap = pending;
			pending = current;
			current = swap;
			pending.clear();
			final List<String> previous = snapshot(current);
			pending.add("second", 20 + frame);
			pending.add("first", 10 + frame);
			Assertions.assertEquals(previous, snapshot(current));
			Assertions.assertEquals(Arrays.asList("second:" + (20 + frame), "first:" + (10 + frame)), snapshot(pending));
			pending.remove("first");
			current.remove("first");
			Assertions.assertEquals(1, snapshot(pending).size());
			Assertions.assertEquals(1, snapshot(current).size());
		}
	}

	@Test
	public void cancellationInsideCallbacksDoesNotSkipUnrelatedBucketsOrFailIteration() {
		final RenderQueue<String, Runnable> queue = new RenderQueue<>();
		final List<String> rendered = new ArrayList<>();
		queue.add("first", () -> {
			rendered.add("first-1");
			queue.remove("first");
			queue.remove("second");
		});
		queue.add("first", () -> rendered.add("first-2"));
		queue.add("second", () -> rendered.add("cancelled-second"));
		queue.add("third", () -> {
			rendered.add("third");
			queue.remove("first");
			queue.remove("fourth");
		});
		queue.add("fourth", () -> rendered.add("cancelled-fourth"));
		queue.add("fifth", () -> rendered.add("fifth"));
		queue.forEach((key, callbacks) -> callbacks.forEach(Runnable::run));
		Assertions.assertEquals(Arrays.asList("first-1", "first-2", "third", "fifth"), rendered);
		queue.clear();
		queue.add("first", () -> rendered.add("new-first"));
		queue.forEach((key, callbacks) -> callbacks.forEach(Runnable::run));
		Assertions.assertEquals(Arrays.asList("first-1", "first-2", "third", "fifth", "new-first"), rendered);
	}

	@Test
	public void cancelledAndReaddedKeysCannotReplaceAnEntryAlreadyBeingTraversed() {
		final RenderQueue<String, Integer> queue = new RenderQueue<>();
		queue.add("first", 1);
		queue.add("second", 2);
		queue.add("third", 3);
		final List<String> rendered = new ArrayList<>();
		queue.forEach((key, callbacks) -> {
			if (key.equals("first")) {
				queue.remove("second");
				queue.add("second", 4);
			}
			callbacks.forEach(value -> rendered.add(key + ":" + value));
		});
		Assertions.assertEquals(Arrays.asList("first:1", "third:3"), rendered);
		Assertions.assertEquals(Arrays.asList("first:1", "third:3", "second:4"), snapshot(queue));
	}

	@Test
	public void poolReleasesCallbacksAndBoundsRetainedArrays() throws Exception {
		final RenderQueue<String, Integer> queue = new RenderQueue<>();
		for (int bucket = 0; bucket < 100; bucket++) {
			for (int item = 0; item < 100; item++) {
				queue.add("texture" + bucket, item);
			}
		}
		for (int item = 0; item < 10000; item++) {
			queue.add("oversized", item);
		}
		queue.clear();
		final Field poolField = RenderQueue.class.getDeclaredField("pool");
		poolField.setAccessible(true);
		final ArrayDeque<?> pool = (ArrayDeque<?>) poolField.get(queue);
		int capacity = 0;
		Assertions.assertFalse(pool.isEmpty());
		Assertions.assertTrue(pool.size() <= 64);
		for (final Object pooled : pool) {
			final ObjectArrayList<?> list = (ObjectArrayList<?>) pooled;
			Assertions.assertTrue(list.isEmpty());
			capacity += list.elements().length;
			for (final Object element : list.elements()) {
				Assertions.assertNull(element);
			}
		}
		Assertions.assertTrue(capacity <= 4096);
		Assertions.assertTrue(snapshot(queue).isEmpty());
	}

	private static List<String> snapshot(RenderQueue<String, Integer> queue) {
		final List<String> result = new ArrayList<>();
		queue.forEach((key, values) -> values.forEach(value -> result.add(key + ":" + value)));
		return result;
	}
}
