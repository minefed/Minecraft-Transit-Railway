package org.mtr.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.mod.Init;
import org.mtr.mod.data.VehicleSpeedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

@Mixin(value = Siding.class, remap = false)
public abstract class SidingSpeedLimitMixin {

	@Unique
	private double mtr$vehicleMaxSpeed = -1;
	@Unique
	private boolean mtr$loggedTimetableCap = false;

	@Inject(method = "generatePathDistancesAndTimeSegments", at = @At("HEAD"))
	private void mtr$beforeGeneratePathDistancesAndTimeSegments(CallbackInfo callbackInfo) {
		mtr$vehicleMaxSpeed = VehicleSpeedRegistry.getMaxSpeedFromVehicleCars(((Siding) (Object) this).getVehicleCars());
	}

	@Redirect(
			method = "generatePathDistancesAndTimeSegments",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/core/data/PathData;getSpeedLimitMetersPerMillisecond()D"
			)
	)
	private double mtr$capSpeedLimitForTimetable(PathData pathData) {
		double speedLimit = pathData.getSpeedLimitMetersPerMillisecond();
		if (mtr$vehicleMaxSpeed > 0) {
			if (!mtr$loggedTimetableCap && speedLimit > mtr$vehicleMaxSpeed) {
				Init.LOGGER.info("[MTR-SpeedLimit-DIAG] Timetable cap applied: pathSpeed={} km/h -> cap={} km/h",
						String.format(Locale.ROOT, "%.1f", speedLimit * 3600.0),
						String.format(Locale.ROOT, "%.1f", mtr$vehicleMaxSpeed * 3600.0)
				);
				mtr$loggedTimetableCap = true;
			}
			speedLimit = Math.min(speedLimit, mtr$vehicleMaxSpeed);
		}
		return speedLimit;
	}
}
