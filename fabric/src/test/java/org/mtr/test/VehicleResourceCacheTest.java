package org.mtr.test;

import org.junit.jupiter.api.Test;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.resource.CachedResource;
import org.mtr.mod.resource.OptimizedModelWrapper;
import org.mtr.mod.resource.PartCondition;
import org.mtr.mod.resource.VehicleResourceCache;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public final class VehicleResourceCacheTest {

	@Test
	public void viewRefreshesAllModelCachesAndTracksEachReplacement() throws ReflectiveOperationException {
		final Fixture fixture = new Fixture();
		VehicleResourceCache previous = fixture.getView(true);
		assertNotNull(previous);
		assertSame(previous, fixture.getView(true));
		assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.loads);

		final Field expiry = field(CachedResource.class, "expiry");
		for (int changedModel = 0; changedModel < 4; changedModel++) {
			for (final CachedResource<?> resource : fixture.resources) {
				expiry.setLong(resource, System.currentTimeMillis() + 1000);
			}
			expiry.setLong(fixture.resources.get(changedModel), -1);
			final long beforeRefresh = System.currentTimeMillis();
			final VehicleResourceCache refreshed = fixture.getView(true);
			assertNotNull(refreshed);
			assertNotSame(previous, refreshed);
			assertSame(previous.floors, refreshed.floors);
			assertSame(previous.doorways, refreshed.doorways);
			for (int model = 0; model < 4; model++) {
				if (model == changedModel) {
					assertNotSame(model(previous, model), model(refreshed, model));
				} else {
					assertSame(model(previous, model), model(refreshed, model));
				}
				assertTrue(expiry.getLong(fixture.resources.get(model)) >= beforeRefresh + Fixture.LIFESPAN);
			}
			previous = refreshed;
		}
		assertArrayEquals(new int[]{2, 2, 2, 2}, fixture.loads);
	}

	@Test
	public void missingModelDoesNotReturnAnOldViewOrBypassTheLoadingBudget() throws ReflectiveOperationException {
		final Fixture fixture = new Fixture();
		final VehicleResourceCache previous = fixture.getView(true);
		final Field canFetchCache = field(CachedResource.class, "canFetchCache");
		final boolean previousCanFetch = canFetchCache.getBoolean(null);
		try {
			field(CachedResource.class, "data").set(fixture.resources.get(2), null);
			canFetchCache.setBoolean(null, false);
			assertNull(fixture.getView(false));
			assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.loads);
			final VehicleResourceCache refreshed = fixture.getView(true);
			assertNotSame(previous, refreshed);
			assertNotSame(previous.optimizedModelsBogie1, refreshed.optimizedModelsBogie1);
			assertArrayEquals(new int[]{1, 1, 2, 1}, fixture.loads);
		} finally {
			canFetchCache.setBoolean(null, previousCanFetch);
		}
	}

	@Test
	public void collectedViewCanBeRebuiltWithoutRegeneratingModels() throws ReflectiveOperationException {
		final Fixture fixture = new Fixture();
		final VehicleResourceCache previous = fixture.getView(true);
		((WeakReference<?>) field(fixture.holder.getClass(), "cacheView").get(fixture.holder)).clear();
		final VehicleResourceCache refreshed = fixture.getView(true);
		assertNotSame(previous, refreshed);
		for (int model = 0; model < 4; model++) {
			assertSame(model(previous, model), model(refreshed, model));
		}
		assertArrayEquals(new int[]{1, 1, 1, 1}, fixture.loads);
	}

	private static Object model(VehicleResourceCache cache, int index) {
		switch (index) {
			case 0:
				return cache.optimizedModels;
			case 1:
				return cache.optimizedModelsDoorsClosed;
			case 2:
				return cache.optimizedModelsBogie1;
			default:
				return cache.optimizedModelsBogie2;
		}
	}

	private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static final class Fixture {

		private static final long LIFESPAN = 60000;
		private final int[] loads = new int[4];
		private final ObjectArrayList<CachedResource<Object2ObjectOpenHashMap<PartCondition, OptimizedModelWrapper>>> resources = new ObjectArrayList<>();
		private final Object holder;
		private final Method getView;

		private Fixture() throws ReflectiveOperationException {
			for (int i = 0; i < 4; i++) {
				final int index = i;
				resources.add(new CachedResource<>(() -> {
					loads[index]++;
					return new Object2ObjectOpenHashMap<>();
				}, LIFESPAN));
			}
			final Class<?> type = Class.forName("org.mtr.mod.resource.VehicleResource$VehicleResourceCacheHolder");
			final Constructor<?> constructor = type.getDeclaredConstructor(ObjectImmutableList.class, ObjectImmutableList.class, CachedResource.class, CachedResource.class, CachedResource.class, CachedResource.class);
			constructor.setAccessible(true);
			holder = constructor.newInstance(new ObjectImmutableList<>(new ObjectArrayList<Box>()), new ObjectImmutableList<>(new ObjectArrayList<Box>()), resources.get(0), resources.get(1), resources.get(2), resources.get(3));
			getView = type.getDeclaredMethod("getView", boolean.class);
			getView.setAccessible(true);
		}

		private VehicleResourceCache getView(boolean force) throws ReflectiveOperationException {
			return (VehicleResourceCache) getView.invoke(holder, force);
		}
	}
}
