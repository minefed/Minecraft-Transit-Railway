package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.DataProvider;
import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mod.CustomThread;
import org.mtr.mod.Init;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * A background thread to perform intensive rendering tasks (e.g. Occlusion culling, generate dynamic textures etc.)
 */
public final class WorkerThread extends CustomThread {

	private static final int MAX_OCCLUSION_CHUNK_DISTANCE = 32;
	private static final int MAX_QUEUE_SIZE = 2;
	private int renderDistance;
	private OcclusionCullingInstance occlusionCullingInstance;
	private final Deque<Consumer<OcclusionCullingInstance>> occlusionQueueVehicle = new ArrayDeque<>();
	private final Deque<Consumer<OcclusionCullingInstance>> occlusionQueueLift = new ArrayDeque<>();
	private final Deque<Consumer<OcclusionCullingInstance>> occlusionQueueRail = new ArrayDeque<>();
	private final Deque<Runnable> dynamicTextureQueue = new ArrayDeque<>();

	@Override
	protected void runTick() {
		try {
			Thread.sleep(10); // Give the CPU a little break
		} catch (InterruptedException e) {}

		if (!occlusionQueueVehicle.isEmpty() || !occlusionQueueLift.isEmpty() || !occlusionQueueRail.isEmpty()) {
			updateInstance();
			occlusionCullingInstance.resetCache();
			run(occlusionQueueVehicle, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueLift, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueRail, task -> task.accept(occlusionCullingInstance));
		}

		run(dynamicTextureQueue, Runnable::run);
	}

	@Override
	protected boolean isRunning() {
		return MinecraftClient.getInstance().isRunning();
	}

	public void scheduleVehicles(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueVehicle.size() < MAX_QUEUE_SIZE) {
			occlusionQueueVehicle.addLast(consumer);
		}
	}

	public void scheduleLifts(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueLift.size() < MAX_QUEUE_SIZE) {
			occlusionQueueLift.addLast(consumer);
		}
	}

	public void scheduleRails(Consumer<OcclusionCullingInstance> consumer) {
		if (occlusionQueueRail.size() < MAX_QUEUE_SIZE) {
			occlusionQueueRail.addLast(consumer);
		}
	}

	public void scheduleDynamicTextures(Runnable runnable) {
		dynamicTextureQueue.addLast(runnable);
	}

	private void updateInstance() {
		final int newRenderDistance = MinecraftClientHelper.getRenderDistance();
		if (renderDistance != newRenderDistance) {
			renderDistance = newRenderDistance;
			occlusionCullingInstance = new OcclusionCullingInstance(Math.min(renderDistance, MAX_OCCLUSION_CHUNK_DISTANCE) * 16, new CullingDataProvider());
		}
	}

	private static <T> void run(Deque<T> queue, Consumer<T> consumer) {
		if (!queue.isEmpty()) {
			try {
				final T task = queue.pollFirst();
				if (task != null) {
					consumer.accept(task);
				}
			} catch (Exception e) {
				Init.LOGGER.error("", e);
			}
		}
	}

	private static final class CullingDataProvider implements DataProvider {

		private final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		private ClientWorld clientWorld = null;

		@Override
		public boolean prepareChunk(int chunkX, int chunkZ) {
			clientWorld = minecraftClient.getWorldMapped();
			return clientWorld != null;
		}

		@Override
		public boolean isOpaqueFullCube(int x, int y, int z) {
			final BlockPos blockPos = new BlockPos(x, y, z);
			return clientWorld != null && clientWorld.getBlockState(blockPos).isOpaqueFullCube(new BlockView(clientWorld.data), blockPos);
		}

		@Override
		public void cleanup() {
			clientWorld = null;
		}
	}
}
