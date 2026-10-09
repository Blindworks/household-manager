package com.household.manager.tapo.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThermostatReadingMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThermostatReadingMapper mapper = new ThermostatReadingMapper();
    private List<ThermostatReading> readings;

    @BeforeEach
    void loadFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tapo/hub/child-device-list-ke100.json")) {
            JsonNode result = objectMapper.readTree(in);
            readings = mapper.map(result);
        }
    }

    private ThermostatReading byId(String id) {
        return readings.stream().filter(r -> r.childDeviceId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Nur Thermostate werden uebernommen, ein T315-Sensor wird verworfen")
    void discardsNonThermostatChildren() {
        assertEquals(4, readings.size());
        assertTrue(readings.stream().noneMatch(r -> r.childDeviceId().startsWith("T315")));
    }

    @Test
    @DisplayName("Heizender KE100: Werte, Modus MANUAL, Nickname dekodiert")
    void mapsHeatingThermostat() {
        ThermostatReading living = byId("KE100-WOHNZIMMER");
        assertEquals("Wohnzimmer", living.nickname());
        assertTrue(living.online());
        assertEquals(20.5, living.currentTemp());
        assertEquals(22.0, living.targetTemp());
        assertEquals(ThermostatMode.MANUAL, living.mode());
        assertTrue(living.heating());
        assertFalse(living.batteryLow());
    }

    @Test
    @DisplayName("Offline-Geraet: fehlende Temperaturen sind null, nie 0; Umlaute im Nickname bleiben erhalten")
    void offlineThermostatHasNoInventedValues() {
        ThermostatReading kitchen = byId("KE100-KUECHE");
        assertEquals("Küche", kitchen.nickname());
        assertFalse(kitchen.online());
        assertNull(kitchen.currentTemp());
        assertNull(kitchen.targetTemp());
        assertFalse(kitchen.heating());
        assertTrue(kitchen.batteryLow());
    }

    @Test
    @DisplayName("frost_protection_on ergibt FROST_PROTECTION, auch bei 5 Grad Ziel")
    void frostProtectionWins() {
        assertEquals(ThermostatMode.FROST_PROTECTION, byId("KE100-SCHLAFZIMMER").mode());
    }

    @Test
    @DisplayName("Ziel 5 Grad ohne Frostschutz ist OFF; unbekannter trv_state heizt nicht")
    void targetFiveMeansOffAndUnknownTrvStateIsNotHeating() {
        ThermostatReading bath = byId("KE100-BAD");
        assertEquals(ThermostatMode.OFF, bath.mode());
        assertFalse(bath.heating());
    }

    @Test
    @DisplayName("Kein result-Knoten oder keine Kinderliste ergibt eine leere Liste")
    void emptyOnMissingList() {
        assertTrue(mapper.map(objectMapper.createObjectNode()).isEmpty());
        assertTrue(mapper.map(null).isEmpty());
    }
}
