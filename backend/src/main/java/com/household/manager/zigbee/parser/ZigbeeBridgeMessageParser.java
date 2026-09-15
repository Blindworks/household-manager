package com.household.manager.zigbee.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parst die Bridge-Topics von zigbee2mqtt (devices, info, event, response/*).
 * <p>
 * Wirft nie. Ein unparsebares Payload ergibt {@code Optional.empty()} und eine
 * Warnung — der Aufrufer laesst dann das alte Registry stehen. Ein Format-Bruch einer
 * kuenftigen z2m-Version darf die Seite nicht leeren. Unbekannte Felder werden
 * ignoriert; bekannte werden in beiden Formen gelesen, die z2m 1.x und 2.x verwenden
 * ({@code interview_completed} vs. {@code interview_state}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZigbeeBridgeMessageParser {

    private static final String DEVICES_TOPIC = "zigbee2mqtt/bridge/devices";
    private static final String INFO_TOPIC = "zigbee2mqtt/bridge/info";
    private static final String EVENT_TOPIC = "zigbee2mqtt/bridge/event";
    private static final String RESPONSE_PREFIX = "zigbee2mqtt/bridge/response/";
    private static final String COORDINATOR_TYPE = "Coordinator";
    private static final Set<String> KNOWN_EVENT_TYPES =
            Set.of("device_joined", "device_interview", "device_announce", "device_leave");

    private final ObjectMapper objectMapper;

    public Optional<List<ZigbeeBridgeDevice>> parseDevices(String topic, String payload) {
        if (!DEVICES_TOPIC.equals(topic)) {
            return Optional.empty();
        }
        JsonNode root = readTree(payload);
        if (root == null || !root.isArray()) {
            log.warn("zigbee2mqtt bridge/devices nicht lesbar — altes Verzeichnis bleibt stehen");
            return Optional.empty();
        }
        List<ZigbeeBridgeDevice> devices = new ArrayList<>();
        for (JsonNode node : root) {
            if (COORDINATOR_TYPE.equals(text(node, "type"))) {
                continue;
            }
            String ieee = text(node, "ieee_address");
            String friendlyName = text(node, "friendly_name");
            if (ieee == null || friendlyName == null) {
                continue;
            }
            JsonNode definition = node.get("definition");
            boolean hasDefinition = definition != null && definition.isObject();
            devices.add(new ZigbeeBridgeDevice(
                    ieee,
                    friendlyName,
                    text(node, "type"),
                    text(node, "power_source"),
                    interviewCompleted(node),
                    node.path("supported").asBoolean(false),
                    hasDefinition ? text(definition, "model") : null,
                    hasDefinition ? text(definition, "vendor") : null,
                    hasDefinition ? text(definition, "description") : null,
                    node.hasNonNull("network_address") && node.get("network_address").isNumber()
                            ? node.get("network_address").asInt() : null));
        }
        return Optional.of(List.copyOf(devices));
    }

    /**
     * z2m 1.x: {@code interview_completed: true}; z2m 2.x: {@code interview_state:
     * "SUCCESSFUL"}. Beide Formen lesen, damit die Seite nicht mit der Add-on-Version
     * bricht.
     */
    private boolean interviewCompleted(JsonNode node) {
        JsonNode completed = node.get("interview_completed");
        if (completed != null && completed.isBoolean()) {
            return completed.asBoolean();
        }
        return "SUCCESSFUL".equalsIgnoreCase(text(node, "interview_state"));
    }

    public Optional<ZigbeeBridgeInfo> parseInfo(String topic, String payload) {
        if (!INFO_TOPIC.equals(topic)) {
            return Optional.empty();
        }
        JsonNode root = readTree(payload);
        if (root == null || !root.isObject()) {
            log.warn("zigbee2mqtt bridge/info nicht lesbar — alter Stand bleibt stehen");
            return Optional.empty();
        }
        boolean permitJoin = root.path("permit_join").asBoolean(false);
        JsonNode end = root.get("permit_join_end");
        Instant permitJoinEnd = (permitJoin && end != null && end.isNumber())
                ? Instant.ofEpochMilli(end.asLong()) : null;
        return Optional.of(new ZigbeeBridgeInfo(
                text(root, "version"),
                permitJoin,
                permitJoinEnd,
                availabilityEnabled(root.path("config").get("availability"))));
    }

    /**
     * z2m 1.x: {@code availability: true} oder ein Objekt mit active/passive (= aktiv);
     * z2m 2.x: Objekt mit {@code enabled}. Fehlend oder {@code false} = aus.
     */
    private boolean availabilityEnabled(JsonNode availability) {
        if (availability == null || availability.isNull()) {
            return false;
        }
        if (availability.isBoolean()) {
            return availability.asBoolean();
        }
        if (availability.isObject()) {
            JsonNode enabled = availability.get("enabled");
            return enabled == null || !enabled.isBoolean() || enabled.asBoolean();
        }
        return false;
    }

    public Optional<ZigbeeBridgeEvent> parseEvent(String topic, String payload) {
        if (!EVENT_TOPIC.equals(topic)) {
            return Optional.empty();
        }
        JsonNode root = readTree(payload);
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String type = text(root, "type");
        if (type == null || !KNOWN_EVENT_TYPES.contains(type)) {
            return Optional.empty();
        }
        JsonNode data = root.path("data");
        JsonNode definition = data.get("definition");
        boolean hasDefinition = definition != null && definition.isObject();
        return Optional.of(new ZigbeeBridgeEvent(
                type,
                text(data, "friendly_name"),
                text(data, "ieee_address"),
                text(data, "status"),
                hasDefinition ? text(definition, "model") : null,
                hasDefinition ? text(definition, "vendor") : null,
                null));
    }

    public Optional<ZigbeeBridgeResponse> parseResponse(String topic, String payload) {
        if (topic == null || !topic.startsWith(RESPONSE_PREFIX) || topic.length() <= RESPONSE_PREFIX.length()) {
            return Optional.empty();
        }
        JsonNode root = readTree(payload);
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String request = topic.substring(RESPONSE_PREFIX.length());
        boolean ok = "ok".equalsIgnoreCase(text(root, "status"));
        return Optional.of(new ZigbeeBridgeResponse(request, text(root, "transaction"), ok,
                ok ? null : text(root, "error")));
    }

    private JsonNode readTree(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.debug("Bridge-Payload nicht parsebar: {}", ex.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value != null && value.isTextual() && !value.asText().isBlank()) ? value.asText() : null;
    }
}
