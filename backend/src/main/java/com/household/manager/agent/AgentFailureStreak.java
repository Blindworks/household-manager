package com.household.manager.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Meldet eine Fehlserie genau einmal. Ein einzelner Fehlschlag (API-Aussetzer) ist Rauschen; erst
 * drei in Folge deuten auf ein echtes Problem (Key, Budget, Modell). Danach bleibt es still bis zum
 * nächsten Erfolg, sonst würde die Warnung bei jedem Takt wiederholt und irgendwann ignoriert.
 */
@Component
@RequiredArgsConstructor
public class AgentFailureStreak {

    static final int FAILURES_BEFORE_WARNING = 3;

    private final AgentNotifier notifier;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicBoolean warned = new AtomicBoolean();

    public void onResult(AgentRunResult result) {
        if (result.success()) {
            failures.set(0);
            warned.set(false);
            return;
        }
        if (failures.incrementAndGet() >= FAILURES_BEFORE_WARNING && warned.compareAndSet(false, true)) {
            notifier.sendInfo("Hausverstand: " + FAILURES_BEFORE_WARNING
                    + " Läufe in Folge fehlgeschlagen — letzter Fehler: " + result.error());
        }
    }
}
