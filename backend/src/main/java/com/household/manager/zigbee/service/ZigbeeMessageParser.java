package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parst zigbee2mqtt-Wert-Topics (zigbee2mqtt/&lt;friendly_name&gt;) in {@link ParsedZigbeeMessage}.
 * Steuer-/Meta-Topics (bridge/*, /availability, /set, /get) werden ignoriert.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZigbeeMessageParser {

    private static final String TOPIC_PREFIX = "zigbee2mqtt/";

    /** zigbee2mqtt-Feldname -> Messgröße. */
    private static final Map<String, MeasurementType> FIELD_TYPES = new LinkedHashMap<>();

    static {
        FIELD_TYPES.put("temperature", MeasurementType.TEMPERATURE);
        FIELD_TYPES.put("humidity", MeasurementType.HUMIDITY);
        FIELD_TYPES.put("pressure", MeasurementType.PRESSURE);
        FIELD_TYPES.put("contact", MeasurementType.CONTACT);
        FIELD_TYPES.put("occupancy", MeasurementType.OCCUPANCY);
        FIELD_TYPES.put("illuminance", MeasurementType.ILLUMINANCE);
        FIELD_TYPES.put("illuminance_lux", MeasurementType.ILLUMINANCE);
        FIELD_TYPES.put("water_leak", MeasurementType.WATER_LEAK);
    }

    private final ObjectMapper objectMapper;

    public Optional<ParsedZigbeeMessage> parse(String topic, String payload) {
        return parse(topic, payload, false);
    }

    /**
     * @param retained MQTT-Retained-Flag; Aktionen aus retained Nachrichten werden
     *                 verworfen, damit ein Reconnect keinen alten Tastendruck nachfeuert
     */
    public Optional<ParsedZigbeeMessage> parse(String topic, String payload, boolean retained) {
        if (!isDeviceTopic(topic)) {
            return Optional.empty();
        }
        String friendlyName = topic.substring(TOPIC_PREFIX.length());

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.debug("Zigbee payload not parseable for topic {}: {}", topic, ex.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        Integer battery = intOrNull(root, "battery");
        Integer linkQuality = intOrNull(root, "linkquality");
        String action = retained ? null : actionOrNull(root);

        List<ZigbeeMeasurementValue> measurements = new ArrayList<>();
        for (Map.Entry<String, MeasurementType> entry : FIELD_TYPES.entrySet()) {
            JsonNode node = root.get(entry.getKey());
            BigDecimal value = toDecimal(node);
            if (value == null) {
                continue;
            }
            MeasurementType type = entry.getValue();
            boolean alreadyPresent = measurements.stream().anyMatch(m -> m.type() == type);
            if (alreadyPresent) {
                continue;
            }
            measurements.add(new ZigbeeMeasurementValue(type, value, type.getDefaultUnit()));
        }

        if (measurements.isEmpty() && battery == null && linkQuality == null && action == null) {
            return Optional.empty();
        }
        return Optional.of(new ParsedZigbeeMessage(friendlyName, battery, linkQuality, measurements, action));
    }

    private boolean isDeviceTopic(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) {
            return false;
        }
        String rest = topic.substring(TOPIC_PREFIX.length());
        return !rest.isEmpty() && !rest.contains("/") && !rest.equals("bridge");
    }

    private BigDecimal toDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return null;
    }

    private Integer intOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return (node != null && node.isNumber()) ? node.asInt() : null;
    }

    private String actionOrNull(JsonNode root) {
        JsonNode node = root.get("action");
        return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText() : null;
    }
}
