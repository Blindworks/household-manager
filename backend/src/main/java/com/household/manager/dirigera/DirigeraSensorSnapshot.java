package com.household.manager.dirigera;

import java.math.BigDecimal;
import java.time.Instant;

/** Ein Temperatur-/Feuchtesensor des Hubs, aus seinen Teilgeraeten zusammengefuehrt. */
public record DirigeraSensorSnapshot(
        String relationId,
        String name,
        String room,
        String model,
        BigDecimal temperature,
        BigDecimal humidity,
        Integer batteryPercent,
        boolean reachable,
        Instant lastSeen) {
}
