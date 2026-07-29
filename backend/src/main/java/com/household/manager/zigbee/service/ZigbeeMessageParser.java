package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeAvailability;
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
 * Parst zigbee2mqtt-Topics. {@link #parse(String, String)} liest Wert-Topics
 * (zigbee2mqtt/&lt;friendly_name&gt;) in {@link ParsedZigbeeMessage} und ignoriert dabei
 * Steuer-/Meta-Topics (bridge/*, /availability, /set, /get). Diese Meta-Topics werden
 * von den dedizierten Methoden {@link #parseBridgeState} und {@link #parseAvailability}
 * gelesen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZigbeeMessageParser {

    private static final String TOPIC_PREFIX = "zigbee2mqtt/";
    private static final String BRIDGE_STATE_TOPIC = "zigbee2mqtt/bridge/state";
    private static final String AVAILABILITY_SUFFIX = "/availability";
    private static final String BRIDGE_NAME = "bridge";

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

    /**
     * Liest den Zustand von zigbee2mqtt selbst ({@code online}/{@code offline}).
     * Leer, wenn das Topic nicht {@value #BRIDGE_STATE_TOPIC} ist.
     */
    public Optional<String> parseBridgeState(String topic, String payload) {
        if (!BRIDGE_STATE_TOPIC.equals(topic)) {
            return Optional.empty();
        }
        return stateFromPayload(payload);
    }

    /**
     * Liest die Verfuegbarkeit eines einzelnen Geraets.
     * <p>
     * Geraetenamen mit '/' werden — wie schon in {@link #isDeviceTopic} — nicht
     * unterstuetzt; das ist eine bewusst geteilte Annahme mit dem bestehenden
     * Identitaetsmodell.
     */
    public Optional<ZigbeeAvailability> parseAvailability(String topic, String payload) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX) || !topic.endsWith(AVAILABILITY_SUFFIX)) {
            return Optional.empty();
        }
        // Praefix- und Suffix-Pruefung koennen sich ueberlappen, z.B. beim Topic
        // "zigbee2mqtt/availability" (ein Geraet, das "availability" heisst). Ohne
        // diese Schranke waere beginIndex > endIndex und substring wuerfe.
        int end = topic.length() - AVAILABILITY_SUFFIX.length();
        if (end <= TOPIC_PREFIX.length()) {
            return Optional.empty();
        }
        String name = topic.substring(TOPIC_PREFIX.length(), end);
        if (name.contains("/") || BRIDGE_NAME.equals(name)) {
            return Optional.empty();
        }
        return stateFromPayload(payload)
                .map(state -> new ZigbeeAvailability(name, "online".equalsIgnoreCase(state)));
    }

    /**
     * Neuere zigbee2mqtt-Versionen senden {@code {"state":"online"}}, aeltere den
     * nackten Text {@code online}. Beides muss funktionieren.
     */
    private Optional<String> stateFromPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        String trimmed = payload.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);
                JsonNode state = root.get("state");
                if (state != null && state.isTextual() && !state.asText().isBlank()) {
                    return Optional.of(state.asText().trim());
                }
            } catch (Exception ex) {
                log.debug("Zigbee-Status-Payload nicht parsebar: {}", ex.getMessage());
            }
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    private boolean isDeviceTopic(String topic) {
        if (topic == null || !topic.startsWith(TOPIC_PREFIX)) {
            return false;
        }
        String rest = topic.substring(TOPIC_PREFIX.length());
        return !rest.isEmpty() && !rest.contains("/") && !rest.equals(BRIDGE_NAME);
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
