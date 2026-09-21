package com.household.manager.sun;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SunDayTimesTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private static ZonedDateTime at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    // dawn 06:35, sunrise 07:07, sunset 19:10, dusk 19:42
    private static final SunDayTimes TIMES = new SunDayTimes(DAY, at("06:35"), at("07:07"), at("19:10"), at("19:42"));

    @Test
    void phaseIsDayBetweenSunriseInclusiveAndSunsetExclusive() {
        assertEquals(SunPhase.DAY, TIMES.phaseAt(at("07:07")));
        assertEquals(SunPhase.DAY, TIMES.phaseAt(at("12:00")));
        assertEquals(SunPhase.DUSK, TIMES.phaseAt(at("19:10")));
    }

    @Test
    void phaseIsDuskBetweenSunsetAndCivilDusk() {
        assertEquals(SunPhase.DUSK, TIMES.phaseAt(at("19:30")));
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("19:42")));
    }

    @Test
    void phaseIsDawnBetweenCivilDawnAndSunrise() {
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("06:34")));
        assertEquals(SunPhase.DAWN, TIMES.phaseAt(at("06:35")));
        assertEquals(SunPhase.DAWN, TIMES.phaseAt(at("07:06")));
    }

    @Test
    void phaseIsNightAroundMidnight() {
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("00:00")));
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("23:59")));
    }

    @Test
    void timeOfReturnsTheMatchingMoment() {
        assertEquals(at("06:35"), TIMES.timeOf(SunEvent.DAWN));
        assertEquals(at("07:07"), TIMES.timeOf(SunEvent.SUNRISE));
        assertEquals(at("19:10"), TIMES.timeOf(SunEvent.SUNSET));
        assertEquals(at("19:42"), TIMES.timeOf(SunEvent.DUSK));
    }

    @Test
    void phaseKeysAreLowercase() {
        assertEquals("day", SunPhase.DAY.key());
        assertEquals("dusk", SunPhase.DUSK.key());
        assertEquals("night", SunPhase.NIGHT.key());
        assertEquals("dawn", SunPhase.DAWN.key());
    }
}
