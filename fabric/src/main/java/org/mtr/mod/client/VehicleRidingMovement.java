package org.mtr.mod.client;

import org.mtr.core.tools.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.EntityHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.render.RenderVehicleTransformationHelper;
import org.mtr.mod.render.RenderVehicles;

import javax.annotation.Nullable;
import java.util.Comparator;

public class VehicleRidingMovement {

	private static long ridingVehicleId;
	private static int ridingVehicleCarNumber;
	private static double ridingVehicleX;
	private static double ridingVehicleY;
	private static double ridingVehicleZ;
	private static boolean isOnGangway;
	private static int ridingVehicleCoolDown;

	private static final float VEHICLE_WALKING_SPEED_MULTIPLIER = 0.005F;
	private static final int RIDING_COOL_DOWN = 5;

	public static void tick() {
		if (ridingVehicleCoolDown < RIDING_COOL_DOWN) {
			ridingVehicleCoolDown++;
		} else {
			ridingVehicleId = 0;
		}
	}

	public static void startRiding(ObjectArrayList<Box> openDoorways, long vehicleId, int carNumber, double x, double y, double z) {
		if (ridingVehicleId == 0 || ridingVehicleId == vehicleId) {
			for (final Box doorway : openDoorways) {
				if (RenderVehicles.boxContains(doorway, x, y, z)) {
					ridingVehicleId = vehicleId;
					ridingVehicleCarNumber = carNumber;
					ridingVehicleX = x;
					ridingVehicleY = y;
					ridingVehicleZ = z;
					isOnGangway = false;
				}
			}
		}
	}

	public static void movePlayer(
			long millisElapsed, long vehicleId, int carNumber,
			ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways,
			@Nullable GangwayMovementPositions previousCarGangwayMovementPositions,
			@Nullable GangwayMovementPositions thisCarGangwayMovementPositions1,
			@Nullable GangwayMovementPositions thisCarGangwayMovementPositions2,
			RenderVehicleTransformationHelper renderVehicleTransformationHelper
	) {
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity == null) {
			return;
		}

		if (ridingVehicleId == vehicleId && ridingVehicleCarNumber == carNumber) {
			ridingVehicleCoolDown = 0;
			final float speedMultiplier = millisElapsed * VEHICLE_WALKING_SPEED_MULTIPLIER * (clientPlayerEntity.isSprinting() ? 2 : 1);
			final Vector3d movement = renderVehicleTransformationHelper.transformBackwards(new Vector3d(
					Math.abs(clientPlayerEntity.getSidewaysSpeedMapped()) > 0.5 ? Math.copySign(speedMultiplier, clientPlayerEntity.getSidewaysSpeedMapped()) : 0,
					0,
					Math.abs(clientPlayerEntity.getForwardSpeedMapped()) > 0.5 ? Math.copySign(speedMultiplier, clientPlayerEntity.getForwardSpeedMapped()) : 0
			), (vector, pitch) -> vector, (vector, yaw) -> vector.rotateY((float) (yaw - Math.toRadians(EntityHelper.getYaw(new Entity(clientPlayerEntity.data))))), (vector, x, y, z) -> vector);
			final double movementX = movement.getXMapped();
			final double movementZ = movement.getZMapped();

			if (isOnGangway) {
				if (thisCarGangwayMovementPositions1 == null || previousCarGangwayMovementPositions == null) {
					ridingVehicleId = 0;
				} else {
					if (ridingVehicleZ + movementZ > 1) {
						isOnGangway = false;
						ridingVehicleX = thisCarGangwayMovementPositions1.getX(ridingVehicleX);
						ridingVehicleZ = thisCarGangwayMovementPositions1.getZ() + ridingVehicleZ + movementZ - 1;
					} else if (ridingVehicleZ + movementZ < 0) {
						isOnGangway = false;
						ridingVehicleCarNumber--;
						ridingVehicleX = previousCarGangwayMovementPositions.getX(ridingVehicleX);
						ridingVehicleZ = previousCarGangwayMovementPositions.getZ() + ridingVehicleZ + movementZ;
					} else {
						ridingVehicleX = Utilities.clamp(ridingVehicleX + movementX, 0, 1);
						ridingVehicleZ += movementZ;
						final Vector3d position1Min = previousCarGangwayMovementPositions.getMinWorldPosition();
						final Vector3d position1Max = previousCarGangwayMovementPositions.getMaxWorldPosition();
						final Vector3d position2Min = thisCarGangwayMovementPositions1.getMinWorldPosition();
						final Vector3d position2Max = thisCarGangwayMovementPositions1.getMaxWorldPosition();
						movePlayer(getFromScale(
								getFromScale(position1Min.getXMapped(), position1Max.getXMapped(), ridingVehicleX),
								getFromScale(position2Min.getXMapped(), position2Max.getXMapped(), ridingVehicleX),
								ridingVehicleZ
						), getFromScale(
								getFromScale(position1Min.getYMapped(), position1Max.getYMapped(), ridingVehicleX),
								getFromScale(position2Min.getYMapped(), position2Max.getYMapped(), ridingVehicleX),
								ridingVehicleZ
						), getFromScale(
								getFromScale(position1Min.getZMapped(), position1Max.getZMapped(), ridingVehicleX),
								getFromScale(position2Min.getZMapped(), position2Max.getZMapped(), ridingVehicleX),
								ridingVehicleZ
						));
					}
				}
			} else {
				if (thisCarGangwayMovementPositions1 != null && thisCarGangwayMovementPositions1.getPercentageZ(ridingVehicleZ + movementZ) < 1) {
					isOnGangway = true;
					ridingVehicleX = thisCarGangwayMovementPositions1.getPercentageX(ridingVehicleX + movementX);
					ridingVehicleZ = thisCarGangwayMovementPositions1.getPercentageZ(ridingVehicleZ + movementZ);
				} else if (thisCarGangwayMovementPositions2 != null && thisCarGangwayMovementPositions2.getPercentageZ(ridingVehicleZ + movementZ) > 0) {
					isOnGangway = true;
					ridingVehicleCarNumber++;
					ridingVehicleX = thisCarGangwayMovementPositions2.getPercentageX(ridingVehicleX + movementX);
					ridingVehicleZ = thisCarGangwayMovementPositions2.getPercentageZ(ridingVehicleZ + movementZ);
				} else {
					final ObjectArrayList<Vector3d> offsets = new ObjectArrayList<>();

					clampPosition(floorsAndDoorways, ridingVehicleX + movementX - RenderVehicles.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ - RenderVehicles.HALF_PLAYER_WIDTH, offsets);
					clampPosition(floorsAndDoorways, ridingVehicleX + movementX + RenderVehicles.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ - RenderVehicles.HALF_PLAYER_WIDTH, offsets);
					clampPosition(floorsAndDoorways, ridingVehicleX + movementX + RenderVehicles.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ + RenderVehicles.HALF_PLAYER_WIDTH, offsets);
					clampPosition(floorsAndDoorways, ridingVehicleX + movementX - RenderVehicles.HALF_PLAYER_WIDTH, ridingVehicleZ + movementZ + RenderVehicles.HALF_PLAYER_WIDTH, offsets);

					if (offsets.isEmpty()) {
						ridingVehicleId = 0;
					} else {
						double clampX = 0;
						double maxY = -Double.MAX_VALUE;
						double clampZ = 0;
						for (final Vector3d offset : offsets) {
							if (Math.abs(offset.getXMapped()) > Math.abs(clampX)) {
								clampX = offset.getXMapped();
							}
							maxY = Math.max(maxY, offset.getYMapped());
							if (Math.abs(offset.getZMapped()) > Math.abs(clampZ)) {
								clampZ = offset.getZMapped();
							}
						}
						ridingVehicleX += movementX + clampX;
						ridingVehicleY = maxY;
						ridingVehicleZ += movementZ + clampZ;
					}

					final Vector3d newPlayerPosition = renderVehicleTransformationHelper.transformForwards(new Vector3d(ridingVehicleX, ridingVehicleY, ridingVehicleZ), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
					movePlayer(newPlayerPosition.getXMapped(), newPlayerPosition.getYMapped(), newPlayerPosition.getZMapped());
				}
			}
		}
	}

