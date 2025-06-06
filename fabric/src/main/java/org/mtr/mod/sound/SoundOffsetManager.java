package org.mtr.mod.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SoundOffsetManager {
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static void scheduleOffsetApplication(SoundInstance soundInstance, float offsetSecond) {
        scheduler.schedule(() -> {
            applySoundOffset(soundInstance, offsetSecond);
        }, 50, TimeUnit.MILLISECONDS);
    }

    private static void applySoundOffset(SoundInstance soundInstance, float offsetSecond) {
        try {
            MinecraftClient minecraftClient = MinecraftClient.getInstance();

            if (minecraftClient.getSoundManager() == null) return;

            Object soundSystem = getSoundSystem(minecraftClient.getSoundManager());

            if (soundSystem == null) return;

            Integer sourceId = findSourceForSound(soundSystem, soundInstance);

            if (sourceId != null && sourceId != 0) {
                AL10.alSourcef(sourceId, AL11.AL_SEC_OFFSET, offsetSecond);

                int error = AL10.alGetError();

                if (error != AL10.AL_NO_ERROR) {
                    System.err.println("OpenAL error applying offset: " + error);
                }
            }

        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private static Object getSoundSystem(Object soundManager) {
        try {
            Field soundSystemField = soundManager.getClass().getDeclaredField("soundSystem");

            soundSystemField.setAccessible(true);

            return soundSystemField.get(soundManager);
        } catch (Exception exception) {
            try {
                Field[] fields = soundManager.getClass().getDeclaredFields();

                for (Field field: fields) {
                    field.setAccessible(true);

                    Object value = field.get(soundManager);

                    if (value != null && value.getClass().getSimpleName().contains("SoundSystem")) {
                        return value;
                    }
                }
            } catch (Exception exception1) {
                exception1.printStackTrace();
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Integer findSourceForSound(Object soundSystem, SoundInstance soundInstance) {
        try {
            Field[] fields = soundSystem.getClass().getDeclaredFields();

            for (Field field: fields) {
                field.setAccessible(true);

                Object value = field.get(soundSystem);

                if (value instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) value;

                    for (Map.Entry<?, ?> entry: map.entrySet()) {
                        if (entry.getKey() == soundInstance) {
                            return extractSourceId(entry.getValue());
                        }
                    }
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        return null;
    }

    private static Integer extractSourceId(Object sourceInfo) {
        try {
            Field[] fields = sourceInfo.getClass().getDeclaredFields();

            for (Field field : fields) {
                field.setAccessible(true);

                Object value = field.get(sourceInfo);

                if (value instanceof Integer) {
                    Integer intValue = (Integer) value;

                    if (intValue > 0 && AL10.alIsSource(intValue)) {
                        return intValue;
                    }
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }

        return null;
    }

    public static void shutdown() {
        scheduler.shutdown();
    }
}
