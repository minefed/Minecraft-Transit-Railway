package org.mtr.mixin;

import org.mtr.core.generated.data.PathDataSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PathDataSchema.class, remap = false)
public interface PathDataAccessor {

	@Accessor("speedLimit")
	long getSpeedLimit();

	@Accessor("speedLimit")
	void setSpeedLimit(long speedLimit);
}
