package com.household.manager.zigbee.parser;

/**
 * Verfuegbarkeitsmeldung eines Zigbee-Geraets aus dem Topic
 * {@code zigbee2mqtt/<friendly_name>/availability}.
 */
public record ZigbeeAvailability(String friendlyName, boolean online) {
}
