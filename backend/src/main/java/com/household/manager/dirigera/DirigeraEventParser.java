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
 * wird davon nur {@code isOpen} der Fenster-/Tuerkontakte, alles andere liefert weiter der Poll.
 * Wirft nie.
 */
@Component
public class DirigeraEventParser {

    private static final String REMOTE_PRESS = "remotePressEvent";
    private static final String STATE_CHANGED = "deviceStateChanged";
    private static final String CONTACT_TYPE = "openCloseSensor";

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
        String key = nonBlank(data.path("relationId"));
        if (key == null) {
            key = nonBlank(data.path("id"));
        }
        if (key == null) {
            return Optional.empty();
        }
        JsonNode reachable = data.path("isReachable");
        return Optional.of(new DirigeraContactChange(key, isOpen.asBoolean(), instant(data.path("lastSeen")),
                reachable.isBoolean() ? reachable.asBoolean() : null));
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
