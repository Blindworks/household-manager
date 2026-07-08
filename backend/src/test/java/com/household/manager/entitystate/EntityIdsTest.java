package com.household.manager.entitystate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityIdsTest {

    @Test
    void buildsIdFromDomainSourceAndRef() {
        String id = EntityIds.build(EntityDomain.SWITCH, EntitySource.KASA, "8006A1B2", null);
        assertEquals("switch.kasa_8006a1b2", id);
    }

    @Test
    void buildsIdWithMeasurementSuffix() {
        String id = EntityIds.build(EntityDomain.SENSOR, EntitySource.ZIGBEE, "Wohnzimmer Sensor", "temperature");
        assertEquals("sensor.zigbee_wohnzimmer_sensor_temperature", id);
    }

    @Test
    void slugReplacesUmlautsAndSpecialCharacters() {
        assertEquals("kueche_tuer", EntityIds.slug("Küche/Tür"));
    }

    @Test
    void slugCollapsesConsecutiveSeparatorsAndTrims() {
        assertEquals("bad_sensor", EntityIds.slug("  Bad -- Sensor  "));
    }

    @Test
    void binarySensorDomainUsesUnderscorePrefix() {
        String id = EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.ZIGBEE, "Tür", "contact");
        assertEquals("binary_sensor.zigbee_tuer_contact", id);
    }

    @Test
    void buildRejectsSourceRefThatSlugsToEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> EntityIds.build(EntityDomain.SENSOR, EntitySource.ZIGBEE, "!!!", "temperature"));
    }

    @Test
    void buildRejectsBlankSourceRef() {
        assertThrows(IllegalArgumentException.class,
                () -> EntityIds.build(EntityDomain.SENSOR, EntitySource.ZIGBEE, "  ", null));
    }
}
