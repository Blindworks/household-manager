package com.household.manager.dirigera;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ein Sensor des Hubs, aus seinen Teilgeraeten zusammengefuehrt. Thermometer tragen
 * Temperatur/Feuchte, Kontakte nur {@code open}, Bewegungsmelder {@code detected} und
 * {@code illuminance} (Lux); die jeweils anderen Werte sind null.
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
        Boolean detected,
        Integer illuminance,
        Integer batteryPercent,
        boolean reachable,
        Instant lastSeen) {

    /** Thermometer und Kontakte: ohne Bewegungswerte. */
    public DirigeraSensorSnapshot(String relationId, DirigeraSensorKind kind, String name, String room, String model,
                                  BigDecimal temperature, BigDecimal humidity, Boolean open, Integer batteryPercent,
                                  boolean reachable, Instant lastSeen) {
        this(relationId, kind, name, room, model, temperature, humidity, open, null, null, batteryPercent,
                reachable, lastSeen);
    }
}
