package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeAvailability;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeMessageParserTest {

    private ZigbeeMessageParser parser;

    @BeforeEach
    void setUp() {
        parser = new ZigbeeMessageParser(new ObjectMapper());
    }

    private BigDecimal valueOf(ParsedZigbeeMessage msg, MeasurementType type) {
        return msg.measurements().stream()
                .filter(m -> m.type() == type)
                .map(ZigbeeMeasurementValue::value)
                .findFirst().orElse(null);
    }

    @Test
    void parsesClimateSensor() {
        String payload = "{\"battery\":90,\"humidity\":55.3,\"linkquality\":120,\"pressure\":1013.2,\"temperature\":21.5,\"voltage\":3000}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Wohnzimmer-Klima", payload);

        assertThat(result).isPresent();
        ParsedZigbeeMessage msg = result.get();
        assertThat(msg.friendlyName()).isEqualTo("Wohnzimmer-Klima");
        assertThat(msg.batteryPercent()).isEqualTo(90);
        assertThat(msg.linkQuality()).isEqualTo(120);
        assertThat(valueOf(msg, MeasurementType.TEMPERATURE)).isEqualByComparingTo("21.5");
        assertThat(valueOf(msg, MeasurementType.HUMIDITY)).isEqualByComparingTo("55.3");
        assertThat(valueOf(msg, MeasurementType.PRESSURE)).isEqualByComparingTo("1013.2");
    }

    @Test
    void parsesContactSensorBooleanAsZeroOne() {
        String payload = "{\"battery\":100,\"contact\":false,\"linkquality\":80}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Haustuer", payload);

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.CONTACT)).isEqualByComparingTo("0");
    }

    @Test
    void parsesMotionAndIlluminance() {
        String payload = "{\"battery\":75,\"illuminance\":12,\"linkquality\":60,\"occupancy\":true}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Bewegung", payload);

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.OCCUPANCY)).isEqualByComparingTo("1");
        assertThat(valueOf(result.get(), MeasurementType.ILLUMINANCE)).isEqualByComparingTo("12");
    }

    @Test
    void parsesWaterLeak() {
        String payload = "{\"battery\":88,\"linkquality\":40,\"water_leak\":true}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Keller-Wasser", payload);

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.WATER_LEAK)).isEqualByComparingTo("1");
    }

    @Test
    void ignoresAvailabilityTopic() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Haustuer/availability", "online");
        assertThat(result).isEmpty();
    }

    @Test
    void ignoresBridgeTopics() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/bridge/state", "{\"state\":\"online\"}");
        assertThat(result).isEmpty();
    }

    @Test
    void ignoresMalformedPayload() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Defekt", "not-json");
        assertThat(result).isEmpty();
    }

    @Test
    void ignoresMessageWithNoUsableFields() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Leer", "{\"voltage\":3000,\"update\":{\"state\":\"idle\"}}");
        assertThat(result).isEmpty();
    }

    @Test
    void deduplicatesIlluminanceWhenBothFieldsPresent() {
        String payload = "{\"battery\":75,\"illuminance\":12,\"illuminance_lux\":12,\"linkquality\":60,\"occupancy\":true}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Bewegung", payload);

        assertThat(result).isPresent();
        long illuminanceCount = result.get().measurements().stream()
                .filter(m -> m.type() == MeasurementType.ILLUMINANCE)
                .count();
        assertThat(illuminanceCount).isEqualTo(1);
        assertThat(valueOf(result.get(), MeasurementType.ILLUMINANCE)).isEqualByComparingTo("12");
    }

    @Test
    void parsesActionFromButtonMessage() {
        String payload = "{\"action\":\"single\",\"battery\":100,\"linkquality\":90}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", payload);

        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo("single");
        assertThat(result.get().friendlyName()).isEqualTo("Flur-Taster");
    }

    @Test
    void messageWithOnlyActionIsValid() {
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", "{\"action\":\"double\"}");

        assertThat(result).isPresent();
        assertThat(result.get().action()).isEqualTo("double");
        assertThat(result.get().measurements()).isEmpty();
    }

    @Test
    void ignoresEmptyLegacyActionReset() {
        // zigbee2mqtt-Legacy-Verhalten: nach der Aktion folgt {"action": ""} als Reset.
        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", "{\"action\":\"\"}");

        assertThat(result).isEmpty();
    }

    @Test
    void discardsActionFromRetainedMessage() {
        // Retained-Nachrichten sind alte Zustände vom Broker — ein Backend-Neustart
        // darf den letzten Tastendruck nicht "nachfeuern".
        String payload = "{\"action\":\"single\",\"battery\":100,\"linkquality\":90}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", payload, true);

        assertThat(result).isPresent();
        assertThat(result.get().action()).isNull();
    }

    @Test
    void nonButtonMessageHasNullAction() {
        String payload = "{\"battery\":100,\"contact\":false,\"linkquality\":80}";

        Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Haustuer", payload);

        assertThat(result).isPresent();
        assertThat(result.get().action()).isNull();
    }

    @Test
    void liestBridgeStateAlsJson() {
        Optional<String> result = parser.parseBridgeState(
                "zigbee2mqtt/bridge/state", "{\"state\":\"online\"}");

        assertThat(result).contains("online");
    }

    @Test
    void liestBridgeStateAlsKlartext() {
        Optional<String> result = parser.parseBridgeState(
                "zigbee2mqtt/bridge/state", "offline");

        assertThat(result).contains("offline");
    }

    @Test
    void ignoriertBridgeStateAufFremdemTopic() {
        assertThat(parser.parseBridgeState("zigbee2mqtt/Kueche", "online")).isEmpty();
    }

    @Test
    void liestGeraeteVerfuegbarkeit() {
        Optional<ZigbeeAvailability> result = parser.parseAvailability(
                "zigbee2mqtt/Temperatur Buero/availability", "{\"state\":\"offline\"}");

        assertThat(result).isPresent();
        assertThat(result.get().friendlyName()).isEqualTo("Temperatur Buero");
        assertThat(result.get().online()).isFalse();
    }

    @Test
    void liestGeraeteVerfuegbarkeitAlsKlartext() {
        Optional<ZigbeeAvailability> result = parser.parseAvailability(
                "zigbee2mqtt/Motion Flur/availability", "online");

        assertThat(result).isPresent();
        assertThat(result.get().online()).isTrue();
    }

    @Test
    void ignoriertBridgeAlsGeraeteVerfuegbarkeit() {
        assertThat(parser.parseAvailability("zigbee2mqtt/bridge/availability", "online"))
                .isEmpty();
    }

    @Test
    void wertTopicsBleibenUnveraendert() {
        Optional<ParsedZigbeeMessage> result =
                parser.parse("zigbee2mqtt/Kueche", "{\"temperature\":21.5}");

        assertThat(result).isPresent();
        assertThat(valueOf(result.get(), MeasurementType.TEMPERATURE))
                .isEqualByComparingTo("21.5");
    }
}
