package com.household.manager.communicator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Einzige Definition, welche Felder als Zugangsdaten gelten. Wirkt über Namen, also auch für neue Endpunkte. */
final class SecretFields {

    private static final Pattern SECRET = Pattern.compile(
            ".*(password|passwort|passwd|secret|token|credential|apikey|privatekey|accesskey|authkey).*|pin|pincode");
    private static final List<String> NAME_KEYS = List.of("key", "name", "settingkey");
    private static final List<String> VALUE_KEYS = List.of("value", "settingvalue");

    private SecretFields() {
    }

    static boolean isSecret(String field) {
        return SECRET.matcher(normalize(field)).matches();
    }

    /** Entfernt Zugangsdaten in-place, auch aus Paaren wie {@code {"key":"access_token","value":"…"}}. */
    static JsonNode strip(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> remove = new ArrayList<>();
            boolean secretPair = false;
            for (var entry : object.properties()) {
                secretPair |= NAME_KEYS.contains(normalize(entry.getKey())) && entry.getValue().isTextual()
                        && isSecret(entry.getValue().asText());
            }
            for (var entry : object.properties()) {
                // Zahlen und Ja/Nein wie inputTokens oder mustChangePassword verraten nichts, eine PIN schon.
                boolean harmless = entry.getValue().isBoolean()
                        || (entry.getValue().isNumber() && !normalize(entry.getKey()).startsWith("pin"));
                if ((isSecret(entry.getKey()) && !harmless)
                        || (secretPair && VALUE_KEYS.contains(normalize(entry.getKey())))) {
                    remove.add(entry.getKey());
                }
            }
            object.remove(remove);
        }
        node.forEach(SecretFields::strip);
        return node;
    }

    private static String normalize(String field) {
        return field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
