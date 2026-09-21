package com.household.manager.sun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SunTimeExpressionTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    @Mock
    private SunTimesService sunTimes;

    private static ZonedDateTime at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    private static final SunDayTimes TIMES = new SunDayTimes(DAY, at("06:35"), at("07:07"), at("19:10"), at("19:42"));

    @Test
    void parsesFixedTime() {
        assertEquals(new SunTimeExpression.Fixed(LocalTime.of(5, 0)), SunTimeExpression.parse("05:00"));
        assertEquals(new SunTimeExpression.Fixed(LocalTime.of(23, 30)), SunTimeExpression.parse(" 23:30 "));
    }

    @Test
    void parsesSunEventWithAndWithoutOffset() {
        assertEquals(new SunTimeExpression.Relative(SunEvent.SUNSET, 0), SunTimeExpression.parse("sunset"));
        assertEquals(new SunTimeExpression.Relative(SunEvent.SUNSET, -30), SunTimeExpression.parse("sunset-30"));
        assertEquals(new SunTimeExpression.Relative(SunEvent.DAWN, 15), SunTimeExpression.parse("dawn+15"));
        assertEquals(new SunTimeExpression.Relative(SunEvent.DUSK, 0), SunTimeExpression.parse("DUSK"));
    }

    @Test
    void rejectsGarbageSpacesInsideAndOffsetOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("noon"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("25:00"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("sunset -30"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("sunset-300"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("sunset+"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse(null));
    }

    @Test
    void fixedResolvesWithoutAskingTheService() {
        Optional<LocalTime> t = SunTimeExpression.parse("05:00").resolve(DAY, sunTimes);
        assertEquals(Optional.of(LocalTime.of(5, 0)), t);
        verifyNoInteractions(sunTimes);
    }

    @Test
    void relativeResolvesAgainstThatDaysTimesPlusOffset() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(TIMES));
        assertEquals(Optional.of(LocalTime.of(18, 40)), SunTimeExpression.parse("sunset-30").resolve(DAY, sunTimes));
        assertEquals(Optional.of(LocalTime.of(6, 35)), SunTimeExpression.parse("dawn").resolve(DAY, sunTimes));
    }

    @Test
    void relativeIsEmptyWithoutHome() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.empty());
        assertTrue(SunTimeExpression.parse("sunset").resolve(DAY, sunTimes).isEmpty());
    }

    @Test
    void negativeOffsetAcrossMidnightLandsOnTheClockFace() {
        SunDayTimes juneTimes = new SunDayTimes(DAY, at("03:50"), at("04:50"), at("21:30"), at("22:20"));
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(juneTimes));
        assertEquals(Optional.of(LocalTime.of(23, 50)), SunTimeExpression.parse("dawn-240").resolve(DAY, sunTimes));
    }

    @Test
    void parsesEveryKeywordOfSunEvent() {
        for (SunEvent event : SunEvent.values()) {
            assertEquals(new SunTimeExpression.Relative(event, 0), SunTimeExpression.parse(event.key()));
            assertEquals(new SunTimeExpression.Relative(event, -5), SunTimeExpression.parse(event.key() + "-5"));
        }
    }
}
