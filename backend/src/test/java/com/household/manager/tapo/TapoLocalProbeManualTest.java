package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Manueller Diagnose-Probe fuer moderne TP-Link-Geraete (Leuchtmittel), die das
 * KLAP- oder AES-securePassthrough-Protokoll ueber HTTP sprechen statt des
 * legacy Kasa-Protokolls auf Port 9999.
 *
 * <p>Laeuft NIE in einem normalen {@code mvn test} (System-Property-Gate) und
 * braucht echten Netzwerkzugriff auf das Zielgeraet sowie bereits konfigurierte
 * Tapo-Zugangsdaten. Bewusst OHNE Spring-Kontext: keine Datenbank, kein Web-Layer,
 * nur ein direkter Aufruf ueber {@link TapoDeviceFactory}.
 *
 * <p>Aufruf:
 * <pre>
 * export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
 * cd backend
 * mvn test -Dtest=TapoLocalProbeManualTest -DfailIfNoTests=false \
 *     -DprobeEnabled=true -Dprobe.ip=192.168.1.114
 * </pre>
 */
@EnabledIfSystemProperty(named = "probeEnabled", matches = "true")
class TapoLocalProbeManualTest {

    /** Matcht Spring-Platzhalter wie {@code ${TAPO_EMAIL:}}, die application.properties
     *  fuer Secrets nutzt. Ohne Spring-Kontext muss das hier von Hand aufgeloest werden. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_]+)(:(.*))?}$");

    /** Feldnamen, deren WERT vor der Ausgabe entfernt wird (Feldname bleibt sichtbar). */
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "mac", "device_mac",
            "device_id", "hw_id", "oem_id", "fw_id",
            "ssid",
            "latitude", "longitude",
            "serial", "serial_number", "device_serial"
    );

    @Test
    void probeLocalHandshake() throws IOException {
        String ip = System.getProperty("probe.ip");
        if (ip == null || ip.isBlank()) {
            fail("System-Property 'probe.ip' fehlt, z. B. -Dprobe.ip=192.168.1.114");
            return;
        }

        Properties appProperties = loadApplicationProperties();
        String email = resolvePlaceholder(appProperties.getProperty("tapo.email", ""));
        String password = resolvePlaceholder(appProperties.getProperty("tapo.password", ""));

        if (email.isBlank() || password.isBlank()) {
            fail("Keine Tapo-Zugangsdaten verfuegbar (tapo.email/tapo.password bzw. die "
                    + "Umgebungsvariablen TAPO_EMAIL/TAPO_PASSWORD sind leer). Ohne Zugangsdaten "
                    + "von Konto 1 kann kein Handshake versucht werden.");
            return;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        TapoDeviceFactory factory = new TapoDeviceFactory(objectMapper);

        System.out.println("=== TP-Link Lokal-Probe gegen " + ip + " (Konto 1) ===");

        JsonNode deviceInfo = tryProtocol(factory, TapoAuthProtocol.KLAP, ip, email, password, "KLAP");
        if (deviceInfo == null) {
            deviceInfo = tryProtocol(factory, TapoAuthProtocol.AES, ip, email, password, "AES");
        }

        if (deviceInfo == null) {
            fail("Weder KLAP noch AES konnten einen Handshake mit " + ip + " herstellen. "
                    + "Siehe Ausgaben oben fuer die konkreten Fehler je Protokoll.");
            return;
        }

        redactSensitiveFields(deviceInfo);
        System.out.println("--- get_device_info Antwort (Konto 1, redigiert) ---");
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(deviceInfo));
    }

    private JsonNode tryProtocol(
            TapoDeviceFactory factory,
            TapoAuthProtocol protocol,
            String ip,
            String email,
            String password,
            String label
    ) {
        try {
            TapoLocalDeviceConnection connection = factory.create(protocol, ip, email, password);
            JsonNode info = connection.getDeviceInfo();
            System.out.println("Handshake erfolgreich mit Protokoll: " + label);
            return info;
        } catch (TapoException ex) {
            System.out.println("Protokoll " + label + " fehlgeschlagen: " + ex.getMessage());
            return null;
        }
    }

    private Properties loadApplicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IOException("application.properties nicht im Klassenpfad gefunden.");
            }
            properties.load(in);
        }
        return properties;
    }

    private String resolvePlaceholder(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(rawValue.trim());
        if (!matcher.matches()) {
            return rawValue;
        }
        String envVarName = matcher.group(1);
        String defaultValue = matcher.group(3) != null ? matcher.group(3) : "";
        String envValue = System.getenv(envVarName);
        return envValue != null ? envValue : defaultValue;
    }

    private void redactSensitiveFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (SENSITIVE_FIELD_NAMES.contains(fieldName.toLowerCase())) {
                    objectNode.put(fieldName, "<entfernt>");
                } else {
                    redactSensitiveFields(objectNode.get(fieldName));
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                redactSensitiveFields(child);
            }
        }
    }
}
