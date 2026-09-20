package org.mtr.mixin;

import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mod.render.ModelBoundsAccess;
import org.mtr.mod.render.ModelBoundsRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = OptimizedModel.class, remap = false)
public abstract class OptimizedModelBoundsMixin implements ModelBoundsAccess {

	@Shadow @Final private List<VertexArray> uploadedParts;
	@Unique private float mtr$modelRadius = Float.POSITIVE_INFINITY;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mtr$finishModelBounds(CallbackInfo callback) {
		mtr$modelRadius = ModelBoundsRegistry.radius(uploadedParts);
	}

	@Override
	public float mtr$getModelRadius() {
		return mtr$modelRadius;
	}
}
