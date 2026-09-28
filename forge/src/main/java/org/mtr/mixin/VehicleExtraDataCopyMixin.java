package org.mtr.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code copy(pathUpdateIndex)} passes a detached copy of the entire path to the constructor, which only keeps
 * it as the copy's {@code immutablePath}, then refills {@code path} with the original path subset. The copy's only
 * caller wraps it in a {@code VehicleUpdate} that is serialized from {@code path}; its {@code immutablePath} is
 * never read, so the detached copy is not built.
 */
@Mixin(value = VehicleExtraData.class, remap = false)
public abstract class VehicleExtraDataCopyMixin {

	@Redirect(method = "copy", at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/VehicleExtraData;copyPath(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<PathData> mtr$skipUnusedPathCopy(ObjectArrayList<PathData> path) {
		return new ObjectArrayList<>();
	}
}
