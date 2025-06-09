package org.mtr.mod.block;

import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mod.BlockEntityTypes;

import javax.annotation.Nonnull;

public class BlockTrainAnnouncer extends BlockTrainSensorBase {

	@Nonnull
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new BlockEntity(blockPos, blockState);
	}

	public static void processQueue() {
	}

	public static class BlockEntity extends BlockEntityBase implements Utilities {

		private String message = "";
		private String soundId = "";
		private int delay;
		private static final String KEY_MESSAGE = "message";
		private static final String KEY_SOUND_ID = "sound_id";
		private static final String KEY_DELAY = "delay";

		public BlockEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.TRAIN_ANNOUNCER.get(), pos, state);
		}

		@Override
		public void readCompoundTag(CompoundTag compoundTag) {
			message = compoundTag.getString(KEY_MESSAGE);
			soundId = compoundTag.getString(KEY_SOUND_ID);
			delay = compoundTag.getInt(KEY_DELAY);
			super.readCompoundTag(compoundTag);
		}

		@Override
		public void writeCompoundTag(CompoundTag compoundTag) {
			compoundTag.putString(KEY_MESSAGE, message);
			compoundTag.putString(KEY_SOUND_ID, soundId);
			compoundTag.putInt(KEY_DELAY, delay);
			super.writeCompoundTag(compoundTag);
		}

		public void setData(LongAVLTreeSet filterRouteIds, boolean stoppedOnly, boolean movingOnly, String message, String soundId, int delay) {
			this.message = message;
			this.soundId = soundId;
			this.delay = delay;
			setData(filterRouteIds, stoppedOnly, movingOnly);
		}

		public String getMessage() {
			return message;
		}

		public String getSoundId() {
			return soundId;
		}

		public int getDelay() {
			return delay;
		}
	}
}
