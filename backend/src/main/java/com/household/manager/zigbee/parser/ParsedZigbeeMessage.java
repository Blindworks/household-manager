package com.household.manager.zigbee.parser;

import java.util.List;

/**
 * Ergebnis des Parsens einer zigbee2mqtt-Gerätenachricht.
 * {@code action} ist die Taster-Aktion (z. B. "single", "double", "hold");
 * {@code null}, wenn die Nachricht keine (oder eine leere) Aktion enthält.
 * {@code retained} ist das MQTT-Retained-Flag: der Broker spielt solche Nachrichten
 * nach jedem Re-Subscribe erneut aus, ohne dass das Geraet gefunkt haette — sie
 * beweisen deshalb nicht, dass das Geraet lebt.
 */
public record ParsedZigbeeMessage(
        String friendlyName,
        Integer batteryPercent,
        Integer linkQuality,
        List<ZigbeeMeasurementValue> measurements,
        String action,
        boolean retained
) {
}
