package com.household.manager.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTokenBudgetTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final AgentProperties properties = new AgentProperties();
    private final AgentRunRecorder recorder = mock(AgentRunRecorder.class);
    private final AgentNotifier notifier = mock(AgentNotifier.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return BERLIN; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private AgentTokenBudget budget;

    @BeforeEach
    void setUp() {
        properties.setDailyTokenBudget(1000);
        budget = new AgentTokenBudget(properties, recorder, notifier, clock);
    }

    @Test
    void belowBudgetIsNotExhausted() {
        when(recorder.tokensToday()).thenReturn(new long[]{400, 599});

        assertFalse(budget.isExhausted());
        verify(notifier, never()).sendInfo(anyString());
    }

    @Test
    void atBudgetIsExhausted() {
        when(recorder.tokensToday()).thenReturn(new long[]{600, 400});

        assertTrue(budget.isExhausted());
    }

    @Test
    void aboveBudgetIsExhausted() {
        when(recorder.tokensToday()).thenReturn(new long[]{5000, 5000});

        assertTrue(budget.isExhausted());
    }

    @Test
    void zeroBudgetMeansNoLimit() {
        properties.setDailyTokenBudget(0);
        when(recorder.tokensToday()).thenReturn(new long[]{Long.MAX_VALUE / 4, Long.MAX_VALUE / 4});

        assertFalse(budget.isExhausted());
        verify(notifier, never()).sendInfo(anyString());
    }

    @Test
    void unreadableUsageFailsClosed() {
        when(recorder.tokensToday()).thenThrow(new IllegalStateException("db weg"));

        assertTrue(budget.isExhausted());
        assertEquals(0, budget.usedToday());
    }

    @Test
    void exhaustionNotifiesOncePerDay() {
        when(recorder.tokensToday()).thenReturn(new long[]{600, 400});

        budget.isExhausted();
        budget.isExhausted();
        budget.isExhausted();

        verify(notifier, times(1)).sendInfo(
                "Hausverstand: Tagesbudget von 1000 Tokens erreicht — keine weiteren Läufe bis Mitternacht.");
    }

    @Test
    void notifiesAgainOnTheNextDay() {
        when(recorder.tokensToday()).thenReturn(new long[]{600, 400});
        budget.isExhausted();

        now.set(now.get().plus(1, ChronoUnit.DAYS));
        budget.isExhausted();

        verify(notifier, times(2)).sendInfo(anyString());
    }

    @Test
    void usedTodaySumsInputAndOutput() {
        when(recorder.tokensToday()).thenReturn(new long[]{123, 45});

        assertEquals(168, budget.usedToday());
        assertEquals(1000, budget.budget());
    }
}
