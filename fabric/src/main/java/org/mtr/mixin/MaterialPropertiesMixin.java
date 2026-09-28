package org.mtr.mixin;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.vertex.VertexAttributeState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Objects;

/** Batch map lookups hash every queued draw; compute the same hash without a varargs array. */
@Mixin(value = MaterialProperties.class, remap = false)
public abstract class MaterialPropertiesMixin {

	@Shadow private Identifier texture;
	@Shadow @Final public OptimizedModel.ShaderType shaderType;
	@Shadow @Final public VertexAttributeState vertexAttributeState;
	@Shadow @Final public boolean translucent;
	@Shadow @Final public boolean writeDepthBuf;
	@Shadow @Final public boolean cutoutHack;

	/**
	 * @author MTR
	 * @reason Same value as {@code Objects.hash(shaderType, texture, vertexAttributeState, translucent, writeDepthBuf, cutoutHack)} without a varargs array
	 */
	@Overwrite
	public int hashCode() {
		int result = 1;
		result = 31 * result + Objects.hashCode(shaderType);
		result = 31 * result + Objects.hashCode(texture);
		result = 31 * result + Objects.hashCode(vertexAttributeState);
		result = 31 * result + Boolean.hashCode(translucent);
		result = 31 * result + Boolean.hashCode(writeDepthBuf);
		return 31 * result + Boolean.hashCode(cutoutHack);
	}
}
