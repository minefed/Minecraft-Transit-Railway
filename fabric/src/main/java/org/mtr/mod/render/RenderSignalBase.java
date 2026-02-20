package org.mtr.mod.render;

import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityRenderer;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalBase;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;

import javax.annotation.Nullable;
import java.util.Collections;

public abstract class RenderSignalBase<T extends BlockSignalBase.BlockEntityBase> extends BlockEntityRenderer<T> implements IBlock, IGui {

	protected final int aspects;
	private final float colorIndicatorHeight;
	private static final ObjectArrayList<Long2ObjectOpenHashMap<BlockPos>> NODE_POS_CACHE = new ObjectArrayList<>();
	private static long nodePosCacheClearTick = Long.MIN_VALUE;

	static {
		for (int i = 0; i < 4; i++) {
			NODE_POS_CACHE.add(new Long2ObjectOpenHashMap<>());
		}
	}

	public RenderSignalBase(Argument dispatcher, int colorIndicatorHeight, int aspects) {
		super(dispatcher);
		this.aspects = aspects;
		this.colorIndicatorHeight = colorIndicatorHeight / 16F + SMALL_OFFSET;
	}

	@Override
	public final void render(T entity, float tickDelta, GraphicsHolder graphicsHolder, int light, int overlay) {
		final World world = entity.getWorld2();
		if (world == null) {
			return;
		}

		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity == null) {
			return;
		}

		final BlockPos pos = entity.getPos2();
		final BlockState state = world.getBlockState(pos);
		if (!(state.getBlock().data instanceof BlockSignalBase)) {
			return;
		}

		final float angle = BlockSignalBase.getAngle(state);
		final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(0.5 + entity.getPos2().getX(), entity.getPos2().getY(), 0.5 + entity.getPos2().getZ());
		int redstoneLevel = 0;
		final ObjectArrayList<String> railIds1 = new ObjectArrayList<>();
		final ObjectArrayList<String> railIds2 = new ObjectArrayList<>();

		for (int i = 0; i < (entity.isDoubleSided ? 2 : 1); i++) {
			final float newAngle = angle + i * 180;
			final AspectState aspectState = getAspectState(pos, newAngle + 90);

			if (aspectState != null) {
				final boolean isBackSide = i == 1;
				final IntAVLTreeSet filterColors = entity.getSignalColors(isBackSide);
				final StoredMatrixTransformations storedMatrixTransformationsNew = storedMatrixTransformations.copy();
				storedMatrixTransformationsNew.add(graphicsHolderNew -> graphicsHolderNew.rotateYDegrees(-newAngle));

				if (RenderRails.isHoldingRailRelated(clientPlayerEntity)) {
					final float xStart = -0.015625F * aspectState.detectedColors.size();
					for (int j = 0; j < aspectState.detectedColors.size(); j++) {
						final int signalColor = aspectState.detectedColors.getInt(j);
						final boolean occupied = aspectState.occupiedColors.contains(signalColor);
						final float x = xStart + j * 0.03125F;
						final float width = 0.03125F / (filterColors.isEmpty() || filterColors.contains(signalColor) ? 1 : 8);
						MainRenderer.scheduleRender(new Identifier(Init.MOD_ID, "textures/block/white.png"), false, occupied ? QueuedRenderLayer.EXTERIOR : QueuedRenderLayer.LIGHT, (graphicsHolderNew, offset) -> {
							storedMatrixTransformationsNew.transform(graphicsHolderNew, offset);
							IDrawing.drawTexture(
									graphicsHolderNew,
									x, colorIndicatorHeight, -0.15625F,
									x + 0.03125F, colorIndicatorHeight, -0.15625F,
									x + 0.03125F, colorIndicatorHeight, -0.15625F - width,
									x, colorIndicatorHeight, -0.15625F - width,
									0, 0, 1, 1,
									Direction.UP, occupied ? MainRenderer.getFlashingColor(signalColor, 1) : signalColor | ARGB_BLACK, GraphicsHolder.getDefaultLight()
							);
							graphicsHolderNew.pop();
						});
					}
				}

				// If filters are empty, render all signal states, including node states
				// If filters are not empty, only render the signal state of the selected colors, even if the colors don't exist
				final int occupiedAspect = entity.getActualAspect(filterColors.isEmpty() && aspectState.nodeBlocked || aspectState.occupiedColors.intStream().anyMatch(color -> filterColors.isEmpty() || filterColors.contains(color)), isBackSide);
				render(storedMatrixTransformationsNew, entity, tickDelta, occupiedAspect, isBackSide);

				if (occupiedAspect > 0 && occupiedAspect < aspects) {
					redstoneLevel = Math.max(redstoneLevel, (4 - occupiedAspect) * 5);
				}

				(isBackSide ? railIds2 : railIds1).addAll(aspectState.railIds);
			}
		}

