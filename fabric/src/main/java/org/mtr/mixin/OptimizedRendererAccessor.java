package org.mtr.mixin;

import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.shader.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = OptimizedRenderer.class, remap = false)
public interface OptimizedRendererAccessor {

	@Accessor("shaderManager")
	ShaderManager mtr$getShaderManager();
}
