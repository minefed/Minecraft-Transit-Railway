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

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * A background thread to perform intensive rendering tasks (e.g. Occlusion culling, generate dynamic textures etc.)
 */
public final class WorkerThread extends CustomThread {

	private static final int MAX_OCCLUSION_CHUNK_DISTANCE = 32;
	private static final int MAX_QUEUE_SIZE = 2;
	private int renderDistance = -1;
	private OcclusionCullingInstance occlusionCullingInstance;
	private final Queue<Consumer<OcclusionCullingInstance>> occlusionQueueVehicle = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
	private final Queue<Consumer<OcclusionCullingInstance>> occlusionQueueLift = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
	private final Queue<Consumer<OcclusionCullingInstance>> occlusionQueueMisc = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
	private final Queue<Consumer<OcclusionCullingInstance>> occlusionQueueRail = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
	private final Queue<Runnable> dynamicTextureQueue = new ConcurrentLinkedQueue<>();

	@Override
	protected void runTick() {
		try {
			Thread.sleep(10); // Give the CPU a little break
		} catch (InterruptedException e) {
		}

		if (!occlusionQueueVehicle.isEmpty() || !occlusionQueueLift.isEmpty() || !occlusionQueueMisc.isEmpty() || !occlusionQueueRail.isEmpty()) {
			updateInstance();
			occlusionCullingInstance.resetCache();
			run(occlusionQueueVehicle, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueLift, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueRail, task -> task.accept(occlusionCullingInstance));
			run(occlusionQueueMisc, task -> task.accept(occlusionCullingInstance));
		}

		run(dynamicTextureQueue, Runnable::run);
	}

	@Override
	protected boolean isRunning() {
		return MinecraftClient.getInstance().isRunning();
	}

	public void scheduleVehicles(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueVehicle.offer(consumer);
	}

	public void scheduleLifts(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueLift.offer(consumer);
	}

	@Deprecated
	public void scheduleRails(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueMisc.offer(consumer);
	}

	public void scheduleMTRRails(Consumer<OcclusionCullingInstance> consumer) {
		occlusionQueueRail.offer(consumer);
	}

	public void scheduleDynamicTextures(Runnable runnable) {
		dynamicTextureQueue.add(runnable);
	}

	private void updateInstance() {
		final int newRenderDistance = MinecraftClientHelper.getRenderDistance();
		if (renderDistance != newRenderDistance) {
			renderDistance = newRenderDistance;
			occlusionCullingInstance = new OcclusionCullingInstance(Math.min(renderDistance, MAX_OCCLUSION_CHUNK_DISTANCE) * 16, new CullingDataProvider());
		}
	}

	private static <T> void run(Queue<T> queue, Consumer<T> consumer) {
		try {
			final T task = queue.poll();
			if (task != null) {
				consumer.accept(task);
			}
		} catch (Exception e) {
			Init.LOGGER.error("", e);
		}
	}

	private static final class CullingDataProvider implements DataProvider {

		private final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		private ClientWorld clientWorld = null;
		private BlockView blockView = null;

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
