package org.mtr.mod.render;

/** Client-only change counter for the blocks read by {@link RenderVehicleHelper#canOpenDoors}. */
public interface DoorScanChunkAccess {

	long mtr$getDoorScanEpoch();
}
