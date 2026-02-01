package org.mtr.mod.sound;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.SoundCategory;
import org.mtr.mapping.mapper.MovingSoundInstanceExtension;
import org.mtr.mapping.mapper.SoundHelper;

public class TrainAnnouncementSoundInstance extends MovingSoundInstanceExtension {

	private static final float SILENT_VOLUME = 0.0001F;

	public TrainAnnouncementSoundInstance(String soundId) {
		super(SoundHelper.createSoundEvent(new Identifier(soundId)), SoundCategory.getBlocksMapped());
		setIsRepeatableMapped(false);
		setRepeatDelay(0);
		setVolume(SILENT_VOLUME);
		setPitch(1);
	}

	public void update(BlockPos blockPos, boolean isRiding) {
		setX(blockPos.getX());
		setY(blockPos.getY());
		setZ(blockPos.getZ());
		setVolume(isRiding ? 1 : SILENT_VOLUME);
	}

	@Override
	public void tick2() {
	}

	@Override
	public boolean shouldAlwaysPlay2() {
		return true;
	}

	@Override
	public boolean canPlay2() {
		return true;
	}
}
