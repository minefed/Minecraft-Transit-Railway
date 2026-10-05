package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the production provider with real mapped wrappers and changing world/state identities. */
public final class CullingDataProviderTest {

	@BeforeAll
	public static void bootstrap() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	public void lookupsAndOpacityMatchAcrossWorldChangesAndCleanup() throws Exception {
		try (Fixture fixture = new Fixture()) {
			final DataProvider baseline = new BaselineProvider();
			final DataProvider optimized = createProvider();
			for (final net.minecraft.client.world.ClientWorld world : new net.minecraft.client.world.ClientWorld[]{fixture.first, fixture.first, fixture.second, null, fixture.first}) {
				fixture.world = world;
				for (final int[] position : new int[][]{{0, 64, 0}, {-17, -65, 15}, {16, 320, -16}, {30_000_000, 0, -30_000_000}}) {
					for (boolean opaque : new boolean[]{false, true}) {
						fixture.opaque = opaque;
						fixture.events.clear();
						final boolean expectedPrepared = baseline.prepareChunk(position[0] >> 4, position[2] >> 4);
						final boolean expected = baseline.isOpaqueFullCube(position[0], position[1], position[2]);
						final List<String> expectedEvents = List.copyOf(fixture.events);
						fixture.events.clear();
						assertEquals(expectedPrepared, optimized.prepareChunk(position[0] >> 4, position[2] >> 4));
						assertEquals(expected, optimized.isOpaqueFullCube(position[0], position[1], position[2]));
						assertEquals(expectedEvents, fixture.events);
					}
				}
				baseline.cleanup();
				optimized.cleanup();
				assertFalse(optimized.isOpaqueFullCube(0, 64, 0));
				assertNull(field(optimized, "clientWorld"));
				assertNull(field(optimized, "blockView"));
			}
		}
	}

	@Test
	public void onlyWorldReplacementOrCleanupReplacesTheView() throws Exception {
		try (Fixture fixture = new Fixture()) {
			final DataProvider provider = createProvider();
			fixture.world = fixture.first;
			assertTrue(provider.prepareChunk(0, 0));
			final Object firstView = field(provider, "blockView");
			final Object firstWorldWrapper = field(provider, "clientWorld");
			assertTrue(provider.prepareChunk(-1, 2));
			assertNotSame(firstWorldWrapper, field(provider, "clientWorld"), "Fresh world wrappers must not invalidate an unchanged native world");
			assertSame(firstView, field(provider, "blockView"));
			fixture.world = fixture.second;
			assertTrue(provider.prepareChunk(-1, 2));
			assertNotSame(firstView, field(provider, "blockView"));
			final Object secondView = field(provider, "blockView");
			fixture.world = null;
			assertFalse(provider.prepareChunk(-1, 2));
			assertNull(field(provider, "blockView"));
			fixture.world = fixture.second;
			assertTrue(provider.prepareChunk(-1, 2));
			assertNotSame(secondView, field(provider, "blockView"));
			final Object afterJoin = field(provider, "blockView");
			provider.cleanup();
			assertTrue(provider.prepareChunk(-1, 2));
			assertNotSame(afterJoin, field(provider, "blockView"));
		}
	}

	private static DataProvider createProvider() throws Exception {
		final Class<?> type = Class.forName(WorkerThread.class.getName() + "$CullingDataProvider");
		final Constructor<?> constructor = type.getDeclaredConstructor();
		constructor.setAccessible(true);
		return (DataProvider) constructor.newInstance();
	}

	private static Object field(DataProvider provider, String name) throws Exception {
		final Field field = provider.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(provider);
	}

	private static final class Fixture implements AutoCloseable {
		private final List<String> events = new ArrayList<>();
		private final MinecraftClient client = mock(MinecraftClient.class);
		private final net.minecraft.client.world.ClientWorld first = world("first");
		private final net.minecraft.client.world.ClientWorld second = world("second");
		private final MockedStatic<MinecraftClient> clients = mockStatic(MinecraftClient.class);
		private net.minecraft.client.world.ClientWorld world;
		private boolean opaque;

		private Fixture() {
			clients.when(MinecraftClient::getInstance).thenReturn(client);
			when(client.getWorldMapped()).thenAnswer(call -> world == null ? null : new ClientWorld(world));
		}

		private net.minecraft.client.world.ClientWorld world(String name) {
			final net.minecraft.client.world.ClientWorld world = mock(net.minecraft.client.world.ClientWorld.class, withSettings().stubOnly());
			final net.minecraft.block.BlockState state = mock(net.minecraft.block.BlockState.class, withSettings().stubOnly());
			when(world.getBlockState(any())).thenAnswer(call -> {
				final net.minecraft.util.math.BlockPos position = call.getArgument(0);
				assertFalse(position instanceof net.minecraft.util.math.BlockPos.Mutable);
				events.add(name + ":read:" + position.getX() + ":" + position.getY() + ":" + position.getZ());
				return state;
			});
			when(state.isOpaqueFullCube(any(), any())).thenAnswer(call -> {
				assertSame(world, call.getArgument(0), "Dynamic shapes must receive the original world");
				final net.minecraft.util.math.BlockPos position = call.getArgument(1);
				events.add(name + ":opaque:" + position.getX() + ":" + position.getY() + ":" + position.getZ());
				return opaque;
			});
			return world;
		}

		@Override
		public void close() {
			clients.close();
		}
	}

	/** Exact pre-change provider, kept as the observable behavior baseline. */
	private static final class BaselineProvider implements DataProvider {
		private final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		private ClientWorld clientWorld;
		private BlockView blockView;

		@Override
		public boolean prepareChunk(int chunkX, int chunkZ) {
			clientWorld = minecraftClient.getWorldMapped();
			blockView = clientWorld == null ? null : new BlockView(clientWorld.data);
			return clientWorld != null;
		}

		@Override
		public boolean isOpaqueFullCube(int x, int y, int z) {
			final BlockPos blockPos = new BlockPos(x, y, z);
			return clientWorld != null && blockView != null && clientWorld.getBlockState(blockPos).isOpaqueFullCube(blockView, blockPos);
		}

		@Override
		public void cleanup() {
			clientWorld = null;
			blockView = null;
		}
	}
}
