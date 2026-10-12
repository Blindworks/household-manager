package com.household.manager.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class AgentFailureStreakTest {

    private AgentNotifier notifier;
    private AgentFailureStreak streak;

    @BeforeEach
    void setUp() {
        notifier = mock(AgentNotifier.class);
        streak = new AgentFailureStreak(notifier);
    }

    private static AgentRunResult fail() {
        return new AgentRunResult(false, null, "kaputt", 0, 0, 0, 1);
    }

    private static AgentRunResult ok() {
        return new AgentRunResult(true, "ok", null, 1, 1, 0, 1);
    }

    @Test
    void twoFailuresDoNotNotify() {
        streak.onResult(fail());
        streak.onResult(fail());
        verifyNoInteractions(notifier);
    }

    @Test
    void thirdFailureNotifiesExactlyOnceAndFourthStaysSilent() {
        for (int i = 0; i < 4; i++) {
            streak.onResult(fail());
        }
        verify(notifier, times(1)).sendInfo(contains("3 Läufe in Folge fehlgeschlagen — letzter Fehler: kaputt"));
    }

    @Test
    void successResetsSoThatNextSeriesNotifiesAgain() {
        for (int i = 0; i < 3; i++) {
            streak.onResult(fail());
        }
        streak.onResult(ok());
        for (int i = 0; i < 3; i++) {
            streak.onResult(fail());
        }
        verify(notifier, times(2)).sendInfo(anyString());
    }
}
