package com.household.manager.agent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AgentQuietHoursTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private AgentQuietHours at(int hour, int minute) {
        Clock clock = Clock.fixed(ZonedDateTime.of(2026, 10, 12, hour, minute, 0, 0, BERLIN).toInstant(), BERLIN);
        AgentProperties properties = new AgentProperties();
        properties.setQuietHours("22:00-07:00");
        return new AgentQuietHours(properties, clock);
    }

    @Test
    void lateEveningIsQuiet() {
        assertTrue(at(23, 30).isQuietNow());
    }

    @Test
    void earlyMorningBeforeEndIsQuiet() {
        assertTrue(at(6, 59).isQuietNow());
    }

    @Test
    void noonIsNotQuiet() {
        assertFalse(at(12, 0).isQuietNow());
    }

    @Test
    void endOfWindowIsExclusive() {
        assertFalse(at(7, 0).isQuietNow());
    }
}
