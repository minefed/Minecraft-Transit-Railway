package org.mtr.mixin;

import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.model.Mesh;
import org.mtr.mapping.render.object.IndexBuffer;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mod.render.GlBufferReclaimer;
import org.mtr.mod.render.GlBufferReclaimerAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The bundled {@link VertexArray} drops the {@link Mesh} after binding it, so its vertex and index buffers are never deleted.
 * Register the three GL names with {@link GlBufferReclaimer}, guarded by the index buffer object that every copy of this part shares.
 */
@Mixin(value = VertexArray.class, remap = false)
public abstract class VertexArrayReleaseMixin implements GlBufferReclaimerAccess {

	@Shadow private int id;
	@Shadow @Final public IndexBuffer indexBuffer;
	@Unique private GlBufferReclaimer.Handle mtr$reclaimerHandle;

	@Inject(method = "<init>(Lorg/mtr/mapping/render/model/Mesh;Lorg/mtr/mapping/render/vertex/VertexAttributeMapping;)V", at = @At("RETURN"))
	private void mtr$registerBuffers(Mesh mesh, VertexAttributeMapping mapping, CallbackInfo callback) {
		mtr$reclaimerHandle = GlBufferReclaimer.register(indexBuffer, id, ((VertexBufferAccessor) (Object) mesh.vertexBuffer).mtr$getId(), ((VertexBufferAccessor) (Object) indexBuffer).mtr$getId());
	}

	@Inject(method = "<init>(Lorg/mtr/mapping/render/object/VertexArray;Lorg/mtr/mapping/render/batch/MaterialProperties;)V", at = @At("RETURN"))
	private void mtr$shareHandle(VertexArray source, MaterialProperties materialProperties, CallbackInfo callback) {
		mtr$reclaimerHandle = ((GlBufferReclaimerAccess) (Object) source).mtr$getReclaimerHandle();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void mtr$markClosed(CallbackInfo callback) {
		if (mtr$reclaimerHandle != null) {
			mtr$reclaimerHandle.markVertexArrayDeleted();
		}
	}

	@Override
	public GlBufferReclaimer.Handle mtr$getReclaimerHandle() {
		return mtr$reclaimerHandle;
	}
}
