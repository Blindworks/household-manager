package com.household.manager.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;

/**
 * Einzige Definition von "jetzt ist Ruhezeit" für den Hausverstand. Gerechnet wird mit dem
 * {@code Clock}-Bean (Europe/Berlin), nicht mit der Systemzone — sonst läge die Ruhezeit im
 * UTC-Container zwei Stunden daneben (dieselbe Falle wie bei den Cron-Triggern).
 */
@Component
@RequiredArgsConstructor
public class AgentQuietHours {

    private final AgentProperties properties;
    private final Clock clock;

    public boolean isQuietNow() {
        return properties.quietWindow().contains(LocalTime.now(clock));
    }
}
