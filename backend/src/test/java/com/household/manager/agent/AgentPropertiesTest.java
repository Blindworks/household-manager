package com.household.manager.agent;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class AgentPropertiesTest {

    @Test
    void parsesQuietHours() {
        AgentProperties p = new AgentProperties();
        p.setQuietHours("23:30-06:00");

        assertTrue(p.quietWindow().contains(LocalTime.of(2, 0)));
        assertFalse(p.quietWindow().contains(LocalTime.of(12, 0)));
    }

    @Test
    void fallsBackToDefaultOnGarbage() {
        AgentProperties p = new AgentProperties();
        p.setQuietHours("abends");

        assertTrue(p.quietWindow().contains(LocalTime.of(23, 0)));
        assertFalse(p.quietWindow().contains(LocalTime.of(8, 0)));
    }
}
