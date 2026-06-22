package com.household.manager.zigbee.model;

import lombok.Getter;

/**
 * Typ einer Zigbee-Messgröße samt zugehöriger Standard-Einheit.
 */
@Getter
public enum MeasurementType {

    TEMPERATURE("°C"),
    HUMIDITY("%"),
    PRESSURE("hPa"),
    CONTACT(""),
    OCCUPANCY(""),
    ILLUMINANCE("lx"),
    WATER_LEAK("");

    private final String defaultUnit;

    MeasurementType(String defaultUnit) {
        this.defaultUnit = defaultUnit;
    }
}
