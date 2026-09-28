package org.mtr.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.mod.render.DoorScanChunkAccess;
import org.mtr.mod.render.RenderVehicleHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidates cached door scans when a client chunk's platform or platform door blocks may change. */
@Mixin(WorldChunk.class)
public abstract class WorldChunkDoorScanMixin implements DoorScanChunkAccess {

	@Shadow @Final World world;
	@Unique private long mtr$doorScanEpoch;

	@Inject(method = "setBlockState", at = @At("HEAD"))
	private void mtr$checkDoorScanBlock(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> callback) {
		if (world.isClient() && (RenderVehicleHelper.affectsDoorScan(state.getBlock()) || RenderVehicleHelper.affectsDoorScan(((WorldChunk) (Object) this).getBlockState(pos).getBlock()))) {
			mtr$doorScanEpoch++;
		}
	}

	@Inject(method = "loadFromPacket", at = @At("HEAD"))
	private void mtr$reloadDoorScanChunk(CallbackInfo callback) {
		mtr$doorScanEpoch++;
	}

	@Override
	public long mtr$getDoorScanEpoch() {
		return mtr$doorScanEpoch;
	}
}
