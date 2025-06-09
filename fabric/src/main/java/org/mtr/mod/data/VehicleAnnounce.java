package org.mtr.mod.data;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mod.sound.OffsetSoundHelper;

import java.util.ArrayList;

public class VehicleAnnounce {
    private final String soundId;

    private final int delay;

    private final ArrayList<String> listenerUuids = new ArrayList<>();

    private long playStartTime;

    private double totalLength;

    public VehicleAnnounce(String soundId, int delay) {
        this.soundId = soundId;
        this.delay = delay;
        this.playStartTime = System.currentTimeMillis();
        this.totalLength = OffsetSoundHelper.getDuration(new Identifier(soundId)) + (delay * 1000);
    }

    public long getPlayStartTime() {
        return playStartTime;
    }

    public double getTotalLength() {
        return totalLength;
    }

    public String getSoundId() {
        return soundId;
    }

    public int getDelay() {
        return delay;
    }

    public void addListenerUuid(String uuid) {
        this.listenerUuids.add(uuid);
    }

    public boolean uuidIsListening(String uuid) {
        return this.listenerUuids.contains(uuid);
    }
}