	/**
	 * Find an intersecting floor or doorway from the player position.
	 * If there are multiple intersecting floors or doorways, get the one with the highest Y level.
	 * If there are no intersecting floors or doorways, find the closest floor or doorway instead.
	 */
	@Nullable
	private static ObjectBooleanImmutablePair<Box> bestPosition(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsOrDoorways, double x, double y, double z) {
		return floorsOrDoorways.stream().filter(floorOrDoorway -> RenderVehicles.boxContains(floorOrDoorway.left(), x, y, z)).max(Comparator.comparingDouble(floorOrDoorway -> floorOrDoorway.left().getMaxYMapped())).orElse(floorsOrDoorways.stream().min(Comparator.comparingDouble(floorOrDoorway -> Math.min(
				Math.abs(floorOrDoorway.left().getMinXMapped() - x),
				Math.abs(floorOrDoorway.left().getMaxXMapped() - x)
		) + Math.min(
				Math.abs(floorOrDoorway.left().getMinYMapped() - y),
				Math.abs(floorOrDoorway.left().getMaxYMapped() - y)
		) + Math.min(
				Math.abs(floorOrDoorway.left().getMinZMapped() - z),
				Math.abs(floorOrDoorway.left().getMaxZMapped() - z)
		))).orElse(null));
	}

	private static void clampPosition(ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways, double x, double z, ObjectArrayList<Vector3d> offsets) {
		final ObjectBooleanImmutablePair<Box> floorOrDoorway = bestPosition(floorsAndDoorways, x, ridingVehicleY, z);

		if (floorOrDoorway != null) {
			if (floorOrDoorway.rightBoolean()) {
				// If the intersecting or closest floor or doorway is a floor, then force the player to be in bounds
				offsets.add(new Vector3d(
						Utilities.clamp(x, floorOrDoorway.left().getMinXMapped(), floorOrDoorway.left().getMaxXMapped()) - x,
						floorOrDoorway.left().getMaxYMapped(),
						Utilities.clamp(z, floorOrDoorway.left().getMinZMapped(), floorOrDoorway.left().getMaxZMapped()) - z
				));
			} else if (RenderVehicles.boxContains(floorOrDoorway.left(), x, ridingVehicleY, z)) {
				// If the intersecting or closest floor or doorway is a doorway, then don't force the player to be in bounds
				// Dismount if the player is not intersecting the doorway
				offsets.add(new Vector3d(0, floorOrDoorway.left().getMaxYMapped(), 0));
			}
		}
	}

	private static void movePlayer(double x, double y, double z) {
		if (InitClient.getGameTick() > 40) {
			final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
			if (clientPlayerEntity == null) {
				return;
			}

			final Runnable runnable = () -> {
				clientPlayerEntity.setFallDistanceMapped(0);
				clientPlayerEntity.setVelocity(0, 0, 0);
				clientPlayerEntity.setMovementSpeed(0);
				clientPlayerEntity.updatePosition(x, y, z);
			};

			runnable.run();
			InitClient.scheduleMovePlayer(runnable);
		}
	}

	private static double getFromScale(double min, double max, double percentage) {
		return (max - min) * percentage + min;
	}
}
