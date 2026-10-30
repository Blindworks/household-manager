package com.household.manager.dirigera;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest die Geraeteliste des Hubs. Ein physischer Sensor (TIMMERFLOTTE) erscheint als zwei
 * Teilgeraete mit gleicher {@code relationId}; welches welchen Wert traegt, entscheidet das
 * Feld, nicht das Suffix der Id.
 */
@Component
public class DirigeraDeviceParser {

    private static final String SENSOR_TYPE = "environmentSensor";
    private static final String GATEWAY_TYPE = "gateway";
    private static final int VALUE_SCALE = 2;

    private final ObjectMapper mapper;

    public DirigeraDeviceParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public DirigeraDevices parse(String json) {
        JsonNode root = readRoot(json);
        String gatewayId = null;
        Map<String, List<JsonNode>> sensorParts = new LinkedHashMap<>();
        for (JsonNode device : root) {
            String type = device.path("deviceType").asText("");
            if (GATEWAY_TYPE.equals(type) && gatewayId == null) {
                gatewayId = textOrNull(device.get("id"));
            } else if (SENSOR_TYPE.equals(type)) {
                String relationId = textOrNull(device.get("relationId"));
                if (relationId != null) {
                    sensorParts.computeIfAbsent(relationId, k -> new ArrayList<>()).add(device);
                }
            }
        }
        List<DirigeraSensorSnapshot> sensors = new ArrayList<>();
        sensorParts.forEach((relationId, parts) -> sensors.add(merge(relationId, parts)));
        return new DirigeraDevices(gatewayId, sensors);
    }

    private JsonNode readRoot(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new DirigeraException("Geraeteliste des Hubs ist kein lesbares JSON", e);
        }
        if (root == null || !root.isArray()) {
            throw new DirigeraException("Geraeteliste des Hubs ist kein JSON-Array");
        }
        return root;
    }

    private DirigeraSensorSnapshot merge(String relationId, List<JsonNode> parts) {
        BigDecimal temperature = null;
        BigDecimal humidity = null;
        Integer battery = null;
        Instant lastSeen = null;
        String name = null;
        String model = null;
        String room = null;
        boolean reachable = true;
        for (JsonNode part : parts) {
            JsonNode attributes = part.path("attributes");
            if (temperature == null) {
                temperature = decimal(attributes.get("currentTemperature"));
            }
            if (humidity == null) {
                humidity = decimal(attributes.get("currentRH"));
            }
            JsonNode batteryNode = attributes.get("batteryPercentage");
            if (batteryNode != null && batteryNode.isNumber()) {
                battery = battery == null ? batteryNode.asInt() : Math.min(battery, batteryNode.asInt());
            }
            Instant seen = instant(part.get("lastSeen"));
            if (seen != null && (lastSeen == null || seen.isAfter(lastSeen))) {
                lastSeen = seen;
            }
            if (name == null) {
                name = nonBlank(textOrNull(attributes.get("customName")));
            }
            if (model == null) {
                model = nonBlank(textOrNull(attributes.get("model")));
            }
            // der Raum steht nur am ersten Teilgeraet
            if (room == null) {
                room = nonBlank(textOrNull(part.path("room").get("name")));
            }
            if (!part.path("isReachable").asBoolean(false)) {
                reachable = false;
            }
        }
        return new DirigeraSensorSnapshot(relationId, name != null ? name : model, room, model,
                temperature, humidity, battery, reachable, lastSeen);
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return new BigDecimal(node.asText()).setScale(VALUE_SCALE, RoundingMode.HALF_UP);
    }

    private static Instant instant(JsonNode node) {
        String text = textOrNull(node);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
