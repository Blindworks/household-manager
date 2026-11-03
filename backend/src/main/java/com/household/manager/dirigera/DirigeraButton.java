package com.household.manager.dirigera;

/**
 * Eine Taste eines Dirigera-Tasters ({@code genericSwitch}, z. B. BILRESA). Ein Doppeltaster
 * erscheint als zwei Eintraege mit gleicher {@code relationId}; die Geraete-Id ({@code …_1},
 * {@code …_2}) identifiziert die Taste und steht so in jedem {@code remotePressEvent}.
 */
public record DirigeraButton(String deviceId, String name, String room, String model,
                             String position, Integer batteryPercent) {
}
