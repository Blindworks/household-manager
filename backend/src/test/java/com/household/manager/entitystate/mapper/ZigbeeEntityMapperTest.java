package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZigbeeEntityMapperTest {

    private final ZigbeeEntityMapper mapper = new ZigbeeEntityMapper();

    @Test
    void mapsNumericMeasurementToSensorEntity() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Wohnzimmer Sensor", 87, 120,
                List.of(new ZigbeeMeasurementValue(MeasurementType.TEMPERATURE, new BigDecimal("21.5"), "°C")),
                null);

        List<EntityStateUpdate> updates = mapper.map(message);

        assertEquals(1, updates.size());
        EntityStateUpdate update = updates.get(0);
        assertEquals("sensor.zigbee_wohnzimmer_sensor_temperature", update.entityId());
        assertEquals(EntityDomain.SENSOR, update.domain());
        assertEquals("21.5", update.state());
        assertEquals("°C", update.attributes().get("unit"));
        assertEquals(87, update.attributes().get("batteryPercent"));
        assertEquals(120, update.attributes().get("linkQuality"));
        assertEquals("Wohnzimmer Sensor Temperatur", update.friendlyName());
        assertEquals("temperature", update.attributes().get("deviceClass"));
    }

    @Test
    void mapsClosedContactToOff() {
        // zigbee2mqtt: contact=true bedeutet Magnet anliegend, also Tür GESCHLOSSEN.
        // HA-Konvention fuer binary_sensor.door: off = geschlossen.
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Haustür", null, null,
                List.of(new ZigbeeMeasurementValue(MeasurementType.CONTACT, BigDecimal.ONE, "")),
                null);

        List<EntityStateUpdate> updates = mapper.map(message);

        EntityStateUpdate update = updates.get(0);
        assertEquals("binary_sensor.zigbee_haustuer_contact", update.entityId());
        assertEquals(EntityDomain.BINARY_SENSOR, update.domain());
        assertEquals("off", update.state());
        assertEquals("door", update.attributes().get("deviceClass"));
        assertFalse(update.attributes().containsKey("batteryPercent"));
    }

    @Test
    void mapsOpenContactToOn() {
        // contact=false (BigDecimal.ZERO) bedeutet Magnet nicht anliegend, also Tür OFFEN.
        // HA-Konvention: on = offen.
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Haustür", null, null,
                List.of(new ZigbeeMeasurementValue(MeasurementType.CONTACT, BigDecimal.ZERO, "")),
                null);

        assertEquals("on", mapper.map(message).get(0).state());
    }

    @Test
    void mapsZeroBinaryValueToOff() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Haustür", null, null,
                List.of(new ZigbeeMeasurementValue(MeasurementType.OCCUPANCY, BigDecimal.ZERO, "")),
                null);

        assertEquals("off", mapper.map(message).get(0).state());
    }

    @Test
    void mapsOccupancyDetectedToOn() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Flur", null, null,
                List.of(new ZigbeeMeasurementValue(MeasurementType.OCCUPANCY, BigDecimal.ONE, "")),
                null);

        assertEquals("on", mapper.map(message).get(0).state());
    }

    @Test
    void mapsMultipleMeasurementsToMultipleEntities() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Bad", 50, 100,
                List.of(
                        new ZigbeeMeasurementValue(MeasurementType.TEMPERATURE, new BigDecimal("22.0"), "°C"),
                        new ZigbeeMeasurementValue(MeasurementType.HUMIDITY, new BigDecimal("55"), "%")),
                null);

        assertEquals(2, mapper.map(message).size());
    }

    @Test
    void mapsActionToEventEntity() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage("Flur-Taster", 100, 90, List.of(), "single");

        EntityStateUpdate update = mapper.mapAction(message).orElseThrow();

        assertEquals("event.zigbee_flur_taster_action", update.entityId());
        assertEquals(EntityDomain.EVENT, update.domain());
        assertEquals("single", update.state());
        assertEquals("button", update.attributes().get("deviceClass"));
        assertEquals(100, update.attributes().get("batteryPercent"));
        assertEquals(90, update.attributes().get("linkQuality"));
        assertEquals("Flur-Taster Taster", update.friendlyName());
        assertEquals("Flur-Taster", update.sourceRef());
    }

    @Test
    void mapActionTransliteratesUmlautsInEntityId() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage("Küchen-Taster", null, null, List.of(), "hold");

        assertEquals("event.zigbee_kuechen_taster_action", mapper.mapAction(message).orElseThrow().entityId());
    }

    @Test
    void mapActionIsEmptyWithoutAction() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage("Haustür", null, null, List.of(), null);

        assertTrue(mapper.mapAction(message).isEmpty());
    }
}
