package org.mtr.mod.render;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SignalModification;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the public entry points with real mapped coordinates, distances and rail data. */
public final class SignalScanEquivalenceTest {

	@BeforeAll
	public static void bootstrap() {
		SharedConstants.createGameVersion();
		Bootstrap.initialize();
	}

	@Test
	public void publicAspectLookupMatchesEveryOriginalQueryAndSelectedNode() {
		final int[][] origins = {{0, 64, 0}, {-23, -64, 17}, {29_999_998, 320, -29_999_998},
				{Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE}, {Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE}};
		try (Fixture fixture = new Fixture()) {
			for (int[] origin : origins) {
				final BlockPos center = new BlockPos(origin[0], origin[1], origin[2]);
				for (float angle : new float[]{0, 90, 180, 270, -90, 450}) {
					final Direction facing = Direction.fromRotation(angle);
					final BlockPos firstTie = center.up(-1);
					final BlockPos secondTie = center.up(1);
					for (List<BlockPos> nodes : List.of(List.<BlockPos>of(), List.of(firstTie, secondTie),
							List.of(center.offset(facing, -4).up(-5), center),
							List.of(center.offset(facing, 4).up(5), center.offset(facing.rotateYClockwise(), -4)))) {
						fixture.setNodes(nodes, angle);
						fixture.clearTrace();
						final BlockPos expected = originalNodePos(fixture.world, center, facing);
						final List<String> expectedTrace = List.copyOf(fixture.events);
						fixture.clearTrace();
						final RenderSignalBase.AspectState actual = RenderSignalBase.getAspectState(center, angle);
						assertEquals(expectedTrace, fixture.events, "query/type-check order: " + key(center) + "/" + angle);
						assertEquals(891, fixture.queries.size());
						assertImmutableDistinctPositions(fixture.queries);
						if (expected == null) {
							assertNull(actual);
						} else {
							assertNotNull(actual);
							assertEquals(List.of(nodes.indexOf(expected) + 1), actual.detectedColors, "selected node");
							if (nodes.size() == 2 && nodes.get(0).equals(firstTie)) {
								assertEquals(firstTie, expected, "Strict < keeps the first equal-distance node");
							}
						}
					}
				}
			}
		}
	}

	@Test
	public void editsAndWorldChangesAreObservedOnEveryPublicLookup() {
		try (Fixture fixture = new Fixture()) {
			final BlockPos center = new BlockPos(0, 64, 0);
			fixture.setNodes(List.of(center.up(-1), center.up(1)), 0);
			assertEquals(List.of(1), RenderSignalBase.getAspectState(center, 0).detectedColors);
			fixture.nodes.remove(key(center.up(-1)));
			fixture.clearTrace();
			assertEquals(List.of(2), RenderSignalBase.getAspectState(center, 0).detectedColors);
			assertEquals(891, fixture.queries.size());
			fixture.nodes.clear(); // An unloaded/replaced chunk must not reuse the last selected node.
			fixture.clearTrace();
			assertNull(RenderSignalBase.getAspectState(center, 0));
			assertEquals(891, fixture.queries.size());
			when(fixture.client.getWorldMapped()).thenReturn(null);
			fixture.clearTrace();
			assertNull(RenderSignalBase.getAspectState(center, 0));
			assertTrue(fixture.queries.isEmpty());
		}
	}

	@Test
	public void publicRenderKeepsFrontBackAspectAndFinalRedstoneOrder() throws ReflectiveOperationException {
		try (Fixture fixture = new Fixture(); MockedStatic<BlockSignalBase> signal = mockStatic(BlockSignalBase.class);
				MockedStatic<RenderRails> rails = mockStatic(RenderRails.class)) {
			final BlockPos center = new BlockPos(12, 64, -8);
			final World entityWorld = mock(World.class);
			final BlockState state = mock(BlockState.class);
			when(state.getBlock()).thenReturn(new Block(mock(BlockSignalBase.class)));
			when(entityWorld.getBlockState(center)).thenReturn(state);
			signal.when(() -> BlockSignalBase.getAngle(state)).thenReturn(0F);
			fixture.setNodes(List.of(center), 90);
			final Position start = position(center);
			final Position backEnd = new Position(start.getX(), start.getY(), start.getZ() - 20);
			final Rail back = rail(start, backEnd, 2);
			fixture.data.positionsToRail.get(start).put(backEnd, back);
			final Rail front = fixture.data.positionsToRail.get(start).values().stream().filter(value -> value != back).findFirst().orElseThrow();
			fixture.data.railIdToCurrentlyBlockedSignalColors.put(front.getHexId(), new LongArrayList(new long[]{1}));

			for (boolean doubleSided : new boolean[]{false, true}) {
				final BlockSignalBase.BlockEntityBase entity = mock(BlockSignalBase.BlockEntityBase.class);
				final java.lang.reflect.Field sides = BlockSignalBase.BlockEntityBase.class.getField("isDoubleSided");
				sides.setAccessible(true);
				sides.setBoolean(entity, doubleSided);
				assertEquals(doubleSided, entity.isDoubleSided, "The fixture must expose the requested side count");
				when(entity.getWorld2()).thenReturn(entityWorld);
				when(entity.getPos2()).thenReturn(center);
				when(entity.getSignalColors(anyBoolean())).thenReturn(new IntAVLTreeSet());
				when(entity.getActualAspect(anyBoolean(), anyBoolean())).thenAnswer(call -> {
					final boolean occupied = call.getArgument(0);
					final boolean backSide = call.getArgument(1);
					fixture.events.add("aspect:" + backSide + ":" + occupied);
					return occupied ? 1 : 3;
				});
				doAnswer(call -> {
					fixture.events.add("redstone:" + call.getArgument(0) + ":" + call.getArgument(1) + ":" + call.getArgument(2));
					return null;
				}).when(entity).checkForRedstoneUpdate(anyInt(), any(), any());
				final RenderSignalBase<BlockSignalBase.BlockEntityBase> renderer = new RenderSignalBase<>(null, 8, 4) {
					@Override
					protected void render(StoredMatrixTransformations transformations, BlockSignalBase.BlockEntityBase value, float tickDelta, int aspect, boolean backSide) {
						fixture.events.add("render:" + backSide + ":" + aspect);
					}
				};
				fixture.clearTrace();
				final List<String> expected = new ArrayList<>();
				for (int side = 0; side < (doubleSided ? 2 : 1); side++) {
					originalNodePos(fixture.world, center, Direction.fromRotation(90 + side * 180));
					expected.addAll(fixture.events);
					fixture.clearTrace();
					expected.add("aspect:" + (side == 1) + ":" + (side == 0));
					expected.add("render:" + (side == 1) + ":" + (side == 0 ? 1 : 3));
				}
				expected.add("redstone:15:[" + front.getHexId() + "]:" + (doubleSided ? "[" + back.getHexId() + "]" : "[]"));
				renderer.render(entity, 0.5F, null, 0, 0);
				assertEquals(expected, fixture.events);
				assertEquals(doubleSided ? 1782 : 891, fixture.queries.size());
				assertImmutableDistinctPositions(fixture.queries);
			}
		}
	}

