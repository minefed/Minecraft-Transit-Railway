package org.mtr.mixin;

import org.mtr.core.data.VehicleExtraData;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.data.VehicleSpeedRegistry;
import org.mtr.mod.mixin_interfaces.VehicleExtraDataExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = VehicleExtraData.class, remap = false)
public abstract class VehicleExtraDataMixin implements VehicleExtraDataExtension {

	@Shadow public ObjectImmutableList<org.mtr.core.data.VehicleCar> immutableVehicleCars;

	@Unique
	private volatile double mtr$vehicleMaxSpeed = -1;
	@Unique
	private volatile long mtr$vehicleMaxSpeedVersion = -1;

	@Override
	public double getVehicleMaxSpeed() {
		final long version = VehicleSpeedRegistry.getVersion();
		if (mtr$vehicleMaxSpeedVersion != version) {
			mtr$computeVehicleMaxSpeedIfReady(version);
		}
		return mtr$vehicleMaxSpeed;
	}

	@Unique
	private synchronized void mtr$computeVehicleMaxSpeedIfReady(long version) {
		if (mtr$vehicleMaxSpeedVersion == version) {
			return;
		}
		if (!VehicleSpeedRegistry.isReady()) {
			if (VehicleSpeedRegistry.getVersion() == version) {
				mtr$vehicleMaxSpeed = -1;
				mtr$vehicleMaxSpeedVersion = version;
			}
			return;
		}
		if (immutableVehicleCars == null) {
			return;
		}
		final double vehicleMaxSpeed = VehicleSpeedRegistry.getMaxSpeedFromVehicleCars(immutableVehicleCars);
		if (VehicleSpeedRegistry.getVersion() == version) {
			mtr$vehicleMaxSpeed = vehicleMaxSpeed;
			mtr$vehicleMaxSpeedVersion = version;
		}
	}
}
