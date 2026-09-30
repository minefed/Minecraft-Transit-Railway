package org.mtr.mod.render;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.LightType;
import org.mtr.mapping.holder.LightmapTextureManager;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public final class LightSamplingEquivalenceTest {

	@Test
	public void publicConstructorsReadFreshBlockThenSkyAtTheOriginalPosition() {
		final MinecraftClient client = mock(MinecraftClient.class);
		final ClientWorld world = mock(ClientWorld.class);
		final List<String> trace = new ArrayList<>();
		final int[] samples = {0};
		when(client.getWorldMapped()).thenAnswer(call -> { trace.add("world"); return world; });
		when(world.getLightLevel(any(), any())).thenAnswer(call -> {
			final LightType type = call.getArgument(0);
			final BlockPos position = call.getArgument(1);
			trace.add(type + ":" + position.getX() + "," + position.getY() + "," + position.getZ());
			return ++samples[0] % 16;
		});
		try (MockedStatic<MinecraftClient> clients = mockStatic(MinecraftClient.class)) {
			clients.when(MinecraftClient::getInstance).thenReturn(client);
			for (Vector position : List.of(new Vector(0, 64, 0), new Vector(-0.000001, -1.000001, 3.9),
					new Vector(Integer.MAX_VALUE, Integer.MIN_VALUE, -29_999_999.75))) {
				final BlockPos expectedPosition = Init.newBlockPos(position.x, position.y + 1, position.z);
				final String coordinates = expectedPosition.getX() + "," + expectedPosition.getY() + "," + expectedPosition.getZ();
				for (int repeat = 0; repeat < 3; repeat++) {
					trace.clear();
					final int before = samples[0];
					final PositionAndRotation actual = new PositionAndRotation(position, 0.5, -0.75);
					assertSame(position, actual.position);
					assertEquals(List.of("world", "BLOCK:" + coordinates, "SKY:" + coordinates), trace);
					assertEquals(LightmapTextureManager.pack((before + 1) % 16, (before + 2) % 16), actual.light);
					assertEquals(before + 2, samples[0], "Light changes must be sampled on every call");
				}
			}
			when(client.getWorldMapped()).thenReturn(null);
			trace.clear();
			assertEquals(0, new PositionAndRotation(new Vector(0, 0, 0), 0, 0).light);
			assertTrue(trace.isEmpty(), "No light reads when there is no world");
		}
	}
}
