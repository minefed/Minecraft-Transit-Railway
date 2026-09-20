package org.mtr.mixin;

import org.mtr.mapping.render.model.RawModel;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mod.render.ModelBoundsRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = RawModel.class, remap = false)
public abstract class RawModelBoundsMixin {

	@Inject(method = "upload", at = @At("RETURN"))
	private void mtr$captureModelBounds(VertexAttributeMapping mapping, CallbackInfoReturnable<List<VertexArray>> callback) {
		ModelBoundsRegistry.capture((RawModel) (Object) this, callback.getReturnValue());
	}
}
