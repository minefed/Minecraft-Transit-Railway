package org.mtr.mod.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.random.Random;
import org.mtr.mapping.holder.Identifier;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

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

    public static double getDuration(String soundPath) {
        try {
            double duration = calculateDuration(Path.of(soundPath).toFile());

            return duration;
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        return 0;
    }

    public static double getDuration(Identifier soundId) {
        String path = "/assets/" + soundId.getNamespace() + "/sounds/" + soundId.getPath() + ".ogg";

        return getDuration(path);
    }

    private static double calculateDuration(final File oggFile) throws IOException {
        int rate = -1;
        int length = -1;
        int size = (int) oggFile.length();
        byte[] t = new byte[size];
        FileInputStream stream = new FileInputStream(oggFile);

        stream.read(t);

        for (int i = size-1-8-2-4; i>=0 && length<0; i--) {
            if (
                    t[i]==(byte)'O'
                            && t[i+1]==(byte)'g'
                            && t[i+2]==(byte)'g'
                            && t[i+3]==(byte)'S'
            ) {
                byte[] byteArray = new byte[]{t[i+6],t[i+7],t[i+8],t[i+9],t[i+10],t[i+11],t[i+12],t[i+13]};
                ByteBuffer bb = ByteBuffer.wrap(byteArray);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                length = bb.getInt(0);
            }
        }

        for (int i = 0; i< size - 8 - 2 - 4 && rate < 0; i++) {
            if (
                    t[i]==(byte)'v'
                            && t[i+1]==(byte)'o'
                            && t[i+2]==(byte)'r'
                            && t[i+3]==(byte)'b'
                            && t[i+4]==(byte)'i'
                            && t[i+5]==(byte)'s'
            ) {
                byte[] byteArray = new byte[]{t[i+11],t[i+12],t[i+13],t[i+14]};
                ByteBuffer bb = ByteBuffer.wrap(byteArray);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                rate = bb.getInt(0);
            }
        }

        stream.close();

        double duration = (double) (length * 1000) / (double) rate;

        return duration;
    }
}
