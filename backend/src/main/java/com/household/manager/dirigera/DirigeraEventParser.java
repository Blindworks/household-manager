package com.household.manager.dirigera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Liest Nachrichten des Hub-WebSockets. Aufgezeichnet am 2026-11-03 gegen den echten Hub
 * (Fixture {@code remote-press-event.json}): ein Tastendruck ist
 * {@code {"type":"remotePressEvent","time":…,"data":{"id":"<geraet>_1","clickPattern":"singlePress"}}};
 * Zustandsmeldungen ({@code deviceStateChanged}) tragen nur die geaenderten Attribute; ausgewertet
 * werden davon {@code isOpen} der Fenster-/Tuerkontakte sowie {@code isDetected}/{@code illuminance}
 * der Bewegungsmelder (aufgezeichnet 2026-11-16), alles andere liefert weiter der Poll.
 * Wirft nie.
 */
@Component
public class DirigeraEventParser {

    private static final String REMOTE_PRESS = "remotePressEvent";
    private static final String STATE_CHANGED = "deviceStateChanged";
    private static final String CONTACT_TYPE = "openCloseSensor";
    private static final String OCCUPANCY_TYPE = "occupancySensor";
    private static final String LIGHT_TYPE = "lightSensor";

    private final ObjectMapper mapper;

    public DirigeraEventParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<DirigeraRemotePress> parseRemotePress(String json) {
        JsonNode root = read(json, REMOTE_PRESS);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode data = root.path("data");
        String deviceId = nonBlank(data.path("id"));
        String clickPattern = nonBlank(data.path("clickPattern"));
        if (deviceId == null || clickPattern == null) {
            return Optional.empty();
        }
        return Optional.of(new DirigeraRemotePress(deviceId, clickPattern, instant(root.path("time"))));
    }

    /**
     * Kontaktwechsel: {@code deviceStateChanged} eines {@code openCloseSensor} mit {@code isOpen}.
     * Schluessel wie im Parser der Geraeteliste: {@code relationId}, sonst die Geraete-Id.
     */
    public Optional<DirigeraContactChange> parseContactChange(String json) {
        JsonNode root = read(json, STATE_CHANGED);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode data = root.path("data");
        JsonNode isOpen = data.path("attributes").path("isOpen");
        if (!CONTACT_TYPE.equals(data.path("deviceType").asText()) || !isOpen.isBoolean()) {
            return Optional.empty();
        }
        String key = key(data);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.of(new DirigeraContactChange(key, isOpen.asBoolean(), instant(data.path("lastSeen")),
                reachable(data)));
    }

    /**
     * Bewegungsmelder: {@code deviceStateChanged} des {@code occupancySensor} mit {@code isDetected}
     * oder des {@code lightSensor} mit {@code illuminance}. Leer, wenn keiner der beiden Werte dabei ist.
     */
    public Optional<DirigeraMotionChange> parseMotionChange(String json) {
        JsonNode root = read(json, STATE_CHANGED);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode data = root.path("data");
        String type = data.path("deviceType").asText();
        JsonNode attributes = data.path("attributes");
        Boolean detected = null;
        Integer illuminance = null;
        if (OCCUPANCY_TYPE.equals(type) && attributes.path("isDetected").isBoolean()) {
            detected = attributes.path("isDetected").asBoolean();
        } else if (LIGHT_TYPE.equals(type) && attributes.path("illuminance").isNumber()) {
            illuminance = DirigeraIlluminance.toLux(attributes.path("illuminance").asInt());
        }
        String key = key(data);
        if ((detected == null && illuminance == null) || key == null) {
            return Optional.empty();
        }
        return Optional.of(new DirigeraMotionChange(key, detected, illuminance, instant(data.path("lastSeen")),
                reachable(data)));
    }

    /** Schluessel wie im Parser der Geraeteliste: {@code relationId}, sonst die Geraete-Id. */
    private static String key(JsonNode data) {
        String key = nonBlank(data.path("relationId"));
        return key != null ? key : nonBlank(data.path("id"));
    }

    private static Boolean reachable(JsonNode data) {
        JsonNode reachable = data.path("isReachable");
        return reachable.isBoolean() ? reachable.asBoolean() : null;
    }

    private JsonNode read(String json, String type) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (IOException e) {
            return null;
        }
        return root != null && root.isObject() && type.equals(root.path("type").asText()) ? root : null;
    }

    private static String nonBlank(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Instant instant(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
