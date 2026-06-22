package com.household.manager.zigbee.parser;

import java.util.List;

/**
 * Ergebnis des Parsens einer zigbee2mqtt-Gerätenachricht.
 */
public record ParsedZigbeeMessage(
        String friendlyName,
        Integer batteryPercent,
        Integer linkQuality,
        List<ZigbeeMeasurementValue> measurements
) {
}
