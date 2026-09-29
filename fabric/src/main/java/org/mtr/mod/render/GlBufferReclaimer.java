package org.mtr.mod.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL33;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Frees the GL objects of an uploaded model part once nothing can draw it anymore.
 * <p>
 * The bundled renderer creates a vertex array, a vertex buffer and an index buffer for every uploaded part,
 * but only ever deletes the vertex array, and nothing calls it. Every part keeps a strong reference to its
 * index buffer, and copies of a part share the same index buffer object, so once that object is unreachable
 * no part can be queued or drawn and the three GL names can be deleted safely on the render thread.
 */
public final class GlBufferReclaimer {

	private static final Cleaner CLEANER = Cleaner.create();
	private static final AtomicLong RECLAIMED = new AtomicLong();

	private GlBufferReclaimer() {
	}

	/**
	 * @param owner             the object whose reachability guards the GL names; must not be referenced by the returned handle
	 * @param vertexArrayId     the vertex array name
	 * @param vertexBufferId    the vertex buffer name
	 * @param indexBufferId     the index buffer name
	 */
	public static Handle register(Object owner, int vertexArrayId, int vertexBufferId, int indexBufferId) {
		final Handle handle = new Handle(vertexArrayId, vertexBufferId, indexBufferId);
		CLEANER.register(owner, handle);
		return handle;
	}

	public static long getReclaimedCount() {
		return RECLAIMED.get();
	}

	public static final class Handle implements Runnable {

		private final int vertexArrayId;
		private final int vertexBufferId;
		private final int indexBufferId;
		private final AtomicBoolean vertexArrayDeleted = new AtomicBoolean();

		private Handle(int vertexArrayId, int vertexBufferId, int indexBufferId) {
			this.vertexArrayId = vertexArrayId;
			this.vertexBufferId = vertexBufferId;
			this.indexBufferId = indexBufferId;
		}

		/**
		 * The renderer's own {@code close()} deletes the vertex array; do not delete the same name again.
		 */
		public void markVertexArrayDeleted() {
			vertexArrayDeleted.set(true);
		}

		/**
		 * Runs on the cleaner thread; the GL calls are deferred to the render thread.
		 */
		@Override
		public void run() {
			RenderSystem.recordRenderCall(this::delete);
		}

		void delete() {
			if (!vertexArrayDeleted.getAndSet(true) && vertexArrayId != 0) {
				GL33.glDeleteVertexArrays(vertexArrayId);
			}
			if (vertexBufferId != 0) {
				GL33.glDeleteBuffers(vertexBufferId);
			}
			if (indexBufferId != 0) {
				GL33.glDeleteBuffers(indexBufferId);
			}
			RECLAIMED.incrementAndGet();
		}
	}
}
