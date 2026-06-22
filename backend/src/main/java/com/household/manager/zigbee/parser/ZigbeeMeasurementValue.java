package com.household.manager.zigbee.parser;

import com.household.manager.zigbee.model.MeasurementType;

import java.math.BigDecimal;

/**
 * Ein einzelner geparster Messwert vor der Persistenz.
 */
public record ZigbeeMeasurementValue(MeasurementType type, BigDecimal value, String unit) {
}
