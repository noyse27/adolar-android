package net.polze.adolarradio;

import java.util.HashMap;
import java.util.Map;

final class RadioJingleSchedule {
    private final Map<Integer, Integer> scheduled = new HashMap<>();

    synchronized boolean afterTrack(int stationId, int every) {
        if (every <= 0) {
            scheduled.remove(stationId);
            return false;
        }
        int count = scheduled.containsKey(stationId) ? scheduled.get(stationId) : 0;
        count++;
        boolean due = count >= every;
        scheduled.put(stationId, due ? 0 : count);
        return due;
    }
}
