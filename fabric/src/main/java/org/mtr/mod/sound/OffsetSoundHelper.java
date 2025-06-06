package org.mtr.mod.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.random.Random;

public class OffsetSoundHelper {
    public static void playSound(SoundEvent sound, SoundCategory category,
                                 float volume, float pitch, float offsetSeconds) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.player == null) return;

        OffsetSoundInstance offsetSound = new OffsetSoundInstance(
                sound,
                category,
                volume,
                pitch,
                Random.create(),
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                offsetSeconds
        );

        client.getSoundManager().play(offsetSound);
    }
}
