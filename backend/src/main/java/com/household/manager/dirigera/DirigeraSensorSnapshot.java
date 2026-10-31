package com.household.manager.dirigera;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ein Sensor des Hubs, aus seinen Teilgeraeten zusammengefuehrt. Thermometer tragen
 * Temperatur/Feuchte, Kontakte nur {@code open}; die jeweils anderen Werte sind null.
 */
public record DirigeraSensorSnapshot(
        String relationId,
        DirigeraSensorKind kind,
        String name,
        String room,
        String model,
        BigDecimal temperature,
        BigDecimal humidity,
        Boolean open,
        Integer batteryPercent,
        boolean reachable,
        Instant lastSeen) {
}
