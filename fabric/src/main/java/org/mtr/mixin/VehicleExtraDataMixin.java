package org.mtr.mixin;

import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.data.VehicleSpeedRegistry;
import org.mtr.mod.mixin_interfaces.VehicleExtraDataExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VehicleExtraData.class, remap = false)
public abstract class VehicleExtraDataMixin implements VehicleExtraDataExtension {

	@Shadow public ObjectImmutableList<org.mtr.core.data.VehicleCar> immutableVehicleCars;

	@Unique
	private double mtr$vehicleMaxSpeed = -1;
	@Unique
	private boolean mtr$vehicleMaxSpeedComputed = false;

	@Override
	public double getVehicleMaxSpeed() {
		mtr$computeVehicleMaxSpeedIfReady();
		return mtr$vehicleMaxSpeed;
	}

	@Inject(method = "<init>(Lorg/mtr/core/serializer/ReaderBase;)V", at = @At("TAIL"))
	private void mtr$initFromReader(ReaderBase readerBase, CallbackInfo callbackInfo) {
		mtr$computeVehicleMaxSpeedIfReady();
	}

	@Inject(method = "create", at = @At("RETURN"))
	private static void mtr$initFromCreate(CallbackInfoReturnable<VehicleExtraData> callbackInfoReturnable) {
		final VehicleExtraData vehicleExtraData = callbackInfoReturnable.getReturnValue();
		if (vehicleExtraData != null) {
			final VehicleExtraDataMixin mixin = (VehicleExtraDataMixin) (Object) vehicleExtraData;
			mixin.mtr$computeVehicleMaxSpeedIfReady();
		}
	}

	@Unique
	private void mtr$computeVehicleMaxSpeedIfReady() {
		if (mtr$vehicleMaxSpeedComputed || !VehicleSpeedRegistry.isInitialized()) {
			return;
		}
		mtr$vehicleMaxSpeed = VehicleSpeedRegistry.getMaxSpeedFromVehicleCars(immutableVehicleCars);
		mtr$vehicleMaxSpeedComputed = true;
	}
}
