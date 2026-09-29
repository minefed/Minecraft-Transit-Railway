package org.mtr.mixin;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.shader.ShaderManager;
import org.mtr.mod.render.ModelTextureEvictor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every optimized-renderer batch passes through here before its texture is bound.
 */
@Mixin(value = ShaderManager.class, remap = false)
public abstract class ShaderManagerTextureUseMixin {

	@Inject(method = "setupShaderBatchState", at = @At("HEAD"))
	private void mtr$markTextureUsed(MaterialProperties materialProperties, CallbackInfo callback) {
		final Identifier texture = materialProperties.getTexture();
		if (texture != null) {
			ModelTextureEvictor.markUsed(texture.data);
		}
	}
}
