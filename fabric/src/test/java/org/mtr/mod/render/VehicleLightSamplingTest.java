package org.mtr.mod.render;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.data.PersistentVehicleData;
import org.mtr.mod.data.VehicleExtension;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class VehicleLightSamplingTest {

	@BeforeAll
	public static void bootstrap() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	public void carGeometryIsUnchangedAndPublicCarConstructorStillSamplesEagerly() {
		final MinecraftClient client = mock(MinecraftClient.class);
		final ClientWorld world = mock(ClientWorld.class);
		when(client.getWorldMapped()).thenReturn(world);
		when(world.getLightLevel(eq(LightType.BLOCK), any())).thenReturn(3);
		when(world.getLightLevel(eq(LightType.SKY), any())).thenReturn(11);
		final VehicleCar car = car("geometry");
		when(car.getLength()).thenReturn(19.5);
		when(car.getBogie1Position()).thenReturn(-5.75);
		when(car.getBogie2Position()).thenReturn(6.25);
		try (MockedStatic<MinecraftClient> clients = mockStatic(MinecraftClient.class)) {
			clients.when(MinecraftClient::getInstance).thenReturn(client);
			for (int bogieCount = 0; bogieCount <= 3; bogieCount++) {
				final ObjectArrayList<PositionAndRotation> bogies = new ObjectArrayList<>();
				for (int i = 0; i < bogieCount; i++) {
					bogies.add(PositionAndRotation.forBogieTransform(new Vector(-17.25 + i * 3, -1.5 + i, 7.25), new Vector(-16.5 + i * 3, i, 9), true));
				}
				for (boolean hasPitch : new boolean[]{false, true}) {
					clearInvocations(client, world);
					final PositionAndRotation deferred = PositionAndRotation.forVehicleTransform(bogies, car, hasPitch);
					verifyNoInteractions(client, world);
					final PositionAndRotation eager = new PositionAndRotation(bogies, car, hasPitch);
					assertEquals(eager.position.x, deferred.position.x);
					assertEquals(eager.position.y, deferred.position.y);
					assertEquals(eager.position.z, deferred.position.z);
					assertEquals(eager.yaw, deferred.yaw);
					assertEquals(eager.pitch, deferred.pitch);
					assertEquals(LightmapTextureManager.pack(3, 11), eager.light);
					verify(world).getLightLevel(eq(LightType.BLOCK), any());
					verify(world).getLightLevel(eq(LightType.SKY), any());
					assertEquals(eager.light, deferred.sampleLight());
				}
			}
			when(client.getWorldMapped()).thenReturn(null);
			clearInvocations(world);
			assertEquals(0, PositionAndRotation.forVehicleTransform(new ObjectArrayList<>(), car, false).sampleLight());
			verifyNoInteractions(world);
		}
	}

	@Test
	public void renderPassesReadOnlyVisibleCarsAndRefreshAfterVisibilityOrLightChanges() throws Exception {
		final MinecraftClient client = mock(MinecraftClient.class, RETURNS_DEEP_STUBS);
		final ClientWorld world = mock(ClientWorld.class);
		final ClientPlayerEntity player = mock(ClientPlayerEntity.class);
		when(client.getWorldMapped()).thenReturn(world);
		when(client.getPlayerMapped()).thenReturn(player);
		when(client.getGameRendererMapped().getCamera().getPos()).thenReturn(new Vector3d(0, 64, 0));
		final MinecraftClientData data = new MinecraftClientData();
		final VehicleExtension vehicle = mock(VehicleExtension.class);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>(List.of(car("first"), car("second")));
		final PersistentVehicleData persistent = new PersistentVehicleData(new ObjectImmutableList<>(cars), TransportMode.TRAIN);
		final Field persistentData = VehicleExtension.class.getField("persistentVehicleData");
		persistentData.setAccessible(true);
		persistentData.set(vehicle, persistent);
		when(vehicle.getId()).thenReturn(17L);
		when(vehicle.getTransportMode()).thenReturn(TransportMode.TRAIN);
		final ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>>>> positions = new ObjectArrayList<>();
		positions.add(new ObjectObjectImmutablePair<>(cars.get(0), new ObjectArrayList<>(List.of(new ObjectObjectImmutablePair<>(new Vector(-0.25, 63.25, 0), new Vector(-0.25, 63.25, 2))))));
		positions.add(new ObjectObjectImmutablePair<>(cars.get(1), new ObjectArrayList<>(List.of(new ObjectObjectImmutablePair<>(new Vector(16.5, -1.25, -4), new Vector(16.5, -1.25, -2))))));
		when(vehicle.getSmoothedVehicleCarsAndPositions(anyLong())).thenReturn(positions);
		data.vehicles.add(vehicle);
		final List<String> trace = new ArrayList<>();
		final int[] light = {2};
		when(world.getLightLevel(any(), any())).thenAnswer(call -> {
			final BlockPos position = call.getArgument(1);
			trace.add(call.getArgument(0) + ":" + position.getX() + "," + position.getY() + "," + position.getZ() + "=" + light[0]);
			return light[0];
		});
		try (MockedStatic<MinecraftClient> clients = mockStatic(MinecraftClient.class);
			 MockedStatic<OptimizedRenderer> renderer = mockStatic(OptimizedRenderer.class);
			 MockedStatic<MinecraftClientData> clientData = mockStatic(MinecraftClientData.class);
			 MockedStatic<VehicleRidingMovement> riding = mockStatic(VehicleRidingMovement.class);
			 MockedStatic<CustomResourceLoader> resources = mockStatic(CustomResourceLoader.class)) {
			clients.when(MinecraftClient::getInstance).thenReturn(client);
			clientData.when(MinecraftClientData::getInstance).thenReturn(data);
			// Stop at resource lookup: the actual render entry point and visibility branch run,
			// while this test needs no GPU/model fixture.
			resources.when(() -> CustomResourceLoader.getVehicleById(any(), anyString(), any())).thenAnswer(call -> {
				trace.add("resource:" + call.getArgument(1));
				return null;
			});
			try {
				for (boolean shadows : new boolean[]{false, true}) {
					renderer.when(OptimizedRenderer::renderingShadows).thenReturn(shadows);
					persistent.rayTracing[0] = false;
					persistent.rayTracing[1] = false;
					riding.when(() -> VehicleRidingMovement.isRiding(17)).thenReturn(false);
					trace.clear();
					RenderVehicles.render(16, new Vector3d(0, 0, 0));
					assertTrue(trace.isEmpty(), "Occluded cars must still have geometry but no light or model lookup");
					persistent.rayTracing[0] = true;
					for (int level : new int[]{3, 12}) {
						light[0] = level;
						trace.clear();
						RenderVehicles.render(16, new Vector3d(0, 0, 0));
						assertEquals(List.of("BLOCK:-1,64,1=" + level, "SKY:-1,64,1=" + level, "resource:first"), trace);
					}
					persistent.rayTracing[0] = false;
					trace.clear();
					RenderVehicles.render(16, new Vector3d(0, 0, 0));
					assertTrue(trace.isEmpty(), "A car hidden again must stop sampling immediately");
					// The riding override deliberately renders every car even with failed culling.
					riding.when(() -> VehicleRidingMovement.isRiding(17)).thenReturn(true);
					light[0] = 7;
					trace.clear();
					RenderVehicles.render(16, new Vector3d(0, 0, 0));
					assertEquals(List.of("BLOCK:-1,64,1=7", "SKY:-1,64,1=7", "resource:first", "BLOCK:16,-1,-3=7", "SKY:16,-1,-3=7", "resource:second"), trace);
				}
				verify(vehicle, times(10)).getSmoothedVehicleCarsAndPositions(16);
			} finally {
				// RenderVehicles enqueues culling work but never starts the worker in this fixture.
				final Field queue = WorkerThread.class.getDeclaredField("occlusionQueueVehicle");
				queue.setAccessible(true);
				((Queue<?>) queue.get(MainRenderer.WORKER_THREAD)).clear();
			}
		}
	}

	private static VehicleCar car(String id) {
		final VehicleCar car = mock(VehicleCar.class);
		when(car.getVehicleId()).thenReturn(id);
		return car;
	}
}
