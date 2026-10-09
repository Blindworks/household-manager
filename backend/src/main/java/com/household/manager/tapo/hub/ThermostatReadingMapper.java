package com.household.manager.tapo.hub;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Bildet die Antwort von {@code get_child_device_list} auf {@link ThermostatReading} ab.
 * Fail-safe: fehlende Felder ergeben {@code null} bzw. {@code false}, nie einen geratenen Wert.
 * Feldnamen stuetzen sich auf dokumentierte Semantik, nicht auf einen Realtest.
 */
@Component
public class ThermostatReadingMapper {

    /** Ab dieser Zieltemperatur gilt ein Thermostat ohne Frostschutz-Flag als "aus". */
    static final double OFF_TARGET_TEMP = 5.0;

    public List<ThermostatReading> map(JsonNode result) {
        List<ThermostatReading> readings = new ArrayList<>();
        if (result == null) {
            return readings;
        }
        for (JsonNode child : result.path("child_device_list")) {
            if (isThermostat(child)) {
                readings.add(toReading(child));
            }
        }
        return readings;
    }

    private boolean isThermostat(JsonNode child) {
        String category = child.path("category").asText("").toLowerCase(Locale.ROOT);
        String model = child.path("model").asText("").toUpperCase(Locale.ROOT);
        return category.contains("thermostat") || category.contains("trv") || model.startsWith("KE100");
    }

    private ThermostatReading toReading(JsonNode child) {
        Double targetTemp = number(child, "target_temp");
        return new ThermostatReading(
                child.path("device_id").asText(),
                decodeNickname(child.path("nickname").asText(null)),
                "online".equalsIgnoreCase(child.path("status").asText("")),
                number(child, "current_temp"),
                targetTemp,
                deriveMode(child, targetTemp),
                isHeating(child),
                child.path("at_low_battery").asBoolean(false));
    }

    private ThermostatMode deriveMode(JsonNode child, Double targetTemp) {
        if (child.path("frost_protection_on").asBoolean(false)) {
            return ThermostatMode.FROST_PROTECTION;
        }
        if (targetTemp != null && targetTemp <= OFF_TARGET_TEMP) {
            return ThermostatMode.OFF;
        }
        return ThermostatMode.MANUAL;
    }

    /** Unbekannte Werte von trv_state heizen nicht: lieber "kalt" anzeigen als ein geratenes "heizt". */
    private boolean isHeating(JsonNode child) {
        if (isHeatingState(child.path("trv_state").asText(null))) {
            return true;
        }
        for (JsonNode state : child.path("trv_states")) {
            if (isHeatingState(state.asText(null))) {
                return true;
            }
        }
        return false;
    }

    private boolean isHeatingState(String state) {
        return "heating".equalsIgnoreCase(state) || "on".equalsIgnoreCase(state);
    }

    private Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    /** Der Nickname kommt Base64-kodiert; was sich nicht als UTF-8 dekodieren laesst, bleibt unveraendert. */
    private String decodeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return nickname;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(nickname);
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded)).toString().trim();
            return text.isBlank() ? nickname : text;
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            return nickname;
        }
    }
}
