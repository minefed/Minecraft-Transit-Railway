package org.mtr.mod.sound;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.random.Random;

public class OffsetSoundInstance extends AbstractSoundInstance {
    private final float startOffsetSecond;
    private final boolean shouldApplyOffset;

    public OffsetSoundInstance(
            SoundEvent soundEvent,
            SoundCategory soundCategory,
            float volume,
            float pitch,
            Random random,
            double x,
            double y,
            double z,
            float startOffsetSecond
    ) {
        super(soundEvent, soundCategory, random);

        System.out.println("[NewAnnouncer] soundOffset: " + startOffsetSecond);

        this.volume = volume;
        this.pitch = pitch;
        this.x = x;
        this.y = y;
        this.z = z;
        this.startOffsetSecond = startOffsetSecond;
        this.shouldApplyOffset = startOffsetSecond > 0.0f;
    }

    public double getStartOffsetSecond() {
        return startOffsetSecond;
    }

    public boolean isShouldApplyOffset() {
        return shouldApplyOffset;
    }
}
