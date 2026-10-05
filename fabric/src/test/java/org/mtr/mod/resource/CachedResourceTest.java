package org.mtr.mod.resource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises exact expiry and budget behavior without sleeping for real resource lifetimes. */
final class CachedResourceTest {

	@BeforeEach
	void resetRegistry() throws Exception {
		registry().clear();
		while (queue().poll() != null) {
		}
		((AtomicLong) field("EXPIRY_REVISION").get(null)).set(0);
		field("scannedExpiryRevision").setLong(null, -1);
		field("nextExpiry").setLong(null, Long.MIN_VALUE);
		field("canFetchCache").setBoolean(null, false);
	}

	@Test
	void unusedCachesDoNotRequireRepeatedSweepsAndBudgetStillRenews() throws Exception {
		final AtomicInteger loads = new AtomicInteger();
		final CachedResource<Integer> first = new CachedResource<>(loads::incrementAndGet, 60000);
		final CachedResource<Integer> second = new CachedResource<>(loads::incrementAndGet, 60000);
		CachedResource.tick();
		assertEquals(Long.MAX_VALUE, field("nextExpiry").getLong(null));
		assertEquals(1, first.getData(false));
		assertNull(second.getData(false));
		CachedResource.tick();
		assertEquals(2, second.getData(false));
		CachedResource.tick();
		final long scanned = field("scannedExpiryRevision").getLong(null);
		for (int i = 0; i < 100; i++) {
			field("canFetchCache").setBoolean(null, false);
			CachedResource.tick();
			assertTrue(field("canFetchCache").getBoolean(null));
			assertEquals(scanned, field("scannedExpiryRevision").getLong(null));
		}
		assertEquals(2, loads.get());
	}

	@Test
	void lifespanBoundaryIsStrictAndDataClearsOnTheFirstLaterTick() throws Exception {
		for (long lifespan : new long[]{60000, Integer.MAX_VALUE, 0, -1, Long.MAX_VALUE}) {
			resetRegistry();
			final Object value = new Object();
			final CachedResource<Object> cache = new CachedResource<>(() -> value, lifespan);
			assertSame(value, cache.getData(true));
			final long expiry = expiry(cache);
			CachedResource.tick(expiry);
			assertSame(value, data(cache));
			CachedResource.tick(expiry + 1);
			assertNull(data(cache));
		}
	}

	@Test
	void ordinaryRefreshKeepsTheConservativeEarlierDeadline() throws Exception {
		final Object value = new Object();
		final CachedResource<Object> cache = new CachedResource<>(() -> value, 60000);
		cache.getData(true);
		// Simulate the previously observed clock being one second earlier.
		final long earlier = expiry(cache) - 1000;
		field("expiry").setLong(cache, earlier);
		CachedResource.tick(earlier - 1);
		final long revision = revision();
		assertSame(value, cache.getData(false));
		assertTrue(expiry(cache) > earlier);
		assertEquals(revision, revision());
		assertEquals(earlier, field("nextExpiry").getLong(null));
		CachedResource.tick(earlier + 1);
		assertSame(value, data(cache));
		assertEquals(expiry(cache), field("nextExpiry").getLong(null));
	}

	@Test
	void backwardsClockRefreshInvalidatesThePreviousLaterDeadline() throws Exception {
		final CachedResource<Object> cache = new CachedResource<>(Object::new, 60000);
		final Object value = cache.getData(true);
		// A previous clock reading left a later expiry; the next real reading moves it back.
		field("expiry").setLong(cache, Long.MAX_VALUE);
		CachedResource.tick(0);
		final long revision = revision();
		assertSame(value, cache.getData(false));
		assertTrue(revision() > revision);
		CachedResource.tick(expiry(cache) + 1);
		assertNull(data(cache));
	}

	@Test
	void forcedLoadsBypassBudgetAndNewCachesInvalidateAnIdleRegistry() throws Exception {
		CachedResource.tick();
		assertEquals(Long.MAX_VALUE, field("nextExpiry").getLong(null));
		final AtomicInteger loads = new AtomicInteger();
		final CachedResource<Integer> first = new CachedResource<>(loads::incrementAndGet, 60000);
		final CachedResource<Integer> second = new CachedResource<>(loads::incrementAndGet, 60000);
		assertEquals(1, first.getData(true));
		assertNull(second.getData(false));
		assertEquals(2, second.getData(true));
		CachedResource.tick(Math.max(expiry(first), expiry(second)) + 1);
		assertNull(data(first));
		assertNull(data(second));
	}

