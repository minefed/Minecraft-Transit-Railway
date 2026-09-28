package org.mtr.mixin;

import org.mtr.mapping.holder.Matrix4f;
import org.mtr.mapping.holder.Vector3f;
import org.mtr.mapping.render.vertex.VertexAttributeState;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.mtr.mod.render.OptimizedRendererBuffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Objects;

/** Removes per-draw allocations without changing the attribute values or GL call order. */
@Mixin(value = VertexAttributeState.class, remap = false)
public abstract class VertexAttributeStateMixin {

	@Shadow @Final public Integer color;
	@Shadow @Final public Integer lightmapUV;
	@Shadow @Final public Vector3f position;
	@Shadow @Final public Float textureU;
	@Shadow @Final public Float textureV;
	@Shadow @Final public Integer overlayUV;
	@Shadow @Final public Vector3f normal;
	@Shadow @Final public Matrix4f matrix4f;

	@Redirect(method = "apply", at = @At(value = "INVOKE", target = "Lorg/mtr/mapping/render/vertex/VertexAttributeType;values()[Lorg/mtr/mapping/render/vertex/VertexAttributeType;"))
	private VertexAttributeType[] mtr$getVertexAttributeTypes() {
		return OptimizedRendererBuffers.VERTEX_ATTRIBUTE_TYPES;
	}

	@Redirect(method = "apply", at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;allocate(I)Ljava/nio/ByteBuffer;"))
	private ByteBuffer mtr$allocateMatrixBuffer(int capacity) {
		return OptimizedRendererBuffers.allocate(capacity);
	}

	@Redirect(method = "apply", at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;asFloatBuffer()Ljava/nio/FloatBuffer;"))
	private FloatBuffer mtr$getMatrixFloatBuffer(ByteBuffer byteBuffer) {
		return OptimizedRendererBuffers.asFloatBuffer(byteBuffer);
	}

	/**
	 * @author MTR
	 * @reason Same value as {@code Objects.hash(position, color, textureU, textureV, lightmapUV, normal, overlayUV, matrix4f)} without a varargs array
	 */
	@Overwrite
	public int hashCode() {
		int result = 1;
		result = 31 * result + Objects.hashCode(position);
		result = 31 * result + Objects.hashCode(color);
		result = 31 * result + Objects.hashCode(textureU);
		result = 31 * result + Objects.hashCode(textureV);
		result = 31 * result + Objects.hashCode(lightmapUV);
		result = 31 * result + Objects.hashCode(normal);
		result = 31 * result + Objects.hashCode(overlayUV);
		return 31 * result + Objects.hashCode(matrix4f);
	}
}
