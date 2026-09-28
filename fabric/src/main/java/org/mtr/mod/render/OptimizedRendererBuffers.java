package org.mtr.mod.render;

import org.mtr.mapping.render.vertex.VertexAttributeType;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Reused per-draw objects for Minecraft-Mappings' {@code VertexAttributeState.apply()}.
 * The values array is only iterated. The matrix buffer has the same capacity and byte order
 * as {@code ByteBuffer.allocate(64)}, and every read uses an index written just before it.
 */
public final class OptimizedRendererBuffers {

	public static final VertexAttributeType[] VERTEX_ATTRIBUTE_TYPES = VertexAttributeType.values();
	private static final int MATRIX_BYTES = 64;
	private static final ThreadLocal<OptimizedRendererBuffers> BUFFERS = ThreadLocal.withInitial(OptimizedRendererBuffers::new);

	private final ByteBuffer byteBuffer = ByteBuffer.allocate(MATRIX_BYTES);
	private final FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();

	private OptimizedRendererBuffers() {
	}

	public static ByteBuffer allocate(int capacity) {
		if (capacity == MATRIX_BYTES) {
			final ByteBuffer byteBuffer = BUFFERS.get().byteBuffer;
			byteBuffer.clear();
			return byteBuffer;
		} else {
			return ByteBuffer.allocate(capacity);
		}
	}

	public static FloatBuffer asFloatBuffer(ByteBuffer byteBuffer) {
		final OptimizedRendererBuffers buffers = BUFFERS.get();
		if (byteBuffer == buffers.byteBuffer) {
			buffers.floatBuffer.clear();
			return buffers.floatBuffer;
		} else {
			return byteBuffer.asFloatBuffer();
		}
	}
}
