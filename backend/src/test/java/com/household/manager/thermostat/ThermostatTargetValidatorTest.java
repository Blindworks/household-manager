package com.household.manager.thermostat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThermostatTargetValidatorTest {

    @ParameterizedTest
    @ValueSource(doubles = {5.0, 5.5, 21.5, 22.0, 30.0})
    void acceptsValuesInRangeOnHalfDegreeGrid(double value) {
        assertDoesNotThrow(() -> ThermostatTargetValidator.validate(value));
    }

    @ParameterizedTest
    @ValueSource(doubles = {21.3, 4.5, 30.5, 4.99, 0.0, -1.0, 100.0})
    void rejectsOffGridOrOutOfRangeValues(double value) {
        assertThrows(IllegalArgumentException.class, () -> ThermostatTargetValidator.validate(value));
    }

    @Test
    void rejectsNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> ThermostatTargetValidator.validate(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ThermostatTargetValidator.validate(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> ThermostatTargetValidator.validate(Double.NEGATIVE_INFINITY));
    }
}
