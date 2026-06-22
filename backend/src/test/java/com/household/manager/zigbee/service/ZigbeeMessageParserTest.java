package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
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
}
