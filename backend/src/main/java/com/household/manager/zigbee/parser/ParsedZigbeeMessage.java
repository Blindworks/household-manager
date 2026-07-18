package com.household.manager.zigbee.parser;

import java.util.List;

/**
 * Ergebnis des Parsens einer zigbee2mqtt-Gerätenachricht.
 * {@code action} ist die Taster-Aktion (z. B. "single", "double", "hold");
 * {@code null}, wenn die Nachricht keine (oder eine leere) Aktion enthält.
 */
public record ParsedZigbeeMessage(
        String friendlyName,
        Integer batteryPercent,
        Integer linkQuality,
        List<ZigbeeMeasurementValue> measurements,
        String action
) {
}
