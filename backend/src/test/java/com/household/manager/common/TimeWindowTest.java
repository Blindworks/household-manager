package com.household.manager.common;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class TimeWindowTest {

    private static final TimeWindow DAY = new TimeWindow(LocalTime.of(5, 0), LocalTime.of(20, 0));
    private static final TimeWindow NIGHT = new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0));

    @Test
    void containsTimeInsideSameDayWindow() {
        assertTrue(DAY.contains(LocalTime.of(12, 0)));
    }

    @Test
    void excludesTimeOutsideSameDayWindow() {
        assertFalse(DAY.contains(LocalTime.of(4, 59)));
        assertFalse(DAY.contains(LocalTime.of(21, 0)));
    }

    @Test
    void startIsInclusiveEndIsExclusive() {
        assertTrue(DAY.contains(LocalTime.of(5, 0)));
        assertFalse(DAY.contains(LocalTime.of(20, 0)));
    }

    @Test
    void windowSpanningMidnightCoversBothSides() {
        assertTrue(NIGHT.contains(LocalTime.of(23, 30)));
        assertTrue(NIGHT.contains(LocalTime.of(2, 0)));
        assertTrue(NIGHT.contains(LocalTime.MIDNIGHT));
        assertFalse(NIGHT.contains(LocalTime.of(12, 0)));
        assertFalse(NIGHT.contains(LocalTime.of(6, 0)));
    }

    @Test
    void equalStartAndEndIsEmptyNotFull() {
        TimeWindow empty = new TimeWindow(LocalTime.of(8, 0), LocalTime.of(8, 0));
        assertFalse(empty.contains(LocalTime.of(8, 0)));
        assertFalse(empty.contains(LocalTime.of(12, 0)));
        assertTrue(empty.isEmpty());
        assertFalse(DAY.isEmpty());
    }

    @Test
    void rejectsNullBounds() {
        assertThrows(NullPointerException.class, () -> new TimeWindow(null, LocalTime.NOON));
        assertThrows(NullPointerException.class, () -> new TimeWindow(LocalTime.NOON, null));
    }
}
