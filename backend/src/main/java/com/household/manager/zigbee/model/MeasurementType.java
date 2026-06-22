package com.household.manager.zigbee.model;

import lombok.Getter;

/**
 * Type of a Zigbee measurement together with its default unit.
 */
@Getter
public enum MeasurementType {

    TEMPERATURE("\u00b0C"),
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
