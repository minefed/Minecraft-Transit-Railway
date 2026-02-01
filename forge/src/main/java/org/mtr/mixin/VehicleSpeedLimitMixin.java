package org.mtr.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.mod.Init;
import org.mtr.mod.mixin_interfaces.VehicleExtraDataExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Locale;

@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleSpeedLimitMixin {

	@Shadow public VehicleExtraData vehicleExtraData;

	@Unique
	private boolean mtr$loggedVehicleCap = false;

	@Redirect(
			method = "simulateMoving",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/core/data/PathData;getSpeedLimitMetersPerMillisecond()D"
			)
	)
	private double mtr$capSpeedLimitForVehicle(PathData pathData) {
		double speedLimit = pathData.getSpeedLimitMetersPerMillisecond();
		if (vehicleExtraData instanceof VehicleExtraDataExtension) {
			final double maxSpeed = ((VehicleExtraDataExtension) vehicleExtraData).getVehicleMaxSpeed();
			if (maxSpeed > 0) {
				if (!mtr$loggedVehicleCap && speedLimit > maxSpeed) {
					Init.LOGGER.info("[MTR-SpeedLimit-DIAG] Vehicle cap applied: pathSpeed={} km/h -> cap={} km/h, routeId={}, routeName={}, station={}",
							String.format(Locale.ROOT, "%.1f", speedLimit * 3600.0),
							String.format(Locale.ROOT, "%.1f", maxSpeed * 3600.0),
							vehicleExtraData.getThisRouteId(),
							vehicleExtraData.getThisRouteName(),
							vehicleExtraData.getThisStationName()
					);
					mtr$loggedVehicleCap = true;
				}
				speedLimit = Math.min(speedLimit, maxSpeed);
			}
		}
		return speedLimit;
	}
}
