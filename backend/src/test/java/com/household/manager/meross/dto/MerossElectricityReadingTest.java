package com.household.manager.meross.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MerossElectricityReadingTest {

    private static final String DEVICE_ID = "2112156531504590863548e1e9817420";

    @Test
    void parsesGeraeteEinheitenZuWattVoltAmpere() {
        // Gerät liefert mW, 0,1 V und mA
        Map<String, Object> payload = Map.of("electricity", Map.of(
                "channel", 0, "power", 1234567, "voltage", 2301, "current", 5432));

        Optional<MerossElectricityReading> reading =
                MerossElectricityReading.fromPayload(DEVICE_ID, "Waschmaschine", payload);

        assertThat(reading).isPresent();
        assertThat(reading.get().powerWatts()).isEqualByComparingTo(new BigDecimal("1234.6"));
        assertThat(reading.get().voltageVolts()).isEqualByComparingTo(new BigDecimal("230.1"));
        assertThat(reading.get().currentAmps()).isEqualByComparingTo(new BigDecimal("5.432"));
        assertThat(reading.get().deviceName()).isEqualTo("Waschmaschine");
    }

    @Test
    void akzeptiertZahlenAlsStrings() {
        Map<String, Object> payload = Map.of("electricity", Map.of("power", "1500"));

        Optional<MerossElectricityReading> reading =
                MerossElectricityReading.fromPayload(DEVICE_ID, "Waschmaschine", payload);

        assertThat(reading).isPresent();
        assertThat(reading.get().powerWatts()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(reading.get().voltageVolts()).isNull();
        assertThat(reading.get().currentAmps()).isNull();
    }

    @Test
    void leerBeiNullPayload() {
        assertThat(MerossElectricityReading.fromPayload(DEVICE_ID, "Waschmaschine", null)).isEmpty();
    }

    @Test
    void leerOhneElectricityBlock() {
        assertThat(MerossElectricityReading.fromPayload(DEVICE_ID, "Waschmaschine", Map.of("foo", "bar"))).isEmpty();
    }

    @Test
    void leerOhneLeistungswert() {
        Map<String, Object> payload = Map.of("electricity", Map.of("voltage", 2301));

        assertThat(MerossElectricityReading.fromPayload(DEVICE_ID, "Waschmaschine", payload)).isEmpty();
    }
}
