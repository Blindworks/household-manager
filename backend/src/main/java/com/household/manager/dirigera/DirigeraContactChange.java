package com.household.manager.dirigera;

import java.time.Instant;

/**
 * Zustandswechsel eines Fenster-/Tuerkontakts aus dem Ereignisstrom. {@code key} ist der Schluessel
 * der {@code dirigera_sensor}-Zeile (relationId, sonst Geraete-Id); {@code lastSeen}/{@code reachable}
 * sind null, wenn der Hub sie nicht mitschickt.
 */
public record DirigeraContactChange(String key, boolean open, Instant lastSeen, Boolean reachable) {
}
