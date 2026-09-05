package org.mtr.test;

import org.junit.jupiter.api.Test;
import org.mtr.core.data.TransportMode;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.data.PersistentVehicleData;
import org.mtr.mod.resource.DoorAnimationType;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class DoorInterpolationKeyTest {

	@Test
	public void animationStatesRetainTheOriginalStringKeyEquivalence() throws ReflectiveOperationException {
		final PersistentVehicleData data = new PersistentVehicleData(new ObjectImmutableList<>(new ObjectArrayList<>()), TransportMode.TRAIN);
		final Field field = PersistentVehicleData.class.getDeclaredField("doorMovementInterpolations");
		field.setAccessible(true);
		final Long2ObjectOpenHashMap<?> states = (Long2ObjectOpenHashMap<?>) field.get(data);
		final Map<String, Object> originalKeys = new HashMap<>();
		final Map<Object, String> owners = new IdentityHashMap<>();
		final double[] multipliers = {0.0, -0.0, 1, -1, Math.PI, Math.nextUp(1.0), Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, Double.longBitsToDouble(0x7ff8000000000001L)};

		for (int repetition = 0; repetition < 2; repetition++) {
			for (final double multiplier : multipliers) {
				for (final DoorAnimationType animation : DoorAnimationType.values()) {
					for (final boolean flipped : new boolean[]{false, true}) {
						data.getInterpolatedDoorValue(animation, multiplier, flipped, 0, false);
						final Object[] animations = (Object[]) states.get(Double.doubleToLongBits(multiplier));
						final Object state = animations[animation.ordinal() * 2 + (flipped ? 1 : 0)];
						final String originalKey = multiplier + "_" + animation + "_" + flipped;
						assertNotNull(state);
						if (originalKeys.containsKey(originalKey)) {
							assertSame(originalKeys.get(originalKey), state, originalKey);
						} else {
							assertNull(owners.put(state, originalKey), originalKey);
							originalKeys.put(originalKey, state);
						}
					}
				}
			}
		}
		assertEquals((multipliers.length - 1) * DoorAnimationType.values().length * 2, originalKeys.size());
	}
}