	@Test
	void nullAndThrowingSuppliersKeepTheirExistingBudgetAndExpiryBehavior() throws Exception {
		final AtomicInteger loads = new AtomicInteger();
		final CachedResource<Object> empty = new CachedResource<>(() -> { loads.incrementAndGet(); return null; }, 60000);
		CachedResource.tick();
		final long revision = revision();
		assertNull(empty.getData(false));
		assertFalse(field("canFetchCache").getBoolean(null));
		assertEquals(revision, revision());
		assertTrue(expiry(empty) > 0);
		assertNull(empty.getData(false));
		assertEquals(1, loads.get());
		CachedResource.tick();
		assertNull(empty.getData(false));
		assertEquals(2, loads.get());
		final CachedResource<Object> throwing = new CachedResource<>(() -> { throw new IllegalStateException("supplier"); }, 60000);
		CachedResource.tick();
		assertThrows(IllegalStateException.class, () -> throwing.getData(false));
		assertTrue(field("canFetchCache").getBoolean(null));
		assertEquals(0, expiry(throwing));
		assertEquals(revision, revision());
	}

	@Test
	void collectionWakesAnIdleRegistryAndCompactsAllDeadEntries() throws Exception {
		final ObjectArrayList<CachedResource<Object>> live = new ObjectArrayList<>();
		for (int i = 0; i < 12; i++) live.add(new CachedResource<>(Object::new, 60000));
		CachedResource.tick();
		final ObjectArrayList<WeakReference<CachedResource<?>>> references = new ObjectArrayList<>(registry());
		for (int i = 0; i < references.size(); i += 2) {
			references.get(i).clear();
			assertTrue(references.get(i).enqueue());
		}
		CachedResource.tick();
		assertEquals(6, registry().size());
		for (int i = 0; i < 6; i++) assertSame(live.get(i * 2 + 1), registry().get(i).get());
		assertNull(queue().poll());
	}

	@Test
	void aLoadAfterItsEntryWasScannedInvalidatesTheFollowingTick() throws Exception {
		final CachedResource<Object> first = new CachedResource<>(Object::new, -1);
		final CachedResource<Object> second = new CachedResource<>(Object::new, Integer.MAX_VALUE);
		second.getData(true);
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch resume = new CountDownLatch(1);
		registry().set(1, new WeakReference<CachedResource<?>>(second) {
			@Override
			public CachedResource<?> get() {
				entered.countDown();
				try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
				catch (InterruptedException exception) { throw new AssertionError(exception); }
				return super.get();
			}
		});
		final long now = System.currentTimeMillis() + 60000;
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final Thread scan = new Thread(() -> {
			try { CachedResource.tick(now); }
			catch (Throwable throwable) { failure.set(throwable); }
		}, "cache-expiry-test");
		scan.start();
		try {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			assertNotNull(first.getData(true));
		} finally {
			resume.countDown();
			scan.join(5000);
		}
		assertFalse(scan.isAlive());
		assertNull(failure.get());
		assertNotNull(data(first));
		assertTrue(revision() > field("scannedExpiryRevision").getLong(null));
		CachedResource.tick(now);
		assertNull(data(first));
	}

	private static Field field(String name) throws Exception {
		final Field field = CachedResource.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static Object data(CachedResource<?> resource) throws Exception { return field("data").get(resource); }
	private static long expiry(CachedResource<?> resource) throws Exception { return field("expiry").getLong(resource); }
	private static long revision() throws Exception { return ((AtomicLong) field("EXPIRY_REVISION").get(null)).get(); }
	@SuppressWarnings("unchecked")
	private static ObjectArrayList<WeakReference<CachedResource<?>>> registry() throws Exception {
		return (ObjectArrayList<WeakReference<CachedResource<?>>>) field("CACHED_RESOURCES").get(null);
	}
	@SuppressWarnings("unchecked")
	private static ReferenceQueue<CachedResource<?>> queue() throws Exception {
		return (ReferenceQueue<CachedResource<?>>) field("COLLECTED_RESOURCES").get(null);
	}
}
