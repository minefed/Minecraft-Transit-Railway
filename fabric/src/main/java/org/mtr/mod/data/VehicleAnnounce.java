package org.mtr.mod.data;

import java.util.ArrayList;

public class VehicleAnnounce {
    private final String soundId;

    private double currentPlayTime;

    private final int delay;

    private ArrayList<String> listenerUuids = new ArrayList<>();

    public VehicleAnnounce(String soundId, int delay) {
        this.soundId = soundId;
        this.delay = delay;
        this.currentPlayTime = delay != 0 ? -(delay) : 0;
    }

    public double getCurrentPlayTime() {
        return currentPlayTime;
    }

    public String getSoundId() {
        return soundId;
    }

    public int getDelay() {
        return delay;
    }

    public void setCurrentPlayTime(double currentPlayTime) {
        this.currentPlayTime = currentPlayTime;
    }

    public void addListenerUuid(String uuid) {
        this.listenerUuids.add(uuid);
    }

    public boolean uuidIsListening(String uuid) {
        return this.listenerUuids.contains(uuid);
    }
}
