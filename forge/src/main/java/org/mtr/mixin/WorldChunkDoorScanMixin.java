package org.mtr.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
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
@Mixin(LevelChunk.class)
public abstract class WorldChunkDoorScanMixin implements DoorScanChunkAccess {

	@Shadow @Final Level level;
	@Unique private long mtr$doorScanEpoch;

	@Inject(method = "setBlockState", at = @At("HEAD"))
	private void mtr$checkDoorScanBlock(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> callback) {
		if (level.isClientSide() && (RenderVehicleHelper.affectsDoorScan(state.getBlock()) || RenderVehicleHelper.affectsDoorScan(((LevelChunk) (Object) this).getBlockState(pos).getBlock()))) {
			mtr$doorScanEpoch++;
		}
	}

	@Inject(method = "replaceWithPacketData", at = @At("HEAD"))
	private void mtr$reloadDoorScanChunk(CallbackInfo callback) {
		mtr$doorScanEpoch++;
	}

	@Override
	public long mtr$getDoorScanEpoch() {
		return mtr$doorScanEpoch;
	}
}
