package net.polze.adolarradio;

import org.junit.Test;
import static org.junit.Assert.*;

public class RadioJingleScheduleTest {
    @Test public void intervalSurvivesBatchBoundaries() {
        RadioJingleSchedule schedule = new RadioJingleSchedule();
        for (int i = 1; i <= 20; i++) {
            assertEquals(i % 5 == 0, schedule.afterTrack(1, 5));
        }
    }

    @Test public void stationsHaveIndependentIntervals() {
        RadioJingleSchedule schedule = new RadioJingleSchedule();
        assertFalse(schedule.afterTrack(1, 2));
        assertFalse(schedule.afterTrack(2, 2));
        assertTrue(schedule.afterTrack(1, 2));
        assertTrue(schedule.afterTrack(2, 2));
    }

    @Test public void disabledJinglesResetTheInterval() {
        RadioJingleSchedule schedule = new RadioJingleSchedule();
        assertFalse(schedule.afterTrack(1, 2));
        assertFalse(schedule.afterTrack(1, 0));
        assertFalse(schedule.afterTrack(1, 2));
        assertTrue(schedule.afterTrack(1, 2));
    }
}
