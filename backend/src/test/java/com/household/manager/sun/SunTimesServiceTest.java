package com.household.manager.sun;

import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SunTimesServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final TractiveHomeSettings BERLIN_HOME =
            new TractiveHomeSettings(52.52, 13.405, 100, 500, 60, 30, "Zuhause");
    private static final TractiveHomeSettings NO_HOME =
            new TractiveHomeSettings(null, null, 100, 500, 60, 30, "Zuhause");

    @Mock
    private TractiveHomeSettingsService homeSettings;

    private SunTimesService serviceAt(String isoLocalDateTime) {
        var instant = ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocalDateTime), BERLIN).toInstant();
        return new SunTimesService(homeSettings, Clock.fixed(instant, BERLIN));
    }

    private static void assertWithinMinutes(String expectedLocalTime, ZonedDateTime actual, int toleranceMinutes) {
        LocalTime expected = LocalTime.parse(expectedLocalTime);
        long diffSeconds = Math.abs(Duration.between(expected, actual.toLocalTime()).toSeconds());
        assertTrue(diffSeconds <= toleranceMinutes * 60L,
                () -> "erwartet " + expected + " +/-" + toleranceMinutes + " min, war " + actual.toLocalTime());
    }

    @Test
    void midsummerInBerlin() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunDayTimes t = serviceAt("2026-06-21T12:00").timesFor(LocalDate.of(2026, 6, 21)).orElseThrow();

        assertWithinMinutes("04:43", t.sunrise(), 3);
        assertWithinMinutes("21:33", t.sunset(), 3);
        assertEquals(BERLIN, t.sunrise().getZone());
        assertEquals(LocalDate.of(2026, 6, 21), t.date());
    }

    @Test
    void midwinterInBerlin() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunDayTimes t = serviceAt("2026-12-21T12:00").timesFor(LocalDate.of(2026, 12, 21)).orElseThrow();

        assertWithinMinutes("08:15", t.sunrise(), 3);
        assertWithinMinutes("15:54", t.sunset(), 3);
    }

    @Test
    void civilTwilightSurroundsSunriseAndSunsetOnTheSameDay() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunDayTimes t = serviceAt("2026-09-21T12:00").timesFor(LocalDate.of(2026, 9, 21)).orElseThrow();

        assertTrue(t.dawn().isBefore(t.sunrise()));
        assertTrue(t.sunrise().isBefore(t.sunset()));
        assertTrue(t.sunset().isBefore(t.dusk()));
        long duskMinutes = Duration.between(t.sunset(), t.dusk()).toMinutes();
        assertTrue(duskMinutes >= 25 && duskMinutes <= 70, "buergerliche Daemmerung war " + duskMinutes + " min");
        assertEquals(LocalDate.of(2026, 9, 21), t.dawn().toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 21), t.dusk().toLocalDate());
    }

    @Test
    void noTimesWhereCivilTwilightNeverEnds() {
        // 61 Grad Nord im Hochsommer: Auf-/Untergang existieren, die buergerliche Daemmerung endet nie.
        when(homeSettings.getSettings()).thenReturn(new TractiveHomeSettings(61.0, 13.0, 100, 500, 60, 30, "Nord"));
        assertTrue(serviceAt("2026-06-21T12:00").timesFor(LocalDate.of(2026, 6, 21)).isEmpty());
    }

    @Test
    void withoutHomeCoordinatesThereAreNoTimes() {
        when(homeSettings.getSettings()).thenReturn(NO_HOME);
        assertTrue(serviceAt("2026-09-21T12:00").timesFor(LocalDate.of(2026, 9, 21)).isEmpty());
    }

    @Test
    void readingNeverThrows() {
        when(homeSettings.getSettings()).thenThrow(new IllegalStateException("DB weg"));
        assertTrue(serviceAt("2026-09-21T12:00").timesFor(LocalDate.of(2026, 9, 21)).isEmpty());
    }

    @Test
    void elevationIsPositiveAtNoonAndNegativeAtMidnight() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunTimesService service = serviceAt("2026-06-21T12:00");

        double noon = service.elevationAt(ZonedDateTime.of(2026, 6, 21, 13, 0, 0, 0, BERLIN)).orElseThrow();
        double midnight = service.elevationAt(ZonedDateTime.of(2026, 6, 21, 1, 0, 0, 0, BERLIN)).orElseThrow();
        assertTrue(noon > 55 && noon < 65, "Mittagshoehe war " + noon);
        assertTrue(midnight < 0, "Mitternachtshoehe war " + midnight);
    }

    @Test
    void elevationIsEmptyWithoutHome() {
        when(homeSettings.getSettings()).thenReturn(NO_HOME);
        assertTrue(serviceAt("2026-06-21T12:00").elevationAt(ZonedDateTime.of(2026, 6, 21, 13, 0, 0, 0, BERLIN)).isEmpty());
    }
}