	private static void assertImmutableDistinctPositions(List<BlockPos> positions) {
		final Set<Object> identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (BlockPos position : positions) {
			assertFalse(position.data instanceof net.minecraft.util.math.BlockPos.Mutable);
			assertTrue(identities.add(position.data), "No mutable/shared scratch position may escape to the world");
		}
	}

	/** Exact baseline helper, intentionally retaining its offset chain and eager distance. */
	private static BlockPos originalNodePos(ClientWorld world, BlockPos pos, Direction facing) {
		int closestDistance = Integer.MAX_VALUE;
		BlockPos closestPos = null;
		for (int z = -4; z <= 4; z++) {
			for (int x = -4; x <= 4; x++) {
				for (int y = -5; y <= 5; y++) {
					final BlockPos checkPos = pos.up(y).offset(facing.rotateYClockwise(), x).offset(facing, z);
					final BlockState checkState = world.getBlockState(checkPos);
					final int distance = checkPos.getManhattanDistance(new Vector3i(pos.data));
					if (checkState.getBlock().data instanceof BlockNode && distance < closestDistance) {
						closestDistance = distance;
						closestPos = checkPos;
					}
				}
			}
		}
		return closestPos;
	}

	private static String key(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static Position position(BlockPos pos) {
		return new Position(pos.getX(), pos.getY(), pos.getZ());
	}

	private static Rail rail(Position start, Position end, int color) {
		final Rail rail = Rail.newRail(start, Angle.E, end, Angle.W, Rail.Shape.QUADRATIC, 0, new ObjectArrayList<>(), 80, 80, false, false, true, false, false, TransportMode.TRAIN);
		final SignalModification modification = new SignalModification(start, end, false);
		modification.putColorToAdd(color);
		rail.applyModification(modification);
		return rail;
	}

	private static final class Fixture implements AutoCloseable {
		private final List<String> events = new ArrayList<>();
		private final List<BlockPos> queries = new ArrayList<>();
		private final Set<String> nodes = new HashSet<>();
		private final MinecraftClient client = mock(MinecraftClient.class);
		private final ClientWorld world = mock(ClientWorld.class, withSettings().stubOnly());
		private final MinecraftClientData data = new MinecraftClientData();
		private final MockedStatic<MinecraftClient> clients = mockStatic(MinecraftClient.class);
		private final MockedStatic<MinecraftClientData> clientData = mockStatic(MinecraftClientData.class);

		private Fixture() {
			clients.when(MinecraftClient::getInstance).thenReturn(client);
			clientData.when(MinecraftClientData::getInstance).thenReturn(data);
			when(client.getWorldMapped()).thenReturn(world);
			when(client.getPlayerMapped()).thenReturn(mock(ClientPlayerEntity.class));
			final Block node = new Block(mock(BlockNode.class));
			final Block other = new Block(mock(net.minecraft.block.Block.class));
			final BlockState nodeState = mock(BlockState.class, withSettings().stubOnly());
			final BlockState otherState = mock(BlockState.class, withSettings().stubOnly());
			when(nodeState.getBlock()).thenAnswer(call -> { events.add("node"); return node; });
			when(otherState.getBlock()).thenAnswer(call -> { events.add("other"); return other; });
			when(world.getBlockState(any())).thenAnswer(call -> {
				final BlockPos pos = call.getArgument(0);
				queries.add(pos);
				events.add("query:" + key(pos));
				return nodes.contains(key(pos)) ? nodeState : otherState;
			});
		}

		private void setNodes(List<BlockPos> positions, float angle) {
			nodes.clear();
			data.positionsToRail.clear();
			for (int index = 0; index < positions.size(); index++) {
				final BlockPos pos = positions.get(index);
				nodes.add(key(pos));
				final Position start = position(pos);
				final Position end = new Position(start.getX() + Math.round(Math.cos(Math.toRadians(angle)) * 20), start.getY(), start.getZ() + Math.round(Math.sin(Math.toRadians(angle)) * 20));
				final Object2ObjectOpenHashMap<Position, Rail> connections = new Object2ObjectOpenHashMap<>();
				connections.put(end, rail(start, end, index + 1));
				data.positionsToRail.put(start, connections);
			}
		}

		private void clearTrace() {
			events.clear();
			queries.clear();
		}

		@Override
		public void close() {
			clientData.close();
			clients.close();
		}
	}
}
