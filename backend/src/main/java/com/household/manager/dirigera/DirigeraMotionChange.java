package com.household.manager.dirigera;

import java.time.Instant;

/**
 * Zustandswechsel eines Bewegungsmelders aus dem Ereignisstrom. {@code key} ist der Schluessel der
 * {@code dirigera_sensor}-Zeile (relationId, sonst Geraete-Id). Der Hub schickt nur geaenderte
 * Attribute: {@code detected} kommt vom {@code occupancySensor}, {@code illuminance} vom
 * {@code lightSensor} — der jeweils andere Wert ist null. {@code lastSeen}/{@code reachable} sind
 * null, wenn der Hub sie nicht mitschickt.
 */
public record DirigeraMotionChange(String key, Boolean detected, Integer illuminance, Instant lastSeen,
                                   Boolean reachable) {
}