		entity.checkForRedstoneUpdate(redstoneLevel, railIds1, railIds2);
	}

	protected abstract void render(StoredMatrixTransformations storedMatrixTransformations, T entity, float tickDelta, int occupiedAspect, boolean isBackSide);

	@Nullable
	public static AspectState getAspectState(BlockPos blockPos, float angle) {
		clearNodeCacheIfExpired();
		final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
		if (clientWorld == null) {
			return null;
		}

		final BlockPos startPos = getNodePos(clientWorld, blockPos, Direction.fromRotation(angle));
		if (startPos == null) {
			return null;
		}

		final MinecraftClientData minecraftClientData = MinecraftClientData.getInstance();
		final IntArrayList detectedColors = new IntArrayList();
		final IntAVLTreeSet occupiedColors = new IntAVLTreeSet();
		final boolean[] blocked = {false};
		final ObjectArrayList<String> railIds = new ObjectArrayList<>();
		final Position startPosition = Init.blockPosToPosition(startPos);

		final Object2ObjectOpenHashMap<Position, Rail> connectedRails = minecraftClientData.positionsToRail.get(startPosition);
		if (connectedRails == null) {
			return new AspectState(detectedColors, occupiedColors, false, railIds);
		}

		connectedRails.forEach((endPosition, rail) -> {
			if (Math.abs(Utilities.circularDifference(Math.round(Math.toDegrees(Math.atan2(endPosition.getZ() - startPos.getZ(), endPosition.getX() - startPos.getX()))), Math.round(angle), 360)) < 90) {
				rail.getSignalColors().forEach(detectedColors::add);
				final String railId = rail.getHexId();
				final LongArrayList blockedSignalColors = minecraftClientData.railIdToCurrentlyBlockedSignalColors.get(railId);
				if (blockedSignalColors != null) {
					blockedSignalColors.forEach(color -> occupiedColors.add((int) color));
				}
				if (minecraftClientData.blockedRailIds.contains(TwoPositionsBase.getHexIdRaw(startPosition, endPosition))) {
					blocked[0] = true;
				}
				railIds.add(railId);
			}
		});

		Collections.sort(detectedColors);
		return new AspectState(detectedColors, occupiedColors, blocked[0], railIds);
	}

	@Nullable
	private static BlockPos getNodePos(ClientWorld world, BlockPos pos, Direction facing) {
		final int facingIndex = Math.round(facing.asRotation() / 90F) & 0b11;
		final long posKey = pos.asLong();
		final Long2ObjectOpenHashMap<BlockPos> nodePosCacheForFacing = NODE_POS_CACHE.get(facingIndex);
		if (nodePosCacheForFacing.containsKey(posKey)) {
			return nodePosCacheForFacing.get(posKey);
		}

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
		nodePosCacheForFacing.put(posKey, closestPos);
		return closestPos;
	}

	private static void clearNodeCacheIfExpired() {
		final long gameTick = (long) Math.floor(InitClient.getGameTick());
		if (gameTick > nodePosCacheClearTick) {
			NODE_POS_CACHE.forEach(Long2ObjectOpenHashMap::clear);
			nodePosCacheClearTick = gameTick + 20;
		}
	}

	public static class AspectState {

		public final IntArrayList detectedColors;
		private final IntAVLTreeSet occupiedColors;
		private final boolean nodeBlocked;
		private final ObjectArrayList<String> railIds;

		private AspectState(IntArrayList detectedColors, IntAVLTreeSet occupiedColors, boolean nodeBlocked, ObjectArrayList<String> railIds) {
			this.detectedColors = detectedColors;
			this.occupiedColors = occupiedColors;
			this.nodeBlocked = nodeBlocked;
			this.railIds = railIds;
		}
	}
}
