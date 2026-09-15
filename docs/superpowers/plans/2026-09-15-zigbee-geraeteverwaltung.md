# Zigbee-Geräteverwaltung — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Zigbee-Seite zeigt je Gerät einen belastbaren Gesundheitsstatus (z2m-Urteil + eigene Stille-Uhr) samt Entitäten und erlaubt Anlernen, Umbenennen, Neu-Interview, Neu-Konfigurieren und Entfernen — alles über die zigbee2mqtt-Bridge-API, nichts mehr über die z2m-Oberfläche.

**Architecture:** Der bestehende HiveMQ-Client (`ZigbeeMqttConfig`) liest zusätzlich die retained Bridge-Topics (`bridge/devices`, `bridge/info`, `bridge/event`, `bridge/response/*`) in ein In-Memory-Registry und publiziert erstmals Requests (`bridge/request/*`) mit Transaktions-ID und 10-s-Timeout. `ZigbeeDeviceHealthResolver` ist die einzige Definition von „lebt das Gerät". Schreibende Endpunkte sind ADMIN über methodenspezifische Matcher; die Angular-Seite `pages/zigbee/` wird umgebaut (Bridge-Kopf, Ereignisliste, Gerätekarten mit Badge und Entitäten, vier Dialoge).

**Tech Stack:** Spring Boot 3.4 / Java 21 / HiveMQ MQTT 3 / Jackson / JUnit 5 + AssertJ + Mockito; Angular 19 standalone / RxJS / Karma-Jasmine. Kein Liquibase-Changeset.

**Spec:** `docs/superpowers/specs/2026-09-15-zigbee-geraeteverwaltung-design.md`

**Abweichung von der Spec (im Plan festgelegt):** „Aus Household Manager entfernen" adressiert über die **DB-Id** (`DELETE /v1/zigbee/devices/local/{id}`), nicht über die IEEE-Adresse — Bestandszeilen in `zigbee_device` haben keine `ieee_address` (der Reading-Pfad kennt sie nicht), und genau die Zeilen, die man aufräumen will, stehen nicht mehr im Registry. Die Flow-Referenzsuche nimmt deshalb den Friendly Name als Query-Parameter (`GET /v1/zigbee/flow-references?friendlyName=`). Spec wird in Task 16 nachgezogen.

---

## Vorbereitung

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager
git checkout -b feature/zigbee-geraeteverwaltung
```

Backend-Tests laufen aus `backend/` mit `mvn -q test -Dtest=<Klasse>`. `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` sind auf dieser Maschine rot (keine Test-DB) — ignorieren. Frontend-Tests aus `frontend/` mit `npm test -- --watch=false --browsers=ChromeHeadless`; Baseline sind 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent`).

## Dateistruktur

**Backend (`backend/src/main/java/com/household/manager/zigbee/`)**

| Datei | Verantwortung |
|---|---|
| `model/ZigbeeBridgeDevice.java` (neu) | Ein Gerät aus `bridge/devices` |
| `model/ZigbeeBridgeInfo.java` (neu) | Auszug aus `bridge/info` |
| `model/ZigbeeBridgeEvent.java` (neu) | Ein `bridge/event` |
| `model/ZigbeeBridgeResponse.java` (neu) | Antwort auf einen eigenen Request |
| `model/DeviceHealth.java`, `model/DeviceHealthStatus.java` (neu) | Urteil je Gerät |
| `parser/ZigbeeBridgeMessageParser.java` (neu) | Parst die vier Bridge-Topics; wirft nie |
| `service/ZigbeeDeviceRegistry.java` (neu) | In-Memory-Verzeichnis, Info, Ereignis-Ringpuffer |
| `service/ZigbeeBridgeCommands.java` (neu) | Interface: publish + isConnected |
| `service/ZigbeeBridgeRequestService.java` (neu) | Request/Response mit Transaktion + Timeout |
| `service/ZigbeeDeviceHealthResolver.java` (neu) | Einzige Definition „lebt das Gerät" |
| `config/ZigbeeDeviceHealthProperties.java` (neu) | Schwellen 25 h / 15 min |
| `service/ZigbeeDeviceQueryService.java` (neu) | Baut die Geräteliste (Registry + DB + Entitäten + Health) |
| `service/ZigbeeDeviceManagementService.java` (neu) | permit-join, rename, interview, configure, remove + Audit |
| `service/ZigbeeDevicePurgeService.java` (neu) | „Aus HM entfernen" |
| `service/ZigbeeFlowReferenceService.java` (neu) | Flows, die Entitäten eines Geräts verwenden |
| `ZigbeeBridgeUnavailableException.java`, `ZigbeeBridgeRejectedException.java`, `ZigbeeDeviceKnownToBridgeException.java` (neu, Paket `zigbee`) | 502 / 400 / 409 |
| `dto/*` (neu/geändert) | siehe Task 8 |
| `controller/ZigbeeController.java` (geändert), `controller/ZigbeeManagementController.java` (neu) | Lesen / Schreiben |
| `config/ZigbeeMqttConfig.java` (geändert) | Bridge-Topics verarbeiten, publish |
| `service/ZigbeeStreamMonitor.java` (geändert) | `lastMessageAt(friendlyName)`, `availability(friendlyName)` |
| `service/ZigbeeLiveService.java` (geändert) | zwei weitere SSE-Ereignisse |
| `exception/GlobalExceptionHandler.java`, `security/SecurityConfig.java`, `repository/ZigbeeMeasurementRepository.java`, `application.properties` (geändert) | |

**Frontend (`frontend/src/app/`)**

| Datei | Verantwortung |
|---|---|
| `models/zigbee.model.ts` (geändert) | neue Typen |
| `services/zigbee.service.ts` (geändert) | neue Endpunkte |
| `services/zigbee-live.service.ts` (geändert) | drei benannte SSE-Ereignisse über eine Verbindung |
| `shared/zigbee-device-view.util.ts` + `.spec.ts` (neu) | Sortierung, Badge, Texte, Countdown — reine Funktionen |
| `pages/zigbee/zigbee.component.{ts,html,scss,spec.ts}` (umgebaut) | Seite |

---

### Task 1: Modelle und Bridge-Parser

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/model/ZigbeeBridgeDevice.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/model/ZigbeeBridgeInfo.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/model/ZigbeeBridgeEvent.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/model/ZigbeeBridgeResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/parser/ZigbeeBridgeMessageParser.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/parser/ZigbeeBridgeMessageParserTest.java`

- [ ] **Step 1: Modelle anlegen**

```java
// model/ZigbeeBridgeDevice.java
package com.household.manager.zigbee.model;

import java.util.Locale;

/**
 * Ein Geraet aus {@code zigbee2mqtt/bridge/devices} — zigbee2mqtt's eigenes Verzeichnis,
 * die Wahrheit darueber, welche Geraete gepaart sind (unsere Tabelle zigbee_device kennt
 * nur, was je gesendet hat).
 */
public record ZigbeeBridgeDevice(
        String ieeeAddress,
        String friendlyName,
        String type,
        String powerSource,
        boolean interviewCompleted,
        boolean supported,
        String model,
        String vendor,
        String description,
        Integer networkAddress) {

    /** Batteriegeraete sind schlafende Endgeraete: andere Stille-Schwelle, kein Ping. */
    public boolean battery() {
        return powerSource != null && powerSource.toLowerCase(Locale.ROOT).contains("battery");
    }
}
```

```java
// model/ZigbeeBridgeInfo.java
package com.household.manager.zigbee.model;

import java.time.Instant;

/**
 * Auszug aus {@code zigbee2mqtt/bridge/info}.
 *
 * @param permitJoinEnd Ende des Anlernfensters, null wenn geschlossen (z2m 2.x liefert
 *                      das Feld nur bei offenem Fenster)
 * @param availabilityCheckEnabled ob zigbee2mqtt's Verfuegbarkeitspruefung aktiv ist —
 *                                 ohne sie kommen nie {@code <name>/availability}-Meldungen
 */
public record ZigbeeBridgeInfo(
        String version,
        boolean permitJoin,
        Instant permitJoinEnd,
        boolean availabilityCheckEnabled) {
}
```

```java
// model/ZigbeeBridgeEvent.java
package com.household.manager.zigbee.model;

import java.time.Instant;

/**
 * Ein Ereignis aus {@code zigbee2mqtt/bridge/event}: device_joined, device_interview
 * (status started/successful/failed), device_announce, device_leave.
 *
 * @param receivedAt vom Registry gesetzt, im Parser null
 */
public record ZigbeeBridgeEvent(
        String type,
        String friendlyName,
        String ieeeAddress,
        String status,
        String model,
        String vendor,
        Instant receivedAt) {

    public ZigbeeBridgeEvent at(Instant instant) {
        return new ZigbeeBridgeEvent(type, friendlyName, ieeeAddress, status, model, vendor, instant);
    }
}
```

```java
// model/ZigbeeBridgeResponse.java
package com.household.manager.zigbee.model;

/**
 * Antwort auf einen eigenen Request, aus {@code zigbee2mqtt/bridge/response/<request>}.
 *
 * @param request     Teil nach {@code bridge/response/}, z. B. {@code device/rename}
 * @param transaction von uns gesetzte Transaktions-ID, von z2m gespiegelt; kann fehlen
 * @param ok          {@code status == "ok"}
 * @param error       z2m's Fehlertext bei {@code status == "error"}, sonst null
 */
public record ZigbeeBridgeResponse(String request, String transaction, boolean ok, String error) {
}
```

- [ ] **Step 2: Fehlschlagenden Parser-Test schreiben**

```java
package com.household.manager.zigbee.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeBridgeMessageParserTest {

    private final ZigbeeBridgeMessageParser parser = new ZigbeeBridgeMessageParser(new ObjectMapper());

    private static final String DEVICES = """
            [
              {"ieee_address":"0x00124b0021aabbcc","type":"Coordinator","network_address":0,
               "supported":true,"friendly_name":"Coordinator","definition":null,
               "power_source":"DC Source","interviewing":false,"interview_completed":true},
              {"ieee_address":"0x00158d0005a1b2c3","type":"EndDevice","network_address":12345,
               "supported":true,"friendly_name":"Motion Büro","disabled":false,
               "definition":{"model":"SNZB-03","vendor":"SONOFF","description":"Motion sensor","exposes":[]},
               "power_source":"Battery","interviewing":false,"interview_completed":true,
               "unknown_future_field":{"x":1}},
              {"ieee_address":"0x00158d0005ffffff","type":"Router","network_address":777,
               "supported":true,"friendly_name":"Steckdose Flur",
               "definition":{"model":"ZBMINI","vendor":"SONOFF","description":"Switch"},
               "power_source":"Mains (single phase)","interview_state":"IN_PROGRESS"}
            ]
            """;

    @Test
    void liestGeraeteOhneCoordinator() {
        Optional<List<ZigbeeBridgeDevice>> result = parser.parseDevices("zigbee2mqtt/bridge/devices", DEVICES);

        assertThat(result).isPresent();
        assertThat(result.get()).extracting(ZigbeeBridgeDevice::friendlyName)
                .containsExactly("Motion Büro", "Steckdose Flur");
        ZigbeeBridgeDevice motion = result.get().get(0);
        assertThat(motion.ieeeAddress()).isEqualTo("0x00158d0005a1b2c3");
        assertThat(motion.model()).isEqualTo("SNZB-03");
        assertThat(motion.vendor()).isEqualTo("SONOFF");
        assertThat(motion.battery()).isTrue();
        assertThat(motion.interviewCompleted()).isTrue();
        assertThat(motion.networkAddress()).isEqualTo(12345);
    }

    @Test
    void liestInterviewStateDerZweierVersion() {
        List<ZigbeeBridgeDevice> devices = parser.parseDevices("zigbee2mqtt/bridge/devices", DEVICES).orElseThrow();

        ZigbeeBridgeDevice router = devices.get(1);
        assertThat(router.interviewCompleted()).isFalse();
        assertThat(router.battery()).isFalse();
    }

    @Test
    void geraeteOhneDefinitionBleibenErhalten() {
        String payload = """
                [{"ieee_address":"0x1","type":"EndDevice","friendly_name":"0x1","supported":false,
                  "definition":null,"power_source":"Battery","interview_completed":false}]
                """;

        List<ZigbeeBridgeDevice> devices = parser.parseDevices("zigbee2mqtt/bridge/devices", payload).orElseThrow();

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).model()).isNull();
        assertThat(devices.get(0).supported()).isFalse();
    }

    @Test
    void anderesTopicOderKaputtesJsonErgibtLeer() {
        assertThat(parser.parseDevices("zigbee2mqtt/Motion Büro", DEVICES)).isEmpty();
        assertThat(parser.parseDevices("zigbee2mqtt/bridge/devices", "[{broken")).isEmpty();
        assertThat(parser.parseDevices("zigbee2mqtt/bridge/devices", "{\"not\":\"array\"}")).isEmpty();
    }

    @Test
    void liestInfoMitOffenemAnlernfensterUndAktiverPruefung() {
        String payload = """
                {"version":"2.1.3","permit_join":true,"permit_join_end":1757950000000,
                 "config":{"availability":{"enabled":true,"active":{"timeout":10},"passive":{"timeout":1500}}}}
                """;

        ZigbeeBridgeInfo info = parser.parseInfo("zigbee2mqtt/bridge/info", payload).orElseThrow();

        assertThat(info.version()).isEqualTo("2.1.3");
        assertThat(info.permitJoin()).isTrue();
        assertThat(info.permitJoinEnd()).isEqualTo(Instant.ofEpochMilli(1757950000000L));
        assertThat(info.availabilityCheckEnabled()).isTrue();
    }

    @Test
    void verfuegbarkeitspruefungAlsBooleanOderFehlend() {
        assertThat(parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"1.42.0\",\"permit_join\":false,\"config\":{\"availability\":true}}")
                .orElseThrow().availabilityCheckEnabled()).isTrue();
        assertThat(parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"1.42.0\",\"permit_join\":false,\"config\":{\"availability\":false}}")
                .orElseThrow().availabilityCheckEnabled()).isFalse();
        assertThat(parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"2.1.3\",\"permit_join\":false,\"config\":{\"availability\":{\"enabled\":false}}}")
                .orElseThrow().availabilityCheckEnabled()).isFalse();
        ZigbeeBridgeInfo ohne = parser.parseInfo("zigbee2mqtt/bridge/info",
                "{\"version\":\"1.42.0\",\"permit_join\":false,\"config\":{}}").orElseThrow();
        assertThat(ohne.availabilityCheckEnabled()).isFalse();
        assertThat(ohne.permitJoinEnd()).isNull();
    }

    @Test
    void liestAlleVierEreignistypen() {
        ZigbeeBridgeEvent joined = parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_joined\",\"data\":{\"friendly_name\":\"0x00158d00aaaaaaaa\",\"ieee_address\":\"0x00158d00aaaaaaaa\"}}")
                .orElseThrow();
        assertThat(joined.type()).isEqualTo("device_joined");
        assertThat(joined.ieeeAddress()).isEqualTo("0x00158d00aaaaaaaa");
        assertThat(joined.receivedAt()).isNull();

        ZigbeeBridgeEvent interview = parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_interview\",\"data\":{\"friendly_name\":\"0x00158d00aaaaaaaa\",\"ieee_address\":\"0x00158d00aaaaaaaa\",\"status\":\"successful\",\"supported\":true,\"definition\":{\"model\":\"SNZB-02\",\"vendor\":\"SONOFF\"}}}")
                .orElseThrow();
        assertThat(interview.status()).isEqualTo("successful");
        assertThat(interview.model()).isEqualTo("SNZB-02");
        assertThat(interview.vendor()).isEqualTo("SONOFF");

        assertThat(parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_announce\",\"data\":{\"friendly_name\":\"Motion Büro\",\"ieee_address\":\"0x1\"}}"))
                .isPresent();
        assertThat(parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"device_leave\",\"data\":{\"friendly_name\":\"Motion Büro\",\"ieee_address\":\"0x1\"}}"))
                .isPresent();
    }

    @Test
    void unbekannterEreignistypWirdIgnoriert() {
        assertThat(parser.parseEvent("zigbee2mqtt/bridge/event",
                "{\"type\":\"something_new\",\"data\":{}}")).isEmpty();
        assertThat(parser.parseEvent("zigbee2mqtt/bridge/state",
                "{\"type\":\"device_joined\",\"data\":{}}")).isEmpty();
    }

    @Test
    void liestAntwortenMitTransaktion() {
        ZigbeeBridgeResponse ok = parser.parseResponse("zigbee2mqtt/bridge/response/permit_join",
                "{\"data\":{\"time\":240},\"status\":\"ok\",\"transaction\":\"abc-1\"}").orElseThrow();
        assertThat(ok.request()).isEqualTo("permit_join");
        assertThat(ok.transaction()).isEqualTo("abc-1");
        assertThat(ok.ok()).isTrue();
        assertThat(ok.error()).isNull();

        ZigbeeBridgeResponse error = parser.parseResponse("zigbee2mqtt/bridge/response/device/remove",
                "{\"data\":{},\"status\":\"error\",\"error\":\"Device 'x' does not exist\",\"transaction\":\"abc-2\"}").orElseThrow();
        assertThat(error.request()).isEqualTo("device/remove");
        assertThat(error.ok()).isFalse();
        assertThat(error.error()).isEqualTo("Device 'x' does not exist");
    }

    @Test
    void antwortOhneTransaktionHatNullTransaktion() {
        ZigbeeBridgeResponse response = parser.parseResponse("zigbee2mqtt/bridge/response/health_check",
                "{\"data\":{\"healthy\":true},\"status\":\"ok\"}").orElseThrow();
        assertThat(response.transaction()).isNull();
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn -q test -Dtest=ZigbeeBridgeMessageParserTest`
Expected: Compile-Fehler `ZigbeeBridgeMessageParser` nicht gefunden.

- [ ] **Step 4: Parser implementieren**

```java
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
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `cd backend && mvn -q test -Dtest=ZigbeeBridgeMessageParserTest`
Expected: `Tests run: 9, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/model backend/src/main/java/com/household/manager/zigbee/parser backend/src/test/java/com/household/manager/zigbee/parser
git commit -m "feat(zigbee): Bridge-Topics parsen (devices, info, event, response)"
```

---

### Task 2: `ZigbeeDeviceRegistry` und Erweiterung des `ZigbeeStreamMonitor`

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeDeviceRegistry.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeStreamMonitor.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeDeviceRegistryTest.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeStreamMonitorTest.java` (ergänzen)

- [ ] **Step 1: Fehlschlagenden Registry-Test schreiben**

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeDeviceRegistryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private final ZigbeeDeviceRegistry registry =
            new ZigbeeDeviceRegistry(Clock.fixed(NOW, ZoneOffset.UTC));

    private static ZigbeeBridgeDevice device(String ieee, String name) {
        return new ZigbeeBridgeDevice(ieee, name, "EndDevice", "Battery", true, true,
                "SNZB-03", "SONOFF", "Motion sensor", 1);
    }

    @Test
    void istVorDemErstenVerzeichnisNichtGeladen() {
        assertThat(registry.loaded()).isFalse();
        assertThat(registry.devices()).isEmpty();
        assertThat(registry.info()).isEmpty();
    }

    @Test
    void ersetztDasVerzeichnisKomplett() {
        registry.replaceDevices(List.of(device("0x1", "A"), device("0x2", "B")));
        registry.replaceDevices(List.of(device("0x2", "B umbenannt")));

        assertThat(registry.loaded()).isTrue();
        assertThat(registry.devices()).extracting(ZigbeeBridgeDevice::friendlyName)
                .containsExactly("B umbenannt");
        assertThat(registry.findByIeee("0x1")).isEmpty();
        assertThat(registry.findByIeee("0x2")).map(ZigbeeBridgeDevice::friendlyName).contains("B umbenannt");
        assertThat(registry.findByFriendlyName("B umbenannt")).isPresent();
        assertThat(registry.findByFriendlyName("B")).isEmpty();
    }

    @Test
    void stempeltEreignisseUndBehaeltNurDieLetzten50NeuesteZuerst() {
        IntStream.rangeClosed(1, 55).forEach(i -> registry.recordEvent(
                new ZigbeeBridgeEvent("device_joined", "0x" + i, "0x" + i, null, null, null, null)));

        List<ZigbeeBridgeEvent> events = registry.events();
        assertThat(events).hasSize(50);
        assertThat(events.get(0).friendlyName()).isEqualTo("0x55");
        assertThat(events.get(49).friendlyName()).isEqualTo("0x6");
        assertThat(events.get(0).receivedAt()).isEqualTo(NOW);
    }

    @Test
    void haeltDieBridgeInfo() {
        registry.updateInfo(new ZigbeeBridgeInfo("2.1.3", true, NOW.plusSeconds(240), true));

        assertThat(registry.info()).isPresent();
        assertThat(registry.info().get().permitJoin()).isTrue();
    }
}
```

- [ ] **Step 2: Test laufen lassen — Compile-Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDeviceRegistryTest`

- [ ] **Step 3: Registry implementieren**

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * zigbee2mqtt's Geraeteverzeichnis, Bridge-Info und die letzten Bridge-Ereignisse —
 * rein im Speicher (Muster {@link ZigbeeStreamMonitor}). Nach einem Neustart leer,
 * bis der Broker die retained Topics {@code bridge/devices} und {@code bridge/info}
 * nachspielt (Sekunden); {@link #loaded()} macht dieses Fenster fuer die Seite sichtbar.
 * <p>
 * Das Verzeichnis wird bei jeder Nachricht als Ganzes ersetzt (unveraenderlicher
 * Snapshot hinter einem volatile-Feld) — z2m publiziert immer die komplette Liste.
 */
@Component
public class ZigbeeDeviceRegistry {

    static final int MAX_EVENTS = 50;

    private final Clock clock;

    private volatile Map<String, ZigbeeBridgeDevice> byIeee = Map.of();
    private volatile boolean loaded;
    private volatile ZigbeeBridgeInfo info;
    private final Deque<ZigbeeBridgeEvent> events = new ArrayDeque<>();

    @Autowired
    public ZigbeeDeviceRegistry() {
        this(Clock.systemUTC());
    }

    ZigbeeDeviceRegistry(Clock clock) {
        this.clock = clock;
    }

    public void replaceDevices(List<ZigbeeBridgeDevice> devices) {
        Map<String, ZigbeeBridgeDevice> next = new LinkedHashMap<>();
        for (ZigbeeBridgeDevice device : devices) {
            next.put(device.ieeeAddress(), device);
        }
        byIeee = Collections.unmodifiableMap(next);
        loaded = true;
    }

    public void updateInfo(ZigbeeBridgeInfo bridgeInfo) {
        info = bridgeInfo;
    }

    public void recordEvent(ZigbeeBridgeEvent event) {
        ZigbeeBridgeEvent stamped = event.at(clock.instant());
        synchronized (events) {
            events.addFirst(stamped);
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
    }

    public boolean loaded() {
        return loaded;
    }

    public List<ZigbeeBridgeDevice> devices() {
        return List.copyOf(byIeee.values());
    }

    public Optional<ZigbeeBridgeDevice> findByIeee(String ieeeAddress) {
        return Optional.ofNullable(byIeee.get(ieeeAddress));
    }

    public Optional<ZigbeeBridgeDevice> findByFriendlyName(String friendlyName) {
        return byIeee.values().stream()
                .filter(device -> device.friendlyName().equals(friendlyName))
                .findFirst();
    }

    public Optional<ZigbeeBridgeInfo> info() {
        return Optional.ofNullable(info);
    }

    /** Neueste zuerst. */
    public List<ZigbeeBridgeEvent> events() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }
}
```

- [ ] **Step 4: Registry-Test laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDeviceRegistryTest`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: Monitor-Test um Geräte-Uhr und Verfügbarkeitsabfrage ergänzen**

In `ZigbeeStreamMonitorTest` anhängen:

```java
    @Test
    void merktSichDieLetzteNachrichtJeGeraet() {
        monitor.recordMessage("Motion Büro");
        clock.advance(Duration.ofMinutes(3));
        monitor.recordMessage("Temperatur Keller");

        assertThat(monitor.lastMessageAt("Motion Büro")).contains(START);
        assertThat(monitor.lastMessageAt("Temperatur Keller")).contains(START.plus(Duration.ofMinutes(3)));
        assertThat(monitor.lastMessageAt("Unbekannt")).isEmpty();
    }

    @Test
    void liefertDieVerfuegbarkeitJeGeraet() {
        monitor.recordAvailability("Motion Büro", false);

        assertThat(monitor.availability("Motion Büro")).contains(false);
        assertThat(monitor.availability("Nie gemeldet")).isEmpty();
    }
```

- [ ] **Step 6: Monitor erweitern**

In `ZigbeeStreamMonitor` nach `deviceAvailability` ergänzen:

```java
    /** friendlyName -> Zeitpunkt der letzten nicht-retained Geraetenachricht. */
    private final Map<String, Instant> lastMessageByDevice = new ConcurrentHashMap<>();
```

`recordMessage` erweitern:

```java
    public void recordMessage(String friendlyName) {
        Instant now = clock.instant();
        lastMessageAt = now;
        if (friendlyName != null && !friendlyName.isBlank()) {
            deviceAvailability.put(friendlyName, Boolean.TRUE);
            lastMessageByDevice.put(friendlyName, now);
        }
    }
```

Nach `lastMessageAt()` ergänzen:

```java
    /**
     * Letzte nicht-retained Nachricht dieses Geraets seit dem Start. Retained
     * Nachrichten zaehlen wie beim globalen Watchdog nicht — sonst saehe nach jedem
     * Reconnect jedes Geraet frisch aus.
     */
    public Optional<Instant> lastMessageAt(String friendlyName) {
        return Optional.ofNullable(lastMessageByDevice.get(friendlyName));
    }

    /** zigbee2mqtt's Verfuegbarkeitsurteil, leer solange nie eines kam. */
    public Optional<Boolean> availability(String friendlyName) {
        return Optional.ofNullable(deviceAvailability.get(friendlyName));
    }
```

Import `java.util.Optional` ergänzen.

**Achtung:** `recordMessage` setzt heute `deviceAvailability.put(name, TRUE)` — das bleibt, ist aber kein z2m-Urteil. Damit der Resolver nur ein **explizites** `offline` als Regel 1 wertet, muss `availability()` den Wert aus einer eigenen Map liefern. Deshalb stattdessen:

```java
    /** friendlyName -> explizites Urteil aus <name>/availability. */
    private final Map<String, Boolean> explicitAvailability = new ConcurrentHashMap<>();
```

`recordAvailability` schreibt zusätzlich `explicitAvailability.put(friendlyName, online)`; `availability()` liest aus `explicitAvailability`. `deviceAvailability` (fürs Health-`offlineDevices`) bleibt unverändert.

- [ ] **Step 7: Monitor-Test laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest=ZigbeeStreamMonitorTest`
Expected: alle Tests grün (bestehende + 2 neue)

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/service backend/src/test/java/com/household/manager/zigbee/service
git commit -m "feat(zigbee): In-Memory-Geraeteverzeichnis und Geraete-Uhr im StreamMonitor"
```

---

### Task 3: `ZigbeeDeviceHealthResolver` mit Properties

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/model/DeviceHealthStatus.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/model/DeviceHealth.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeDeviceHealthProperties.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeDeviceHealthResolver.java`
- Modify: `backend/src/main/resources/application.properties` (nach Zeile 135)
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeDeviceHealthResolverTest.java`

- [ ] **Step 1: Modelle und Properties anlegen**

```java
// model/DeviceHealthStatus.java
package com.household.manager.zigbee.model;

/** Urteil ueber ein einzelnes Zigbee-Geraet, Reihenfolge = Prioritaet im Resolver. */
public enum DeviceHealthStatus {
    /** zigbee2mqtt meldet das Geraet explizit als offline. */
    OFFLINE,
    /** Interview nicht abgeschlossen (frisch angelernt oder gescheitert). */
    INTERVIEW,
    /** Nie eine Nachricht gehoert — weder seit dem Start noch in der DB. */
    UNKNOWN,
    /** Laenger still als die Schwelle seiner Stromquelle. */
    SILENT,
    ACTIVE
}
```

```java
// model/DeviceHealth.java
package com.household.manager.zigbee.model;

import java.time.Duration;
import java.time.Instant;

/**
 * @param basis       worauf das Urteil beruht: {@code zigbee2mqtt}, {@code registry},
 *                    {@code last-message} oder null bei UNKNOWN
 * @param lastHeardAt letzte Geraetenachricht (Speicher oder DB), null wenn nie
 * @param silentFor   Zeit seit lastHeardAt, null wenn nie
 */
public record DeviceHealth(DeviceHealthStatus status, String basis, Instant lastHeardAt, Duration silentFor) {
}
```

```java
// config/ZigbeeDeviceHealthProperties.java
package com.household.manager.zigbee.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Stille-Schwellen je Stromquelle fuer das Urteil je Geraet.
 * <p>
 * 25 h ist zigbee2mqtt's eigener Default fuer passive (Batterie-)Geraete, nicht am
 * eigenen Geraetepark verifiziert — nach einigen Tagen Betrieb nachziehen. Die
 * Netz-Schwelle entspricht der Watchdog-Schwelle {@code zigbee.watchdog.stale-after-minutes}
 * (der Default in application.properties referenziert dieselbe Env-Variable).
 */
@Component
@ConfigurationProperties(prefix = "zigbee.device-health")
@Getter
@Setter
public class ZigbeeDeviceHealthProperties {

    private int batterySilentAfterHours = 25;
    private int mainsSilentAfterMinutes = 15;

    public Duration batterySilentAfter() {
        return Duration.ofHours(batterySilentAfterHours);
    }

    public Duration mainsSilentAfter() {
        return Duration.ofMinutes(mainsSilentAfterMinutes);
    }
}
```

In `application.properties` nach Zeile 135 (`zigbee.watchdog.recover-grace-minutes=...`) ergänzen:

```properties
# Urteil je Zigbee-Geraet: Stille-Schwelle nach Stromquelle. 25 h = zigbee2mqtt-Default
# fuer passive Geraete (unverifiziert am eigenen Park); Netz-Geraete teilen die Watchdog-Schwelle.
zigbee.device-health.battery-silent-after-hours=${ZIGBEE_DEVICE_HEALTH_BATTERY_HOURS:25}
zigbee.device-health.mains-silent-after-minutes=${ZIGBEE_DEVICE_HEALTH_MAINS_MINUTES:${ZIGBEE_WATCHDOG_STALE_AFTER_MINUTES:15}}
# Antwortfrist fuer Requests an die zigbee2mqtt-Bridge-API (permit_join, rename, remove, ...)
zigbee.bridge.request-timeout-seconds=${ZIGBEE_BRIDGE_REQUEST_TIMEOUT_SECONDS:10}
```

- [ ] **Step 2: Fehlschlagenden Resolver-Test schreiben**

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeDeviceHealthProperties;
import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ZigbeeDeviceHealthResolverTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");
    private final ZigbeeDeviceHealthResolver resolver = new ZigbeeDeviceHealthResolver(
            new ZigbeeDeviceHealthProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    private static ZigbeeDeviceHealthResolver.Input input(Boolean availability, Boolean interviewCompleted,
                                                          boolean battery, Instant lastHeard) {
        return new ZigbeeDeviceHealthResolver.Input(availability, interviewCompleted, battery, lastHeard);
    }

    @Test
    void offlineVonZigbee2mqttSchlaegtAllesAndere() {
        DeviceHealth health = resolver.resolve(input(false, true, true, NOW.minusSeconds(10)));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.OFFLINE);
        assertThat(health.basis()).isEqualTo("zigbee2mqtt");
        assertThat(health.lastHeardAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @Test
    void onlineVonZigbee2mqttEntscheidetNichtAlterZaehltTrotzdem() {
        DeviceHealth health = resolver.resolve(input(true, true, true, NOW.minus(Duration.ofHours(30))));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void unfertigesInterviewVorStille() {
        DeviceHealth health = resolver.resolve(input(null, false, true, null));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.INTERVIEW);
        assertThat(health.basis()).isEqualTo("registry");
    }

    @Test
    void nieGehoertIstUnbekanntNichtAktiv() {
        DeviceHealth health = resolver.resolve(input(null, true, true, null));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.UNKNOWN);
        assertThat(health.basis()).isNull();
        assertThat(health.silentFor()).isNull();
    }

    @Test
    void batterieSchwelleIst25Stunden() {
        assertThat(resolver.resolve(input(null, true, true, NOW.minus(Duration.ofHours(24)))).status())
                .isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(resolver.resolve(input(null, true, true, NOW.minus(Duration.ofHours(25)))).status())
                .isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void netzSchwelleIst15Minuten() {
        assertThat(resolver.resolve(input(null, true, false, NOW.minus(Duration.ofMinutes(14)))).status())
                .isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(resolver.resolve(input(null, true, false, NOW.minus(Duration.ofMinutes(15)))).status())
                .isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void ohneRegistryEintragZaehltNurDieStilleUhr() {
        // Geraet nur in unserer Tabelle (knownToBridge=false): interviewCompleted null, battery true
        DeviceHealth health = resolver.resolve(input(null, null, true, NOW.minus(Duration.ofDays(20))));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.SILENT);
        assertThat(health.silentFor()).isEqualTo(Duration.ofDays(20));
        assertThat(health.basis()).isEqualTo("last-message");
    }

    @Test
    void zukuenftigerZeitstempelWirdAufNullGeklemmt() {
        DeviceHealth health = resolver.resolve(input(null, true, true, NOW.plusSeconds(120)));

        assertThat(health.status()).isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(health.silentFor()).isEqualTo(Duration.ZERO);
    }
}
```

- [ ] **Step 3: Test laufen lassen — Compile-Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDeviceHealthResolverTest`

- [ ] **Step 4: Resolver implementieren**

```java
package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeDeviceHealthProperties;
import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Die einzige Definition von "lebt dieses Zigbee-Geraet" (Muster TractiveHomeResolver):
 * Seite und ein kuenftiger Flow-Trigger fragen dieselbe Klasse.
 * <p>
 * Reihenfolge der Regeln ist Teil des Vertrags: ein explizites offline von zigbee2mqtt
 * schlaegt eine frische Nachricht (z2m weiss ueber Ping und Funkstille mehr als wir),
 * ein unfertiges Interview schlaegt die Stille-Uhr, und "nie gehoert" wird als UNKNOWN
 * ausgewiesen statt als ACTIVE geraten. Wirft nie.
 */
@Component
public class ZigbeeDeviceHealthResolver {

    /**
     * @param bridgeAvailability z2m's Urteil aus name/availability, null wenn nie eines kam
     *                           (auch wenn die Pruefung im Add-on aus ist)
     * @param interviewCompleted aus dem Registry; null wenn das Geraet dort nicht steht
     * @param battery            Stromquelle Batterie (Registry) — ohne Registry-Eintrag true,
     *                           die konservativere Schwelle
     * @param lastHeardAt        letzte Geraetenachricht, Maximum aus Speicher und DB; null wenn nie
     */
    public record Input(Boolean bridgeAvailability, Boolean interviewCompleted, boolean battery, Instant lastHeardAt) {
    }

    private final ZigbeeDeviceHealthProperties properties;
    private final Clock clock;

    public ZigbeeDeviceHealthResolver(ZigbeeDeviceHealthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public DeviceHealth resolve(Input input) {
        Instant lastHeard = input.lastHeardAt();
        Duration silentFor = null;
        if (lastHeard != null) {
            Duration raw = Duration.between(lastHeard, clock.instant());
            // Uhrenversatz zwischen DB-Zeit und Server: nie eine negative Stille ausweisen
            silentFor = raw.isNegative() ? Duration.ZERO : raw;
        }

        if (Boolean.FALSE.equals(input.bridgeAvailability())) {
            return new DeviceHealth(DeviceHealthStatus.OFFLINE, "zigbee2mqtt", lastHeard, silentFor);
        }
        if (Boolean.FALSE.equals(input.interviewCompleted())) {
            return new DeviceHealth(DeviceHealthStatus.INTERVIEW, "registry", lastHeard, silentFor);
        }
        if (lastHeard == null) {
            return new DeviceHealth(DeviceHealthStatus.UNKNOWN, null, null, null);
        }
        Duration threshold = input.battery() ? properties.batterySilentAfter() : properties.mainsSilentAfter();
        DeviceHealthStatus status = silentFor.compareTo(threshold) >= 0
                ? DeviceHealthStatus.SILENT : DeviceHealthStatus.ACTIVE;
        return new DeviceHealth(status, "last-message", lastHeard, silentFor);
    }
}
```

- [ ] **Step 5: Test laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDeviceHealthResolverTest`
Expected: `Tests run: 8, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee backend/src/main/resources/application.properties backend/src/test/java/com/household/manager/zigbee
git commit -m "feat(zigbee): ZigbeeDeviceHealthResolver - Urteil je Geraet mit Schwelle je Stromquelle"
```

---

### Task 4: Exceptions, `ZigbeeBridgeCommands` und `ZigbeeBridgeRequestService`

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/ZigbeeBridgeUnavailableException.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/ZigbeeBridgeRejectedException.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/ZigbeeDeviceKnownToBridgeException.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeBridgeCommands.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeBridgeRequestService.java`
- Modify: `backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeBridgeRequestServiceTest.java`

- [ ] **Step 1: Exceptions und Interface anlegen**

```java
// zigbee/ZigbeeBridgeUnavailableException.java
package com.household.manager.zigbee;

/** zigbee2mqtt nicht erreichbar: keine MQTT-Verbindung, Publish gescheitert oder Timeout. Wird zu 502. */
public class ZigbeeBridgeUnavailableException extends RuntimeException {
    public ZigbeeBridgeUnavailableException(String message) {
        super(message);
    }
}
```

```java
// zigbee/ZigbeeBridgeRejectedException.java
package com.household.manager.zigbee;

/** zigbee2mqtt hat den Request mit status error beantwortet; Nachricht = z2m's Fehlertext. Wird zu 400. */
public class ZigbeeBridgeRejectedException extends RuntimeException {
    public ZigbeeBridgeRejectedException(String message) {
        super(message);
    }
}
```

```java
// zigbee/ZigbeeDeviceKnownToBridgeException.java
package com.household.manager.zigbee;

/** "Aus Household Manager entfernen" fuer ein Geraet, das zigbee2mqtt noch kennt. Wird zu 409. */
public class ZigbeeDeviceKnownToBridgeException extends RuntimeException {
    public ZigbeeDeviceKnownToBridgeException(String message) {
        super(message);
    }
}
```

```java
// service/ZigbeeBridgeCommands.java
package com.household.manager.zigbee.service;

import java.util.concurrent.CompletableFuture;

/**
 * Erlaubt dem Request-Service, auf dem MQTT-Client zu publizieren, ohne ihn zu kennen
 * (Muster {@link ZigbeeConnectionControl}).
 */
public interface ZigbeeBridgeCommands {

    boolean isConnected();

    /** Publiziert mit QoS 1. Das Future schlaegt fehl, wenn der Broker nicht bestaetigt. */
    CompletableFuture<Void> publish(String topic, String payload);
}
```

- [ ] **Step 2: Handler in `GlobalExceptionHandler` ergänzen** (direkt nach `handleNukiException`, Muster dort)

```java
    @ExceptionHandler(ZigbeeBridgeUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleZigbeeBridgeUnavailable(
            ZigbeeBridgeUnavailableException ex, WebRequest request) {
        log.warn("zigbee2mqtt nicht erreichbar: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(zigbeeError(HttpStatus.BAD_GATEWAY, "Bad Gateway", ex.getMessage(), request));
    }

    @ExceptionHandler(ZigbeeBridgeRejectedException.class)
    public ResponseEntity<ErrorResponse> handleZigbeeBridgeRejected(
            ZigbeeBridgeRejectedException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(zigbeeError(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request));
    }

    @ExceptionHandler(ZigbeeDeviceKnownToBridgeException.class)
    public ResponseEntity<ErrorResponse> handleZigbeeDeviceKnownToBridge(
            ZigbeeDeviceKnownToBridgeException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(zigbeeError(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request));
    }

    private ErrorResponse zigbeeError(HttpStatus status, String error, String message, WebRequest request) {
        return ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(error)
                .message(message)
                .path(request.getDescription(false).replace("uri=", ""))
                .build();
    }
```

Imports ergänzen: `com.household.manager.zigbee.ZigbeeBridgeUnavailableException`, `com.household.manager.zigbee.ZigbeeBridgeRejectedException`, `com.household.manager.zigbee.ZigbeeDeviceKnownToBridgeException`.

- [ ] **Step 3: Fehlschlagenden Test für den Request-Service schreiben**

```java
package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.ZigbeeBridgeRejectedException;
import com.household.manager.zigbee.ZigbeeBridgeUnavailableException;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZigbeeBridgeRequestServiceTest {

    /** Fake-Client, der jedes Publish protokolliert. */
    private static final class FakeCommands implements ZigbeeBridgeCommands {
        boolean connected = true;
        boolean failPublish = false;
        final List<String> topics = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        @Override public boolean isConnected() { return connected; }

        @Override public CompletableFuture<Void> publish(String topic, String payload) {
            topics.add(topic);
            payloads.add(payload);
            return failPublish
                    ? CompletableFuture.failedFuture(new RuntimeException("broker weg"))
                    : CompletableFuture.completedFuture(null);
        }
    }

    private final FakeCommands commands = new FakeCommands();
    private final ScheduledExecutorService replies = Executors.newSingleThreadScheduledExecutor();
    private ZigbeeBridgeRequestService service;

    @BeforeEach
    void setUp() {
        service = new ZigbeeBridgeRequestService(commands, new ObjectMapper(), Duration.ofMillis(300));
    }

    @AfterEach
    void tearDown() {
        replies.shutdownNow();
    }

    /** Liest die vom Service erzeugte Transaktions-ID aus dem publizierten JSON. */
    private String transactionOf(String payload) {
        String marker = "\"transaction\":\"";
        int start = payload.indexOf(marker) + marker.length();
        return payload.substring(start, payload.indexOf('"', start));
    }

    @Test
    void sendetRequestMitTransaktionUndLiefertDieAntwort() {
        replies.schedule(() -> service.onResponse(new ZigbeeBridgeResponse(
                "permit_join", transactionOf(commands.payloads.get(0)), true, null)), 50, TimeUnit.MILLISECONDS);

        ZigbeeBridgeResponse response = service.send("permit_join", Map.of("time", 240));

        assertThat(response.ok()).isTrue();
        assertThat(commands.topics).containsExactly("zigbee2mqtt/bridge/request/permit_join");
        assertThat(commands.payloads.get(0)).contains("\"time\":240").contains("\"transaction\":\"");
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    void fehlerantwortWirdZurAusnahmeMitZ2mText() {
        replies.schedule(() -> service.onResponse(new ZigbeeBridgeResponse(
                "device/remove", transactionOf(commands.payloads.get(0)), false, "Device 'x' does not exist")),
                50, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> service.send("device/remove", Map.of("id", "x")))
                .isInstanceOf(ZigbeeBridgeRejectedException.class)
                .hasMessage("Device 'x' does not exist");
    }

    @Test
    void timeoutRaeumtDieTransaktionAb() {
        assertThatThrownBy(() -> service.send("device/interview", Map.of("id", "x")))
                .isInstanceOf(ZigbeeBridgeUnavailableException.class)
                .hasMessageContaining("antwortet nicht");
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    void keinPublishOhneVerbindung() {
        commands.connected = false;

        assertThatThrownBy(() -> service.send("permit_join", Map.of("time", 240)))
                .isInstanceOf(ZigbeeBridgeUnavailableException.class);
        assertThat(commands.topics).isEmpty();
    }

    @Test
    void gescheitertesPublishIstNichtErreichbar() {
        commands.failPublish = true;

        assertThatThrownBy(() -> service.send("permit_join", Map.of("time", 240)))
                .isInstanceOf(ZigbeeBridgeUnavailableException.class);
        assertThat(service.pendingCount()).isZero();
    }

    @Test
    void fremdeAntwortenWerdenIgnoriert() {
        service.onResponse(new ZigbeeBridgeResponse("permit_join", "nie-gesendet", true, null));
        service.onResponse(new ZigbeeBridgeResponse("permit_join", null, true, null));

        assertThat(service.pendingCount()).isZero();
    }
}
```

- [ ] **Step 4: Test laufen lassen — Compile-Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=ZigbeeBridgeRequestServiceTest`

- [ ] **Step 5: Request-Service implementieren**

```java
package com.household.manager.zigbee.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.zigbee.ZigbeeBridgeRejectedException;
import com.household.manager.zigbee.ZigbeeBridgeUnavailableException;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Request/Response gegen die zigbee2mqtt-Bridge-API: Request auf
 * {@code zigbee2mqtt/bridge/request/<request>} mit Transaktions-ID, Antwort kommt
 * asynchron auf {@code bridge/response/<request>} und traegt dieselbe ID.
 * <p>
 * Timeout ist Pflicht — ohne es hinge ein HTTP-Thread ewig, wenn z2m gerade weg ist.
 * Kein Request ohne bestehende Verbindung: HiveMQ wuerde das Publish sonst puffern und
 * Minuten spaeter nachholen, ein verspaetet geoeffnetes Anlernfenster waere eine
 * Ueberraschung.
 */
@Service
@Slf4j
public class ZigbeeBridgeRequestService {

    private static final String REQUEST_PREFIX = "zigbee2mqtt/bridge/request/";

    private final ZigbeeBridgeCommands commands;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final Map<String, CompletableFuture<ZigbeeBridgeResponse>> pending = new ConcurrentHashMap<>();

    @Autowired
    public ZigbeeBridgeRequestService(ZigbeeBridgeCommands commands, ObjectMapper objectMapper,
                                      @Value("${zigbee.bridge.request-timeout-seconds:10}") int timeoutSeconds) {
        this(commands, objectMapper, Duration.ofSeconds(timeoutSeconds));
    }

    ZigbeeBridgeRequestService(ZigbeeBridgeCommands commands, ObjectMapper objectMapper, Duration timeout) {
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    /**
     * @param request Pfad nach {@code bridge/request/}, z. B. {@code permit_join} oder {@code device/rename}
     * @param params  Felder des Requests; {@code transaction} wird ergaenzt
     * @throws ZigbeeBridgeUnavailableException nicht verbunden, Publish gescheitert oder Timeout
     * @throws ZigbeeBridgeRejectedException    z2m antwortet mit status error
     */
    public ZigbeeBridgeResponse send(String request, Map<String, Object> params) {
        if (!commands.isConnected()) {
            throw new ZigbeeBridgeUnavailableException(
                    "Keine Verbindung zum MQTT-Broker — zigbee2mqtt nicht erreichbar.");
        }
        String transaction = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>(params);
        payload.put("transaction", transaction);
        CompletableFuture<ZigbeeBridgeResponse> future = new CompletableFuture<>();
        pending.put(transaction, future);
        try {
            String json = objectMapper.writeValueAsString(payload);
            commands.publish(REQUEST_PREFIX + request, json).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ZigbeeBridgeResponse response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!response.ok()) {
                throw new ZigbeeBridgeRejectedException(
                        response.error() != null ? response.error() : "zigbee2mqtt hat den Request abgelehnt.");
            }
            return response;
        } catch (TimeoutException ex) {
            throw new ZigbeeBridgeUnavailableException("zigbee2mqtt antwortet nicht (" + request + ").");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ZigbeeBridgeUnavailableException("Warten auf zigbee2mqtt unterbrochen.");
        } catch (ZigbeeBridgeRejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ZigbeeBridgeUnavailableException("Request an zigbee2mqtt fehlgeschlagen: " + ex.getMessage());
        } finally {
            pending.remove(transaction);
        }
    }

    /** Vom MQTT-Handler fuer jede {@code bridge/response/*}-Nachricht aufzurufen. */
    public void onResponse(ZigbeeBridgeResponse response) {
        if (response.transaction() == null) {
            return;
        }
        CompletableFuture<ZigbeeBridgeResponse> future = pending.remove(response.transaction());
        if (future != null) {
            future.complete(response);
        }
    }

    int pendingCount() {
        return pending.size();
    }
}
```

- [ ] **Step 6: Test laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest=ZigbeeBridgeRequestServiceTest`
Expected: `Tests run: 6, Failures: 0`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee backend/src/main/java/com/household/manager/exception/GlobalExceptionHandler.java backend/src/test/java/com/household/manager/zigbee
git commit -m "feat(zigbee): Request/Response gegen die Bridge-API mit Transaktion und Timeout"
```

---

### Task 5: DTOs für Bridge-Status und -Ereignisse, `ZigbeeLiveService` erweitern

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeBridgeStatusResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeBridgeEventResponse.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeLiveService.java`

- [ ] **Step 1: DTOs anlegen**

```java
// dto/ZigbeeBridgeStatusResponse.java
package com.household.manager.zigbee.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** GET /v1/zigbee/bridge und SSE-Ereignis {@code bridge-info}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeBridgeStatusResponse {
    private String version;
    private boolean connected;
    private boolean registryLoaded;
    private boolean permitJoin;
    /** Ende des Anlernfensters, null wenn geschlossen. */
    private Instant permitJoinEnd;
    /** null, solange nie eine bridge/info kam — die Seite zeigt dann keinen Hinweis. */
    private Boolean availabilityCheckEnabled;
}
```

```java
// dto/ZigbeeBridgeEventResponse.java
package com.household.manager.zigbee.dto;

import com.household.manager.zigbee.model.ZigbeeBridgeEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Ein Bridge-Ereignis fuer GET /v1/zigbee/bridge/events und SSE {@code bridge-event}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeBridgeEventResponse {
    private String type;
    private String friendlyName;
    private String ieeeAddress;
    private String status;
    private String model;
    private String vendor;
    private Instant receivedAt;

    public static ZigbeeBridgeEventResponse from(ZigbeeBridgeEvent event) {
        return ZigbeeBridgeEventResponse.builder()
                .type(event.type())
                .friendlyName(event.friendlyName())
                .ieeeAddress(event.ieeeAddress())
                .status(event.status())
                .model(event.model())
                .vendor(event.vendor())
                .receivedAt(event.receivedAt())
                .build();
    }
}
```

- [ ] **Step 2: `ZigbeeLiveService` um zwei Ereignisse erweitern**

`broadcast` in eine private Methode mit Ereignisname umbauen:

```java
    public void broadcast(ZigbeeLiveResponse event) {
        send("live", event);
    }

    /** Anlernen live verfolgen: device_joined, device_interview, ... */
    public void broadcastBridgeEvent(ZigbeeBridgeEventResponse event) {
        send("bridge-event", event);
    }

    /** Bei jeder bridge/info: Anlernfenster auf/zu, Verfuegbarkeitspruefung an/aus. */
    public void broadcastBridgeInfo(ZigbeeBridgeStatusResponse status) {
        send("bridge-info", status);
    }

    private void send(String name, Object data) {
        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (Exception ex) {
                emitters.remove(emitter);
            }
        });
    }
```

Imports: `com.household.manager.zigbee.dto.ZigbeeBridgeEventResponse`, `com.household.manager.zigbee.dto.ZigbeeBridgeStatusResponse`.

- [ ] **Step 3: Kompilieren**

Run: `cd backend && mvn -q compile`
Expected: keine Fehler

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee
git commit -m "feat(zigbee): SSE-Ereignisse fuer Bridge-Events und Bridge-Info"
```

---

### Task 6: `ZigbeeMqttConfig` — Bridge-Topics verarbeiten und publizieren

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`

Es gibt keinen Unit-Test für diese Klasse (echter MQTT-Client). Absicherung: `mvn compile` + der manuelle Realtest in Task 17.

- [ ] **Step 1: Klasse um `ZigbeeBridgeCommands` erweitern**

Klassenkopf:

```java
public class ZigbeeMqttConfig implements ZigbeeConnectionControl, ZigbeeBridgeCommands {
```

Neue Felder (bei den anderen `private final`):

```java
    private final ZigbeeBridgeMessageParser bridgeParser;
    private final ZigbeeDeviceRegistry registry;
    private final ZigbeeBridgeRequestService requestService;
```

**Achtung Zirkel:** `ZigbeeBridgeRequestService` braucht `ZigbeeBridgeCommands` (= diese Klasse), diese Klasse braucht den Request-Service für `onResponse`. Auflösung: den Request-Service **nicht** im Konstruktor, sondern über `ObjectProvider` holen:

```java
    private final ObjectProvider<ZigbeeBridgeRequestService> requestServiceProvider;
```

und im Handler `requestServiceProvider.getObject().onResponse(...)`. (`org.springframework.beans.factory.ObjectProvider` importieren; das Feld `requestService` oben entfällt.)

- [ ] **Step 2: Interface-Methoden implementieren** (nach `forceReconnect()`)

```java
    @Override
    public boolean isConnected() {
        Mqtt3AsyncClient current = this.client;
        return current != null && current.getConfig().getState().isConnected();
    }

    @Override
    public CompletableFuture<Void> publish(String topic, String payload) {
        Mqtt3AsyncClient current = this.client;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("MQTT-Client nicht gestartet"));
        }
        return current.publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .send()
                .thenApply(ack -> null);
    }
```

Import `java.util.concurrent.CompletableFuture`.

- [ ] **Step 3: Bridge-Topics im Handler verarbeiten**

In `handle(...)` nach dem `bridgeState`-Block und vor dem `availability`-Block einfügen:

```java
            if (handleBridgeTopic(topic, payload)) {
                return;
            }
```

Neue Methode:

```java
    /**
     * Verzeichnis, Info, Ereignisse und Antworten der Bridge. Liefert true, wenn das
     * Topic eines davon war (auch wenn das Payload unlesbar war — dann bleibt der alte
     * Stand stehen, ein Format-Bruch darf das Registry nicht leeren).
     */
    private boolean handleBridgeTopic(String topic, String payload) {
        if (!topic.startsWith("zigbee2mqtt/bridge/")) {
            return false;
        }
        bridgeParser.parseDevices(topic, payload).ifPresent(devices -> {
            registry.replaceDevices(devices);
            log.info("zigbee2mqtt-Verzeichnis: {} Geraete", devices.size());
        });
        bridgeParser.parseInfo(topic, payload).ifPresent(info -> {
            registry.updateInfo(info);
            liveService.broadcastBridgeInfo(bridgeStatus());
        });
        bridgeParser.parseEvent(topic, payload).ifPresent(event -> {
            registry.recordEvent(event);
            registry.events().stream().findFirst()
                    .ifPresent(stamped -> liveService.broadcastBridgeEvent(ZigbeeBridgeEventResponse.from(stamped)));
            log.info("zigbee2mqtt-Ereignis {} fuer {}", event.type(), event.friendlyName());
        });
        bridgeParser.parseResponse(topic, payload)
                .ifPresent(response -> requestServiceProvider.getObject().onResponse(response));
        return true;
    }

    /** Baut den Bridge-Status fuer SSE; derselbe Aufbau wie GET /v1/zigbee/bridge (siehe ZigbeeDeviceQueryService). */
    private ZigbeeBridgeStatusResponse bridgeStatus() {
        var info = registry.info();
        return ZigbeeBridgeStatusResponse.builder()
                .version(info.map(ZigbeeBridgeInfo::version).orElse(null))
                .connected(isConnected())
                .registryLoaded(registry.loaded())
                .permitJoin(info.map(ZigbeeBridgeInfo::permitJoin).orElse(false))
                .permitJoinEnd(info.map(ZigbeeBridgeInfo::permitJoinEnd).orElse(null))
                .availabilityCheckEnabled(info.map(ZigbeeBridgeInfo::availabilityCheckEnabled).orElse(null))
                .build();
    }
```

Imports: `com.household.manager.zigbee.dto.ZigbeeBridgeEventResponse`, `com.household.manager.zigbee.dto.ZigbeeBridgeStatusResponse`, `com.household.manager.zigbee.model.ZigbeeBridgeInfo`, `com.household.manager.zigbee.parser.ZigbeeBridgeMessageParser`, `com.household.manager.zigbee.service.ZigbeeBridgeCommands`, `com.household.manager.zigbee.service.ZigbeeBridgeRequestService`, `com.household.manager.zigbee.service.ZigbeeDeviceRegistry`, `org.springframework.beans.factory.ObjectProvider`.

**Hinweis:** Der bestehende Parser-Aufruf `parser.parse(...)` ignoriert `bridge/*` schon (`isDeviceTopic`), und `parseBridgeState` steht vor dem neuen Block — `bridge/state` bleibt also dort. `bridgeStatus()` wird in Task 7 durch den Aufruf `queryService.bridgeStatus()` ersetzt, damit es genau eine Definition gibt; hier zunächst lokal, weil der Query-Service noch nicht existiert.

- [ ] **Step 4: Kompilieren und alle Zigbee-Tests laufen lassen**

Run: `cd backend && mvn -q test -Dtest='Zigbee*'`
Expected: alle grün

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "feat(zigbee): MQTT-Client liest Bridge-Topics und publiziert Requests"
```

---

### Task 7: `ZigbeeDeviceQueryService` und Geräte-DTOs

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeDeviceResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeDeviceHealthResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/ZigbeeDeviceEntityResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeDeviceQueryService.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java` (`bridgeStatus()` durch Query-Service ersetzen)
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeDeviceQueryServiceTest.java`

- [ ] **Step 1: DTOs**

`ZigbeeDeviceResponse` komplett ersetzen:

```java
package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ein Zigbee-Geraet fuer die Seite: Grundlage ist zigbee2mqtt's Verzeichnis, unsere
 * Tabelle liefert Batterie/LQ/lastSeen, der Entity-State-Layer die Entitaeten.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceResponse {
    /** DB-Id, null wenn das Geraet nur im Registry steht (noch nie gesendet). */
    private Long id;
    private String friendlyName;
    private String ieeeAddress;
    /** false = nur in unserer Tabelle, zigbee2mqtt kennt es nicht (mehr). */
    private boolean knownToBridge;
    private String type;
    private String powerSource;
    private boolean battery;
    private Boolean interviewCompleted;
    private Boolean supported;
    private String model;
    private String vendor;
    private String description;
    private Integer lastBatteryPercent;
    private Integer lastLinkQuality;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastSeen;

    private ZigbeeDeviceHealthResponse health;
    private List<ZigbeeDeviceEntityResponse> entities;
}
```

```java
// dto/ZigbeeDeviceHealthResponse.java
package com.household.manager.zigbee.dto;

import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceHealthResponse {
    private DeviceHealthStatus status;
    private String basis;
    private Instant lastHeardAt;
    /** Sekunden seit lastHeardAt, null bei UNKNOWN. */
    private Long silentSeconds;

    public static ZigbeeDeviceHealthResponse from(DeviceHealth health) {
        return ZigbeeDeviceHealthResponse.builder()
                .status(health.status())
                .basis(health.basis())
                .lastHeardAt(health.lastHeardAt())
                .silentSeconds(health.silentFor() != null ? health.silentFor().toSeconds() : null)
                .build();
    }
}
```

```java
// dto/ZigbeeDeviceEntityResponse.java
package com.household.manager.zigbee.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.entitystate.EntityDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Eine Entitaet des Geraets aus dem Entity-State-Layer, mit lastUpdated (nicht nur lastChanged). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZigbeeDeviceEntityResponse {
    private String entityId;
    private String displayName;
    private EntityDomain domain;
    private String state;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastChanged;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastUpdated;
}
```

- [ ] **Step 2: Fehlschlagenden Test schreiben**

```java
package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.config.ZigbeeDeviceHealthProperties;
import com.household.manager.zigbee.config.ZigbeeWatchdogProperties;
import com.household.manager.zigbee.dto.ZigbeeBridgeStatusResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceResponse;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeDeviceQueryServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant NOW = LocalDateTime.of(2026, 9, 15, 12, 0).atZone(BERLIN).toInstant();

    @Mock private ZigbeeDeviceRepository deviceRepository;
    @Mock private EntityStateService entityStateService;
    @Mock private EntityStateResponseMapper responseMapper;
    @Mock private ZigbeeBridgeCommands commands;

    private final Clock clock = Clock.fixed(NOW, BERLIN);
    private final ZigbeeDeviceRegistry registry = new ZigbeeDeviceRegistry(clock);
    private final ZigbeeStreamMonitor monitor = new ZigbeeStreamMonitor(new ZigbeeWatchdogProperties(), clock);
    private ZigbeeDeviceQueryService service;

    @BeforeEach
    void setUp() {
        service = new ZigbeeDeviceQueryService(registry, monitor, deviceRepository, entityStateService,
                responseMapper, new ZigbeeDeviceHealthResolver(new ZigbeeDeviceHealthProperties(), clock),
                commands, clock);
        when(responseMapper.displayName(any())).thenAnswer(inv -> ((EntityState) inv.getArgument(0)).getFriendlyName());
    }

    private static ZigbeeBridgeDevice bridgeDevice(String ieee, String name, String powerSource) {
        return new ZigbeeBridgeDevice(ieee, name, "EndDevice", powerSource, true, true, "SNZB-03", "SONOFF", "Motion", 1);
    }

    private static ZigbeeDevice dbDevice(long id, String name, LocalDateTime lastSeen) {
        return ZigbeeDevice.builder().id(id).friendlyName(name).lastBatteryPercent(80)
                .lastLinkQuality(120).lastSeen(lastSeen).build();
    }

    private static EntityState entity(String id, String sourceRef, String state, LocalDateTime updated) {
        return EntityState.builder().entityId(id).domain(EntityDomain.BINARY_SENSOR).source(EntitySource.ZIGBEE)
                .sourceRef(sourceRef).friendlyName(id).state(state).lastChanged(updated).lastUpdated(updated).build();
    }

    @Test
    void verbindetRegistryTabelleUndEntitaeten() {
        registry.replaceDevices(List.of(bridgeDevice("0x1", "Motion Büro", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of(
                dbDevice(7, "Motion Büro", LocalDateTime.of(2026, 8, 21, 16, 36))));
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of(
                entity("binary_sensor.zigbee_motion_buero_occupancy", "Motion Büro", "on", LocalDateTime.of(2026, 8, 21, 16, 36)),
                entity("binary_sensor.zigbee_anderes_contact", "Anderes", "off", LocalDateTime.of(2026, 9, 15, 11, 0))));

        List<ZigbeeDeviceResponse> devices = service.listDevices();

        assertThat(devices).hasSize(1);
        ZigbeeDeviceResponse motion = devices.get(0);
        assertThat(motion.getId()).isEqualTo(7);
        assertThat(motion.getIeeeAddress()).isEqualTo("0x1");
        assertThat(motion.isKnownToBridge()).isTrue();
        assertThat(motion.isBattery()).isTrue();
        assertThat(motion.getModel()).isEqualTo("SNZB-03");
        assertThat(motion.getLastBatteryPercent()).isEqualTo(80);
        assertThat(motion.getHealth().getStatus()).isEqualTo(DeviceHealthStatus.SILENT);
        assertThat(motion.getHealth().getSilentSeconds()).isGreaterThan(20L * 24 * 3600);
        assertThat(motion.getEntities()).extracting("entityId")
                .containsExactly("binary_sensor.zigbee_motion_buero_occupancy");
    }

    @Test
    void geraetNurInDerTabelleBleibtSichtbarAlsNichtBekannt() {
        registry.replaceDevices(List.of());
        when(deviceRepository.findAll()).thenReturn(List.of(
                dbDevice(3, "Alter Sensor", LocalDateTime.of(2026, 1, 1, 0, 0))));
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());

        List<ZigbeeDeviceResponse> devices = service.listDevices();

        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).isKnownToBridge()).isFalse();
        assertThat(devices.get(0).getId()).isEqualTo(3);
        assertThat(devices.get(0).getHealth().getStatus()).isEqualTo(DeviceHealthStatus.SILENT);
    }

    @Test
    void geraetNurImRegistryHatKeineIdUndIstUnbekannt() {
        registry.replaceDevices(List.of(bridgeDevice("0x9", "0x9", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());

        ZigbeeDeviceResponse fresh = service.listDevices().get(0);

        assertThat(fresh.getId()).isNull();
        assertThat(fresh.getHealth().getStatus()).isEqualTo(DeviceHealthStatus.UNKNOWN);
    }

    @Test
    void speicherUhrSchlaegtDbLastSeenWennJuenger() {
        registry.replaceDevices(List.of(bridgeDevice("0x1", "Temp Keller", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of(
                dbDevice(1, "Temp Keller", LocalDateTime.of(2026, 9, 14, 12, 0))));
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());
        monitor.recordMessage("Temp Keller");

        ZigbeeDeviceResponse device = service.listDevices().get(0);

        assertThat(device.getHealth().getStatus()).isEqualTo(DeviceHealthStatus.ACTIVE);
        assertThat(device.getHealth().getLastHeardAt()).isEqualTo(NOW);
    }

    @Test
    void z2mOfflineWirdDurchgereicht() {
        registry.replaceDevices(List.of(bridgeDevice("0x1", "Motion Flur", "Battery")));
        when(deviceRepository.findAll()).thenReturn(List.of());
        when(entityStateService.find(null, EntitySource.ZIGBEE)).thenReturn(List.of());
        monitor.recordMessage("Motion Flur");
        monitor.recordAvailability("Motion Flur", false);

        assertThat(service.listDevices().get(0).getHealth().getStatus()).isEqualTo(DeviceHealthStatus.OFFLINE);
    }

    @Test
    void bridgeStatusSpiegeltRegistryUndVerbindung() {
        when(commands.isConnected()).thenReturn(true);
        registry.updateInfo(new ZigbeeBridgeInfo("2.1.3", true, NOW.plusSeconds(200), false));
        registry.replaceDevices(List.of());

        ZigbeeBridgeStatusResponse status = service.bridgeStatus();

        assertThat(status.getVersion()).isEqualTo("2.1.3");
        assertThat(status.isConnected()).isTrue();
        assertThat(status.isRegistryLoaded()).isTrue();
        assertThat(status.isPermitJoin()).isTrue();
        assertThat(status.getPermitJoinEnd()).isEqualTo(NOW.plusSeconds(200));
        assertThat(status.getAvailabilityCheckEnabled()).isFalse();
    }

    @Test
    void bridgeStatusOhneInfoHatNullFuerDiePruefung() {
        assertThat(service.bridgeStatus().getAvailabilityCheckEnabled()).isNull();
        assertThat(service.bridgeStatus().isRegistryLoaded()).isFalse();
    }
}
```

- [ ] **Step 3: Test laufen lassen — Compile-Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDeviceQueryServiceTest`

- [ ] **Step 4: Query-Service implementieren**

```java
package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.zigbee.dto.ZigbeeBridgeEventResponse;
import com.household.manager.zigbee.dto.ZigbeeBridgeStatusResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceEntityResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceHealthResponse;
import com.household.manager.zigbee.dto.ZigbeeDeviceResponse;
import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Baut die Geraeteliste der Zigbee-Seite: Grundlage ist zigbee2mqtt's Verzeichnis,
 * dazu Batterie/LQ/lastSeen aus unserer Tabelle, die Entitaeten aus dem Entity-State-Layer
 * und das Urteil aus {@link ZigbeeDeviceHealthResolver}. Geraete, die nur in unserer
 * Tabelle stehen, bleiben als {@code knownToBridge=false} sichtbar statt zu verschwinden.
 */
@Service
@RequiredArgsConstructor
public class ZigbeeDeviceQueryService {

    private final ZigbeeDeviceRegistry registry;
    private final ZigbeeStreamMonitor streamMonitor;
    private final ZigbeeDeviceRepository deviceRepository;
    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;
    private final ZigbeeDeviceHealthResolver healthResolver;
    private final ZigbeeBridgeCommands commands;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<ZigbeeDeviceResponse> listDevices() {
        Map<String, ZigbeeDevice> dbByName = deviceRepository.findAll().stream()
                .collect(Collectors.toMap(ZigbeeDevice::getFriendlyName, Function.identity(), (a, b) -> a));
        Map<String, List<EntityState>> entitiesByRef = entityStateService.find(null, EntitySource.ZIGBEE).stream()
                .collect(Collectors.groupingBy(EntityState::getSourceRef));

        List<ZigbeeDeviceResponse> result = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (ZigbeeBridgeDevice bridgeDevice : registry.devices()) {
            ZigbeeDevice db = dbByName.get(bridgeDevice.friendlyName());
            covered.add(bridgeDevice.friendlyName());
            result.add(build(bridgeDevice, db, bridgeDevice.friendlyName(), entitiesByRef));
        }
        for (ZigbeeDevice db : dbByName.values()) {
            if (!covered.contains(db.getFriendlyName())) {
                result.add(build(null, db, db.getFriendlyName(), entitiesByRef));
            }
        }
        result.sort(Comparator.comparing(ZigbeeDeviceResponse::getFriendlyName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private ZigbeeDeviceResponse build(ZigbeeBridgeDevice bridge, ZigbeeDevice db, String friendlyName,
                                       Map<String, List<EntityState>> entitiesByRef) {
        Instant lastHeard = lastHeard(friendlyName, db);
        DeviceHealth health = healthResolver.resolve(new ZigbeeDeviceHealthResolver.Input(
                streamMonitor.availability(friendlyName).orElse(null),
                bridge != null ? bridge.interviewCompleted() : null,
                bridge == null || bridge.battery(),
                lastHeard));
        List<ZigbeeDeviceEntityResponse> entities = entitiesByRef.getOrDefault(friendlyName, List.of()).stream()
                .map(entity -> ZigbeeDeviceEntityResponse.builder()
                        .entityId(entity.getEntityId())
                        .displayName(responseMapper.displayName(entity))
                        .domain(entity.getDomain())
                        .state(entity.getState())
                        .lastChanged(entity.getLastChanged())
                        .lastUpdated(entity.getLastUpdated())
                        .build())
                .toList();
        return ZigbeeDeviceResponse.builder()
                .id(db != null ? db.getId() : null)
                .friendlyName(friendlyName)
                .ieeeAddress(bridge != null ? bridge.ieeeAddress() : (db != null ? db.getIeeeAddress() : null))
                .knownToBridge(bridge != null)
                .type(bridge != null ? bridge.type() : null)
                .powerSource(bridge != null ? bridge.powerSource() : null)
                .battery(bridge == null || bridge.battery())
                .interviewCompleted(bridge != null ? bridge.interviewCompleted() : null)
                .supported(bridge != null ? bridge.supported() : null)
                .model(bridge != null ? bridge.model() : (db != null ? db.getModel() : null))
                .vendor(bridge != null ? bridge.vendor() : null)
                .description(bridge != null ? bridge.description() : null)
                .lastBatteryPercent(db != null ? db.getLastBatteryPercent() : null)
                .lastLinkQuality(db != null ? db.getLastLinkQuality() : null)
                .lastSeen(db != null ? db.getLastSeen() : null)
                .health(ZigbeeDeviceHealthResponse.from(health))
                .entities(entities)
                .build();
    }

    /** Juengster Zeitpunkt aus Speicher-Uhr (seit Start) und DB-lastSeen (ueberlebt Neustarts). */
    private Instant lastHeard(String friendlyName, ZigbeeDevice db) {
        Optional<Instant> inMemory = streamMonitor.lastMessageAt(friendlyName);
        LocalDateTime lastSeen = db != null ? db.getLastSeen() : null;
        Instant fromDb = lastSeen != null ? lastSeen.atZone(clock.getZone()).toInstant() : null;
        if (inMemory.isPresent() && fromDb != null) {
            return inMemory.get().isAfter(fromDb) ? inMemory.get() : fromDb;
        }
        return inMemory.orElse(fromDb);
    }

    public ZigbeeBridgeStatusResponse bridgeStatus() {
        Optional<ZigbeeBridgeInfo> info = registry.info();
        return ZigbeeBridgeStatusResponse.builder()
                .version(info.map(ZigbeeBridgeInfo::version).orElse(null))
                .connected(commands.isConnected())
                .registryLoaded(registry.loaded())
                .permitJoin(info.map(ZigbeeBridgeInfo::permitJoin).orElse(false))
                .permitJoinEnd(info.map(ZigbeeBridgeInfo::permitJoinEnd).orElse(null))
                .availabilityCheckEnabled(info.map(ZigbeeBridgeInfo::availabilityCheckEnabled).orElse(null))
                .build();
    }

    public List<ZigbeeBridgeEventResponse> bridgeEvents() {
        return registry.events().stream().map(ZigbeeBridgeEventResponse::from).toList();
    }
}
```

**`EntityState.builder()`**: prüfen, dass die Entity `@Builder` trägt (Lombok). Falls nicht, im Test per `new EntityState()` + Setter bauen.

- [ ] **Step 5: `ZigbeeMqttConfig.bridgeStatus()` durch den Query-Service ersetzen**

Feld `private final ObjectProvider<ZigbeeDeviceQueryService> queryServiceProvider;` ergänzen (wieder `ObjectProvider`, weil der Query-Service über `ZigbeeBridgeCommands` auf diese Klasse zeigt), die private Methode `bridgeStatus()` samt `ZigbeeBridgeInfo`-Import löschen und im Info-Zweig `liveService.broadcastBridgeInfo(queryServiceProvider.getObject().bridgeStatus())` rufen.

- [ ] **Step 6: Tests laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest='Zigbee*'`
Expected: alle grün, darunter `ZigbeeDeviceQueryServiceTest: Tests run: 7`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee backend/src/test/java/com/household/manager/zigbee
git commit -m "feat(zigbee): Geraeteliste aus Registry, Tabelle, Entitaeten und Health-Urteil"
```

---

### Task 8: `ZigbeeDeviceManagementService` (permit-join, rename, interview, configure, remove)

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeDeviceManagementService.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeDeviceManagementServiceTest.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

```java
package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.zigbee.ZigbeeBridgeRejectedException;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import com.household.manager.zigbee.model.ZigbeeBridgeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeDeviceManagementServiceTest {

    private static final ZigbeeBridgeResponse OK = new ZigbeeBridgeResponse("x", "t", true, null);

    @Mock private ZigbeeBridgeRequestService requestService;
    @Mock private AuditService auditService;

    private final ZigbeeDeviceRegistry registry =
            new ZigbeeDeviceRegistry(Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC));
    private ZigbeeDeviceManagementService service;

    @BeforeEach
    void setUp() {
        service = new ZigbeeDeviceManagementService(requestService, registry, auditService);
        registry.replaceDevices(List.of(new ZigbeeBridgeDevice("0x1", "Motion Büro", "EndDevice", "Battery",
                true, true, "SNZB-03", "SONOFF", "Motion", 1)));
    }

    @Test
    void anlernenNutztDasZweierFormat() {
        registry.updateInfo(new ZigbeeBridgeInfo("2.1.3", false, null, true));
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.permitJoin(240);

        verify(requestService).send("permit_join", Map.of("time", 240));
        verify(auditService).record("zigbee.permit-join.open", "240 s");
    }

    @Test
    void anlernenNutztDasEinerFormatBeiAlterVersion() {
        registry.updateInfo(new ZigbeeBridgeInfo("1.42.0", false, null, true));
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.permitJoin(60);
        service.closePermitJoin();

        verify(requestService).send("permit_join", Map.of("value", true, "time", 60));
        verify(requestService).send("permit_join", Map.of("value", false, "time", 0));
        verify(auditService).record("zigbee.permit-join.close", "");
    }

    @Test
    void anlernenOhneInfoNutztDasZweierFormat() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.closePermitJoin();

        verify(requestService).send("permit_join", Map.of("time", 0));
    }

    @Test
    void anlerndauerWirdGeprueft() {
        assertThatThrownBy(() -> service.permitJoin(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.permitJoin(255)).isInstanceOf(IllegalArgumentException.class);
        verify(requestService, never()).send(anyString(), any());
    }

    @Test
    void umbenennenSendetAltUndNeuUndAuditiert() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.rename("0x1", "  Bewegung Büro ");

        verify(requestService).send("device/rename", Map.of("from", "Motion Büro", "to", "Bewegung Büro"));
        verify(auditService).record("zigbee.device.rename", "Motion Büro -> Bewegung Büro");
    }

    @Test
    void umbenennenLehntUngueltigeNamenVorDemSendenAb() {
        assertThatThrownBy(() -> service.rename("0x1", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "a+b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "a#b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("0x1", "x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        verify(requestService, never()).send(anyString(), any());
    }

    @Test
    void unbekannteIeeeAdresseIst404() {
        assertThatThrownBy(() -> service.interview("0xfehlt")).isInstanceOf(ResourceNotFoundException.class);
        verify(requestService, never()).send(anyString(), any());
    }

    @Test
    void interviewUndKonfigurierenAdressierenUeberDieIeee() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.interview("0x1");
        service.configure("0x1");

        verify(requestService).send("device/interview", Map.of("id", "0x1"));
        verify(requestService).send("device/configure", Map.of("id", "0x1"));
        verify(auditService).record("zigbee.device.interview", "Motion Büro (0x1)");
        verify(auditService).record("zigbee.device.configure", "Motion Büro (0x1)");
    }

    @Test
    void entfernenMitForceFlag() {
        when(requestService.send(anyString(), any())).thenReturn(OK);

        service.remove("0x1", true);

        verify(requestService).send("device/remove", Map.of("id", "0x1", "force", true));
        verify(auditService).record("zigbee.device.remove", "Motion Büro (0x1), force=true");
    }

    @Test
    void fehlgeschlageneAktionErzeugtKeinenAuditEintrag() {
        when(requestService.send(anyString(), any())).thenThrow(new ZigbeeBridgeRejectedException("nope"));

        assertThatThrownBy(() -> service.remove("0x1", false)).isInstanceOf(ZigbeeBridgeRejectedException.class);

        verify(auditService, never()).record(anyString(), anyString());
    }
}
```

- [ ] **Step 2: Test laufen lassen — Compile-Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDeviceManagementServiceTest`

- [ ] **Step 3: Service implementieren**

```java
package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Schreibende Geraeteverwaltung ueber die zigbee2mqtt-Bridge-API. Alle Aktionen ADMIN
 * (SecurityConfig); Audit erst NACH erfolgreicher Antwort (Muster NukiLockService).
 * <p>
 * Geraete werden ueber die IEEE-Adresse adressiert, nicht ueber den Friendly Name:
 * der ist genau das, was Umbenennen aendert, und z2m erlaubt '/' im Namen.
 */
@Service
@RequiredArgsConstructor
public class ZigbeeDeviceManagementService {

    static final int MAX_PERMIT_JOIN_SECONDS = 254;
    static final int MAX_NAME_LENGTH = 64;

    private final ZigbeeBridgeRequestService requestService;
    private final ZigbeeDeviceRegistry registry;
    private final AuditService auditService;

    public void permitJoin(int seconds) {
        if (seconds < 1 || seconds > MAX_PERMIT_JOIN_SECONDS) {
            throw new IllegalArgumentException("Anlerndauer muss zwischen 1 und 254 Sekunden liegen.");
        }
        requestService.send("permit_join", permitJoinParams(seconds));
        auditService.record("zigbee.permit-join.open", seconds + " s");
    }

    public void closePermitJoin() {
        requestService.send("permit_join", permitJoinParams(0));
        auditService.record("zigbee.permit-join.close", "");
    }

    /**
     * z2m 1.x verlangt {@code value: true/false} (+ optional time), 2.x nur {@code time}
     * (0 = schliessen). Die Version kommt aus bridge/info; ohne Info gilt 2.x.
     */
    private Map<String, Object> permitJoinParams(int seconds) {
        boolean legacy = registry.info().map(ZigbeeBridgeInfo::version)
                .map(version -> version.startsWith("1.")).orElse(false);
        return legacy
                ? Map.of("value", seconds > 0, "time", seconds)
                : Map.of("time", seconds);
    }

    public void rename(String ieeeAddress, String newName) {
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name muss 1 bis 64 Zeichen lang sein.");
        }
        if (trimmed.contains("/") || trimmed.contains("+") || trimmed.contains("#")) {
            throw new IllegalArgumentException("Name darf kein '/', '+' oder '#' enthalten (MQTT-Topic).");
        }
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/rename", Map.of("from", device.friendlyName(), "to", trimmed));
        auditService.record("zigbee.device.rename", device.friendlyName() + " -> " + trimmed);
    }

    public void interview(String ieeeAddress) {
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/interview", Map.of("id", device.ieeeAddress()));
        auditService.record("zigbee.device.interview", label(device));
    }

    public void configure(String ieeeAddress) {
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/configure", Map.of("id", device.ieeeAddress()));
        auditService.record("zigbee.device.configure", label(device));
    }

    /**
     * @param force nur den Eintrag in z2m loeschen, ohne das Geraet zu erreichen — fuer
     *              tote Batteriegeraete; auf ein lebendes Geraet angewendet funkt es weiter
     *              und taucht beim naechsten Anlernen wieder auf
     */
    public void remove(String ieeeAddress, boolean force) {
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/remove", Map.of("id", device.ieeeAddress(), "force", force));
        auditService.record("zigbee.device.remove", label(device) + ", force=" + force);
    }

    private ZigbeeBridgeDevice require(String ieeeAddress) {
        return registry.findByIeee(ieeeAddress)
                .orElseThrow(() -> new ResourceNotFoundException("Zigbee-Geraet", "ieeeAddress", ieeeAddress));
    }

    private static String label(ZigbeeBridgeDevice device) {
        return device.friendlyName() + " (" + device.ieeeAddress() + ")";
    }
}
```

- [ ] **Step 4: Test laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDeviceManagementServiceTest`
Expected: `Tests run: 10, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/service/ZigbeeDeviceManagementService.java backend/src/test/java/com/household/manager/zigbee/service/ZigbeeDeviceManagementServiceTest.java
git commit -m "feat(zigbee): Geraeteverwaltung - Anlernen, Umbenennen, Interview, Konfigurieren, Entfernen"
```

---

### Task 9: `ZigbeeDevicePurgeService` („Aus Household Manager entfernen")

**Files:**
- Modify: `backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeDevicePurgeService.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeDevicePurgeServiceTest.java`

- [ ] **Step 1: Bulk-Delete im Measurement-Repository**

In `ZigbeeMeasurementRepository` ergänzen (Muster `WasteCollectionEventRepository`: abgeleitete Delete-Methoden bringen keine Transaktion mit):

```java
    @Transactional
    @Modifying
    @Query("delete from ZigbeeMeasurement m where m.device.id = :deviceId")
    int deleteByDeviceId(@Param("deviceId") Long deviceId);
```

Imports: `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.repository.query.Param`, `org.springframework.transaction.annotation.Transactional`.

- [ ] **Step 2: Fehlschlagenden Test schreiben**

```java
package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.ZigbeeDeviceKnownToBridgeException;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeDevicePurgeServiceTest {

    @Mock private ZigbeeDeviceRepository deviceRepository;
    @Mock private ZigbeeMeasurementRepository measurementRepository;
    @Mock private EntityStateRepository entityStateRepository;
    @Mock private EntityTileVisibilityRepository tileVisibilityRepository;
    @Mock private AuditService auditService;

    private final ZigbeeDeviceRegistry registry =
            new ZigbeeDeviceRegistry(Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC));
    private ZigbeeDevicePurgeService service;

    private final ZigbeeDevice device = ZigbeeDevice.builder().id(7L).friendlyName("Alter Sensor").build();

    @BeforeEach
    void setUp() {
        service = new ZigbeeDevicePurgeService(registry, deviceRepository, measurementRepository,
                entityStateRepository, tileVisibilityRepository, auditService);
        registry.replaceDevices(List.of());
    }

    private static EntityState entity(String id, String ref) {
        return EntityState.builder().entityId(id).domain(EntityDomain.SENSOR).source(EntitySource.ZIGBEE)
                .sourceRef(ref).friendlyName(id).state("1").build();
    }

    @Test
    void loeschtMesswerteGeraetEntitaetenUndKachelregeln() {
        when(deviceRepository.findById(7L)).thenReturn(Optional.of(device));
        when(measurementRepository.deleteByDeviceId(7L)).thenReturn(1234);
        EntityState mine = entity("sensor.zigbee_alter_sensor_temperature", "Alter Sensor");
        EntityState other = entity("sensor.zigbee_anderes_temperature", "Anderes");
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of(mine, other));
        EntityTileVisibility rule = EntityTileVisibility.builder()
                .entityId("sensor.zigbee_alter_sensor_temperature").tileKey("switches").build();
        when(tileVisibilityRepository.findByEntityIdIn(List.of("sensor.zigbee_alter_sensor_temperature")))
                .thenReturn(List.of(rule));

        ZigbeeDevicePurgeService.PurgeResult result = service.purge(7L);

        assertThat(result.measurements()).isEqualTo(1234);
        assertThat(result.entities()).isEqualTo(1);
        verify(tileVisibilityRepository).deleteAll(List.of(rule));
        verify(entityStateRepository).deleteAll(List.of(mine));
        verify(deviceRepository).delete(device);
        verify(auditService).record("zigbee.device.purge", "Alter Sensor: 1234 Messwerte, 1 Entitaeten");
    }

    @Test
    void verweigertWennZigbee2mqttDasGeraetNochKennt() {
        when(deviceRepository.findById(7L)).thenReturn(Optional.of(device));
        registry.replaceDevices(List.of(new ZigbeeBridgeDevice("0x1", "Alter Sensor", "EndDevice", "Battery",
                true, true, null, null, null, 1)));

        assertThatThrownBy(() -> service.purge(7L)).isInstanceOf(ZigbeeDeviceKnownToBridgeException.class);

        verify(deviceRepository, never()).delete(any());
        verify(auditService, never()).record(anyString(), anyString());
    }

    @Test
    void unbekannteIdIst404() {
        when(deviceRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purge(99L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
```

`EntityTileVisibility.builder()`: prüfen, ob die Entity `@Builder` hat; sonst `new EntityTileVisibility()` + Setter.

- [ ] **Step 3: Test laufen lassen — Compile-Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDevicePurgeServiceTest`

- [ ] **Step 4: Service implementieren**

```java
package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.EntityTileVisibilityRepository;
import com.household.manager.repository.ZigbeeDeviceRepository;
import com.household.manager.repository.ZigbeeMeasurementRepository;
import com.household.manager.zigbee.ZigbeeDeviceKnownToBridgeException;
import com.household.manager.zigbee.model.entity.ZigbeeDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * "Aus Household Manager entfernen": loescht Messwerte, Geraetezeile, Entitaeten und
 * deren Kachelregeln in EINER Transaktion. Nur fuer Geraete, die zigbee2mqtt nicht
 * (mehr) kennt — sonst legte die naechste Nachricht alles wieder an und der Knopf
 * waere eine Luege.
 * <p>
 * Die Kachelregeln muessen explizit mit: entity_tile_visibility hat keinen
 * Fremdschluessel auf entity_states, eine verwaiste NEVER-Zeile griffe beim naechsten
 * gleichnamigen Geraet wieder (die Falle aus dem Helfer-Kapitel in CLAUDE.md).
 */
@Service
@RequiredArgsConstructor
public class ZigbeeDevicePurgeService {

    public record PurgeResult(String friendlyName, int measurements, int entities) {
    }

    private final ZigbeeDeviceRegistry registry;
    private final ZigbeeDeviceRepository deviceRepository;
    private final ZigbeeMeasurementRepository measurementRepository;
    private final EntityStateRepository entityStateRepository;
    private final EntityTileVisibilityRepository tileVisibilityRepository;
    private final AuditService auditService;

    @Transactional
    public PurgeResult purge(Long deviceId) {
        ZigbeeDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Zigbee-Geraet", "id", deviceId));
        String friendlyName = device.getFriendlyName();
        if (registry.findByFriendlyName(friendlyName).isPresent()) {
            throw new ZigbeeDeviceKnownToBridgeException(
                    "'" + friendlyName + "' ist zigbee2mqtt noch bekannt — zuerst dort entfernen.");
        }

        List<EntityState> entities = entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE).stream()
                .filter(entity -> friendlyName.equals(entity.getSourceRef()))
                .toList();
        List<String> entityIds = entities.stream().map(EntityState::getEntityId).toList();
        if (!entityIds.isEmpty()) {
            List<EntityTileVisibility> rules = tileVisibilityRepository.findByEntityIdIn(entityIds);
            tileVisibilityRepository.deleteAll(rules);
            entityStateRepository.deleteAll(entities);
        }
        int measurements = measurementRepository.deleteByDeviceId(deviceId);
        deviceRepository.delete(device);

        auditService.record("zigbee.device.purge",
                friendlyName + ": " + measurements + " Messwerte, " + entities.size() + " Entitaeten");
        return new PurgeResult(friendlyName, measurements, entities.size());
    }
}
```

- [ ] **Step 5: Test laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest=ZigbeeDevicePurgeServiceTest`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/repository/ZigbeeMeasurementRepository.java backend/src/main/java/com/household/manager/zigbee/service/ZigbeeDevicePurgeService.java backend/src/test/java/com/household/manager/zigbee/service/ZigbeeDevicePurgeServiceTest.java
git commit -m "feat(zigbee): Aus Household Manager entfernen - Messwerte, Geraet, Entitaeten, Kachelregeln"
```

---

### Task 10: `ZigbeeFlowReferenceService`

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/FlowReferenceResponse.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeFlowReferenceService.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeFlowReferenceServiceTest.java`

- [ ] **Step 1: DTO**

```java
package com.household.manager.zigbee.dto;

/** Ein Flow, der eine Entitaet des Geraets verwendet (Draft oder Deployed). */
public record FlowReferenceResponse(Long flowId, String name, boolean enabled) {
}
```

- [ ] **Step 2: Fehlschlagenden Test schreiben**

```java
package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.FlowRepository;
import com.household.manager.zigbee.dto.FlowReferenceResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZigbeeFlowReferenceServiceTest {

    @Mock private EntityStateRepository entityStateRepository;
    @Mock private FlowRepository flowRepository;
    @InjectMocks private ZigbeeFlowReferenceService service;

    private static EntityState entity(String id, String ref) {
        return EntityState.builder().entityId(id).domain(EntityDomain.SENSOR).source(EntitySource.ZIGBEE)
                .sourceRef(ref).friendlyName(id).state("1").build();
    }

    private static Flow flow(long id, String name, boolean enabled, String draft, String deployed) {
        return Flow.builder().id(id).name(name).enabled(enabled).draftDefinition(draft).deployedDefinition(deployed).build();
    }

    @Test
    void findetFlowsInDraftUndDeployed() {
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of(
                entity("sensor.zigbee_temperatur_buero_temperature", "Temperatur Büro"),
                entity("sensor.zigbee_temperatur_buero_humidity", "Temperatur Büro"),
                entity("sensor.zigbee_anderes_temperature", "Anderes")));
        when(flowRepository.findAllByOrderByNameAsc()).thenReturn(List.of(
                flow(4, "Feuer-Verdacht", true, null,
                        "{\"nodes\":[{\"config\":{\"entityId\":\"sensor.zigbee_temperatur_buero_temperature\"}}]}"),
                flow(9, "Entwurf", false,
                        "{\"nodes\":[{\"config\":{\"entityId\":\"sensor.zigbee_temperatur_buero_humidity\"}}]}", null),
                flow(2, "Unbeteiligt", true, null,
                        "{\"nodes\":[{\"config\":{\"entityId\":\"sensor.zigbee_anderes_temperature\"}}]}")));

        List<FlowReferenceResponse> refs = service.references("Temperatur Büro");

        assertThat(refs).containsExactly(
                new FlowReferenceResponse(4L, "Feuer-Verdacht", true),
                new FlowReferenceResponse(9L, "Entwurf", false));
    }

    @Test
    void teilstringTrefferIstBewusstEinTreffer() {
        // Falsch-positiv ist bei einer Warnung akzeptabel, falsch-negativ nicht.
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of(
                entity("sensor.zigbee_buero_temperature", "Büro")));
        when(flowRepository.findAllByOrderByNameAsc()).thenReturn(List.of(
                flow(1, "Anderer Sensor", true, null, "{\"entityId\":\"sensor.zigbee_buero_temperature_2\"}")));

        assertThat(service.references("Büro")).hasSize(1);
    }

    @Test
    void ohneEntitaetenKeineFlowSuche() {
        when(entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE)).thenReturn(List.of());

        assertThat(service.references("Nie gesendet")).isEmpty();
        verify(flowRepository, never()).findAllByOrderByNameAsc();
    }
}
```

- [ ] **Step 3: Test laufen lassen — Compile-Fehler erwartet**

Run: `cd backend && mvn -q test -Dtest=ZigbeeFlowReferenceServiceTest`

- [ ] **Step 4: Service implementieren**

```java
package com.household.manager.zigbee.service;

import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.EntityStateRepository;
import com.household.manager.repository.FlowRepository;
import com.household.manager.zigbee.dto.FlowReferenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Welche Flows verwenden Entitaeten dieses Geraets? Fuer die Warnung vor Umbenennen
 * und Entfernen — warnt, blockiert nicht.
 * <p>
 * Bewusst eine String-Suche im Flow-JSON: Node-Configs sind eine freie Map, eine
 * strukturierte Suche hinkte bei jedem neuen Node-Typ nach. Ein falsch-positiver
 * Teilstring-Treffer ist bei einer Warnung akzeptabel, ein falsch-negativer nicht.
 */
@Service
@RequiredArgsConstructor
public class ZigbeeFlowReferenceService {

    private final EntityStateRepository entityStateRepository;
    private final FlowRepository flowRepository;

    @Transactional(readOnly = true)
    public List<FlowReferenceResponse> references(String friendlyName) {
        List<String> entityIds = entityStateRepository.findBySourceOrderByEntityIdAsc(EntitySource.ZIGBEE).stream()
                .filter(entity -> friendlyName.equals(entity.getSourceRef()))
                .map(EntityState::getEntityId)
                .toList();
        if (entityIds.isEmpty()) {
            return List.of();
        }
        return flowRepository.findAllByOrderByNameAsc().stream()
                .filter(flow -> mentionsAny(flow, entityIds))
                .map(flow -> new FlowReferenceResponse(flow.getId(), flow.getName(), flow.isEnabled()))
                .toList();
    }

    private static boolean mentionsAny(Flow flow, List<String> entityIds) {
        String draft = flow.getDraftDefinition() != null ? flow.getDraftDefinition() : "";
        String deployed = flow.getDeployedDefinition() != null ? flow.getDeployedDefinition() : "";
        return entityIds.stream().anyMatch(id -> draft.contains(id) || deployed.contains(id));
    }
}
```

- [ ] **Step 5: Test laufen lassen — grün**

Run: `cd backend && mvn -q test -Dtest=ZigbeeFlowReferenceServiceTest`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee backend/src/test/java/com/household/manager/zigbee
git commit -m "feat(zigbee): Flow-Referenzen eines Geraets fuer die Warnung vor Umbenennen/Entfernen"
```

---

### Task 11: Controller, Request-DTOs, Security-Regeln

**Files:**
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/PermitJoinRequest.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/RenameDeviceRequest.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/dto/PurgeResultResponse.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/controller/ZigbeeController.java`
- Create: `backend/src/main/java/com/household/manager/zigbee/controller/ZigbeeManagementController.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Request-/Response-DTOs**

```java
// dto/PermitJoinRequest.java
package com.household.manager.zigbee.dto;

/** Body von POST /v1/zigbee/bridge/permit-join. */
public record PermitJoinRequest(int seconds) {
}
```

```java
// dto/RenameDeviceRequest.java
package com.household.manager.zigbee.dto;

/** Body von PUT /v1/zigbee/devices/{ieee}/name. */
public record RenameDeviceRequest(String friendlyName) {
}
```

```java
// dto/PurgeResultResponse.java
package com.household.manager.zigbee.dto;

/** Antwort von DELETE /v1/zigbee/devices/local/{id}. */
public record PurgeResultResponse(String friendlyName, int measurements, int entities) {
}
```

- [ ] **Step 2: `ZigbeeController` umbauen**

Felder `deviceRepository`-Nutzung in `getDevices()` durch den Query-Service ersetzen; `toDeviceResponse` löschen. Neue Felder: `private final ZigbeeDeviceQueryService queryService; private final ZigbeeFlowReferenceService flowReferenceService;`. (`deviceRepository`/`measurementRepository` bleiben für `/measurements`.)

```java
    @GetMapping("/devices")
    public ResponseEntity<List<ZigbeeDeviceResponse>> getDevices() {
        return ResponseEntity.ok(queryService.listDevices());
    }

    @GetMapping("/bridge")
    public ResponseEntity<ZigbeeBridgeStatusResponse> getBridge() {
        return ResponseEntity.ok(queryService.bridgeStatus());
    }

    @GetMapping("/bridge/events")
    public ResponseEntity<List<ZigbeeBridgeEventResponse>> getBridgeEvents() {
        return ResponseEntity.ok(queryService.bridgeEvents());
    }

    /** Friendly Name als Query-Parameter, weil z2m '/' im Namen erlaubt. */
    @GetMapping("/flow-references")
    public ResponseEntity<List<FlowReferenceResponse>> getFlowReferences(@RequestParam String friendlyName) {
        return ResponseEntity.ok(flowReferenceService.references(friendlyName));
    }
```

Imports für `ZigbeeBridgeStatusResponse`, `ZigbeeBridgeEventResponse`, `FlowReferenceResponse`, `ZigbeeDeviceQueryService`, `ZigbeeFlowReferenceService` ergänzen; nicht mehr genutzte (`ZigbeeDevice`-Builder-Import bleibt für `/measurements`) prüfen.

- [ ] **Step 3: `ZigbeeManagementController` anlegen**

```java
package com.household.manager.zigbee.controller;

import com.household.manager.zigbee.dto.PermitJoinRequest;
import com.household.manager.zigbee.dto.PurgeResultResponse;
import com.household.manager.zigbee.dto.RenameDeviceRequest;
import com.household.manager.zigbee.service.ZigbeeDeviceManagementService;
import com.household.manager.zigbee.service.ZigbeeDevicePurgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Schreibende Zigbee-Geraeteverwaltung. Alle Endpunkte ADMIN ueber methodenspezifische
 * Matcher in SecurityConfig — GET /devices/{name}/measurements liegt unter demselben
 * Pfadpraefix und muss KIOSK bleiben.
 */
@RestController
@RequestMapping("/v1/zigbee")
@RequiredArgsConstructor
public class ZigbeeManagementController {

    private final ZigbeeDeviceManagementService managementService;
    private final ZigbeeDevicePurgeService purgeService;

    @PostMapping("/bridge/permit-join")
    public ResponseEntity<Void> openPermitJoin(@RequestBody PermitJoinRequest request) {
        managementService.permitJoin(request.seconds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/bridge/permit-join")
    public ResponseEntity<Void> closePermitJoin() {
        managementService.closePermitJoin();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/devices/{ieee}/name")
    public ResponseEntity<Void> rename(@PathVariable String ieee, @RequestBody RenameDeviceRequest request) {
        managementService.rename(ieee, request.friendlyName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ieee}/interview")
    public ResponseEntity<Void> interview(@PathVariable String ieee) {
        managementService.interview(ieee);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ieee}/configure")
    public ResponseEntity<Void> configure(@PathVariable String ieee) {
        managementService.configure(ieee);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/devices/{ieee}")
    public ResponseEntity<Void> remove(@PathVariable String ieee,
                                       @RequestParam(defaultValue = "false") boolean force) {
        managementService.remove(ieee, force);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/devices/local/{id}")
    public ResponseEntity<PurgeResultResponse> purge(@PathVariable Long id) {
        var result = purgeService.purge(id);
        return ResponseEntity.ok(new PurgeResultResponse(result.friendlyName(), result.measurements(), result.entities()));
    }
}
```

- [ ] **Step 4: Security-Regeln** — in `SecurityConfig` direkt nach den drei `/v1/presence/devices`-Zeilen und vor `POST /v1/presence/refresh`:

```java
                        // Zigbee-Geraeteverwaltung (Anlernen, Umbenennen, Interview, Entfernen) ist
                        // ADMIN. Methodenspezifisch, weil GET /v1/zigbee/devices/*/measurements unter
                        // demselben Praefix liegt und KIOSK bleiben muss (Muster Kalender-Kategorien).
                        .requestMatchers(HttpMethod.POST, "/v1/zigbee/bridge/permit-join").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/zigbee/bridge/permit-join").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/v1/zigbee/devices/*/name").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/v1/zigbee/devices/*/interview",
                                "/v1/zigbee/devices/*/configure").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/zigbee/devices/*",
                                "/v1/zigbee/devices/local/*").hasRole("ADMIN")
```

- [ ] **Step 5: `SecurityRulesTest` erweitern**

In `@WebMvcTest(controllers = {...})` ergänzen: `ZigbeeController.class, ZigbeeManagementController.class` (Imports aus `com.household.manager.zigbee.controller`, `.service` und `com.household.manager.repository` ergänzen). Mocks ergänzen:

```java
    @MockitoBean private ZigbeeDeviceRepository zigbeeDeviceRepository;
    @MockitoBean private ZigbeeMeasurementRepository zigbeeMeasurementRepository;
    @MockitoBean private ZigbeeLiveService zigbeeLiveService;
    @MockitoBean private ZigbeeStreamMonitor zigbeeStreamMonitor;
    @MockitoBean private ZigbeeDeviceQueryService zigbeeDeviceQueryService;
    @MockitoBean private ZigbeeFlowReferenceService zigbeeFlowReferenceService;
    @MockitoBean private ZigbeeDeviceManagementService zigbeeDeviceManagementService;
    @MockitoBean private ZigbeeDevicePurgeService zigbeeDevicePurgeService;
```

Tests anhängen:

```java
    // --- Zigbee-Geraeteverwaltung: jede ADMIN-Zeile hat ihren eigenen MEMBER-Verbotstest ---

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinAnlernfensterOeffnen() throws Exception {
        mockMvc.perform(post("/v1/zigbee/bridge/permit-join").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":240}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinAnlernfensterSchliessen() throws Exception {
        mockMvc.perform(delete("/v1/zigbee/bridge/permit-join").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfZigbeeGeraeteNichtUmbenennen() throws Exception {
        mockMvc.perform(put("/v1/zigbee/devices/0x1/name").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"friendlyName\":\"Neu\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinInterviewUndKeinKonfigurierenAusloesen() throws Exception {
        mockMvc.perform(post("/v1/zigbee/devices/0x1/interview").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/zigbee/devices/0x1/configure").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfZigbeeGeraeteNichtEntfernen() throws Exception {
        mockMvc.perform(delete("/v1/zigbee/devices/0x1").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/zigbee/devices/local/5").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDarfZigbeeGeraeteVerwalten() throws Exception {
        mockMvc.perform(post("/v1/zigbee/bridge/permit-join").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seconds\":240}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/v1/zigbee/devices/0x1").with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfZigbeeLesen() throws Exception {
        when(zigbeeDeviceQueryService.listDevices()).thenReturn(List.of());
        when(zigbeeDeviceQueryService.bridgeEvents()).thenReturn(List.of());
        mockMvc.perform(get("/v1/zigbee/devices")).andExpect(status().isOk());
        mockMvc.perform(get("/v1/zigbee/bridge")).andExpect(status().isOk());
        mockMvc.perform(get("/v1/zigbee/bridge/events")).andExpect(status().isOk());
        // 404 = Regel laesst durch, das Geraet gibt es nur nicht (Repository-Mock leer)
        mockMvc.perform(get("/v1/zigbee/devices/Motion/measurements").param("type", "OCCUPANCY"))
                .andExpect(status().isNotFound());
    }
```

`when(zigbeeDeviceQueryService.bridgeStatus())` liefert vom Mock `null` → Jackson serialisiert `null` als leeren Body mit 200 — reicht für den Regeltest. Falls der Test dennoch 500 liefert, `when(zigbeeDeviceQueryService.bridgeStatus()).thenReturn(ZigbeeBridgeStatusResponse.builder().build())` ergänzen.

- [ ] **Step 6: Tests laufen lassen**

Run: `cd backend && mvn -q test -Dtest='SecurityRulesTest,Zigbee*'`
Expected: alle grün

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(zigbee): Lese- und Verwaltungs-Endpunkte, ADMIN-Matcher fuer schreibende Aktionen"
```

---

### Task 12: Frontend-Modelle und `ZigbeeService`

**Files:**
- Modify: `frontend/src/app/models/zigbee.model.ts`
- Modify: `frontend/src/app/services/zigbee.service.ts`

- [ ] **Step 1: Modell erweitern** — `ZigbeeDevice` ersetzen und neue Typen anhängen

```ts
export type ZigbeeDeviceHealthStatus = 'OFFLINE' | 'INTERVIEW' | 'UNKNOWN' | 'SILENT' | 'ACTIVE';

export interface ZigbeeDeviceHealth {
  status: ZigbeeDeviceHealthStatus;
  basis: 'zigbee2mqtt' | 'registry' | 'last-message' | null;
  lastHeardAt: string | null;
  silentSeconds: number | null;
}

export interface ZigbeeDeviceEntity {
  entityId: string;
  displayName: string;
  domain: string;
  state: string;
  lastChanged: string;
  lastUpdated: string;
}

/** GET /api/v1/zigbee/devices — Grundlage ist zigbee2mqtt's Verzeichnis. */
export interface ZigbeeDevice {
  /** DB-Id; null, wenn das Geraet noch nie gesendet hat. */
  id: number | null;
  friendlyName: string;
  ieeeAddress: string | null;
  /** false = nur in unserer Tabelle, zigbee2mqtt kennt es nicht (mehr). */
  knownToBridge: boolean;
  type: string | null;
  powerSource: string | null;
  battery: boolean;
  interviewCompleted: boolean | null;
  supported: boolean | null;
  model: string | null;
  vendor: string | null;
  description: string | null;
  lastBatteryPercent: number | null;
  lastLinkQuality: number | null;
  lastSeen: string | null;
  health: ZigbeeDeviceHealth;
  entities: ZigbeeDeviceEntity[];
}

/** GET /api/v1/zigbee/bridge und SSE 'bridge-info'. */
export interface ZigbeeBridgeStatus {
  version: string | null;
  connected: boolean;
  registryLoaded: boolean;
  permitJoin: boolean;
  permitJoinEnd: string | null;
  /** null, solange nie eine bridge/info kam. */
  availabilityCheckEnabled: boolean | null;
}

export type ZigbeeBridgeEventType = 'device_joined' | 'device_interview' | 'device_announce' | 'device_leave';

/** GET /api/v1/zigbee/bridge/events und SSE 'bridge-event'. */
export interface ZigbeeBridgeEvent {
  type: ZigbeeBridgeEventType;
  friendlyName: string | null;
  ieeeAddress: string | null;
  status: 'started' | 'successful' | 'failed' | null;
  model: string | null;
  vendor: string | null;
  receivedAt: string;
}

export interface FlowReference {
  flowId: number;
  name: string;
  enabled: boolean;
}

export interface PurgeResult {
  friendlyName: string;
  measurements: number;
  entities: number;
}
```

`ZigbeeMeasurementType`, `ZigbeeMeasurement`, `ZigbeeLiveEvent`, `ZigbeeHealth` bleiben unverändert.

- [ ] **Step 2: Service erweitern** — nach `getHealth()` einfügen; `handleError` so ändern, dass die Server-Meldung erhalten bleibt (die Dialoge zeigen z2m's Fehlertext):

```ts
  getBridge(): Observable<ZigbeeBridgeStatus> {
    return this.http.get<ZigbeeBridgeStatus>(`${this.baseUrl}/bridge`).pipe(catchError(this.handleError));
  }

  getBridgeEvents(): Observable<ZigbeeBridgeEvent[]> {
    return this.http.get<ZigbeeBridgeEvent[]>(`${this.baseUrl}/bridge/events`).pipe(catchError(this.handleError));
  }

  getFlowReferences(friendlyName: string): Observable<FlowReference[]> {
    const params = new HttpParams().set('friendlyName', friendlyName);
    return this.http.get<FlowReference[]>(`${this.baseUrl}/flow-references`, { params }).pipe(catchError(this.handleError));
  }

  openPermitJoin(seconds: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/bridge/permit-join`, { seconds }).pipe(catchError(this.handleError));
  }

  closePermitJoin(): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/bridge/permit-join`).pipe(catchError(this.handleError));
  }

  renameDevice(ieeeAddress: string, friendlyName: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}/name`, { friendlyName })
      .pipe(catchError(this.handleError));
  }

  interviewDevice(ieeeAddress: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}/interview`, {})
      .pipe(catchError(this.handleError));
  }

  configureDevice(ieeeAddress: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}/configure`, {})
      .pipe(catchError(this.handleError));
  }

  removeDevice(ieeeAddress: string, force: boolean): Observable<void> {
    const params = new HttpParams().set('force', String(force));
    return this.http.delete<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}`, { params })
      .pipe(catchError(this.handleError));
  }

  purgeLocalData(deviceId: number): Observable<PurgeResult> {
    return this.http.delete<PurgeResult>(`${this.baseUrl}/devices/local/${deviceId}`).pipe(catchError(this.handleError));
  }

  /** Server-Meldung (z. B. z2m's Fehlertext) durchreichen, sonst generischer Text. */
  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Zigbee API-Fehler:', error);
    const message = typeof error.error?.message === 'string' && error.error.message.trim()
      ? error.error.message
      : 'Fehler bei der Zigbee-Anfrage.';
    return throwError(() => new Error(message));
  }
```

Imports im Modell-Import ergänzen: `FlowReference, PurgeResult, ZigbeeBridgeEvent, ZigbeeBridgeStatus`.

- [ ] **Step 3: Typprüfung**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: Fehler nur in `zigbee.component.ts` (nutzt noch das alte `ZigbeeDevice`-Feld `deviceType`/`id: number`) — wird in Task 15 behoben. Keine anderen Fehler.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/zigbee.model.ts frontend/src/app/services/zigbee.service.ts
git commit -m "feat(zigbee): Frontend-Modelle und Service fuer Bridge-Status, Ereignisse und Verwaltung"
```

---

### Task 13: `ZigbeeLiveService` — drei Ereignisse über eine SSE-Verbindung

**Files:**
- Modify: `frontend/src/app/services/zigbee-live.service.ts`

Heute schließt das Teardown jedes Observables die geteilte `EventSource`. Mit drei Observables auf derselben Verbindung darf ein Unsubscribe nur seinen Listener entfernen; die Verbindung schließt die Komponente in `ngOnDestroy` über `disconnect()`.

- [ ] **Step 1: Service umbauen**

```ts
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { ZigbeeBridgeEvent, ZigbeeBridgeStatus, ZigbeeLiveEvent } from '../models/zigbee.model';

type LiveStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * SSE-Service fuer /api/v1/zigbee/live. Eine Verbindung, drei benannte Ereignisse:
 * 'live' (Messwerte), 'bridge-event' (Anlernen live), 'bridge-info' (Anlernfenster,
 * Verfuegbarkeitspruefung). Ein Unsubscribe entfernt nur seinen Listener — die
 * Verbindung schliesst erst disconnect() (die Seite ruft es in ngOnDestroy).
 */
@Injectable({ providedIn: 'root' })
export class ZigbeeLiveService {
  private readonly url = '/api/v1/zigbee/live';
  private eventSource: EventSource | null = null;
  private readonly statusSubject = new BehaviorSubject<LiveStatus>('disconnected');

  getStatusStream(): Observable<LiveStatus> {
    return this.statusSubject.asObservable();
  }

  getLiveStream(): Observable<ZigbeeLiveEvent> {
    return this.stream<ZigbeeLiveEvent>('live');
  }

  getBridgeEvents(): Observable<ZigbeeBridgeEvent> {
    return this.stream<ZigbeeBridgeEvent>('bridge-event');
  }

  getBridgeInfo(): Observable<ZigbeeBridgeStatus> {
    return this.stream<ZigbeeBridgeStatus>('bridge-info');
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.statusSubject.next('disconnected');
  }

  private stream<T>(name: string): Observable<T> {
    return new Observable<T>((observer) => {
      const source = this.connect();
      const listener = (event: MessageEvent) => {
        try {
          observer.next(JSON.parse(event.data) as T);
        } catch (error) {
          observer.error(error);
        }
      };
      source.addEventListener(name, listener);
      return () => source.removeEventListener(name, listener);
    });
  }

  private connect(): EventSource {
    if (this.eventSource) { return this.eventSource; }
    this.statusSubject.next('connecting');
    const source = new EventSource(this.url);
    source.onopen = () => this.statusSubject.next('connected');
    // Kein observer.error hier: der Browser verbindet SSE selbst neu, ein Fehler
    // wuerde alle drei Streams beenden, obwohl die Verbindung gleich wiederkommt.
    source.onerror = () => this.statusSubject.next('error');
    this.eventSource = source;
    return source;
  }
}
```

- [ ] **Step 2: Typprüfung**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: weiterhin nur die bekannten Fehler in `zigbee.component.ts`

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/services/zigbee-live.service.ts
git commit -m "feat(zigbee): SSE-Service liefert Bridge-Ereignisse und Bridge-Info ueber dieselbe Verbindung"
```

---

### Task 14: `zigbee-device-view.util.ts` — reine Anzeige-Logik

**Files:**
- Create: `frontend/src/app/shared/zigbee-device-view.util.ts`
- Test: `frontend/src/app/shared/zigbee-device-view.util.spec.ts`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

```ts
import {
  bridgeEventText,
  healthBadge,
  permitJoinRemainingSeconds,
  silentText,
  sortDevicesForDisplay
} from './zigbee-device-view.util';
import { ZigbeeBridgeEvent, ZigbeeDevice, ZigbeeDeviceHealthStatus } from '../models/zigbee.model';

describe('zigbee-device-view.util', () => {
  const device = (name: string, status: ZigbeeDeviceHealthStatus, overrides: Partial<ZigbeeDevice> = {}): ZigbeeDevice => ({
    id: 1, friendlyName: name, ieeeAddress: '0x1', knownToBridge: true, type: 'EndDevice',
    powerSource: 'Battery', battery: true, interviewCompleted: true, supported: true,
    model: null, vendor: null, description: null, lastBatteryPercent: null, lastLinkQuality: null,
    lastSeen: null, entities: [],
    health: { status, basis: null, lastHeardAt: null, silentSeconds: null },
    ...overrides
  });

  describe('sortDevicesForDisplay', () => {
    it('stellt kranke Geraete vor aktive, innerhalb alphabetisch', () => {
      const sorted = sortDevicesForDisplay([
        device('Zeta', 'ACTIVE'),
        device('Beta', 'SILENT'),
        device('Alpha', 'ACTIVE'),
        device('Gamma', 'OFFLINE'),
        device('delta', 'UNKNOWN'),
        device('Epsilon', 'INTERVIEW')
      ]);

      expect(sorted.map(d => d.friendlyName)).toEqual(['Beta', 'delta', 'Epsilon', 'Gamma', 'Alpha', 'Zeta']);
    });

    it('laesst die Eingabe unveraendert', () => {
      const input = [device('B', 'ACTIVE'), device('A', 'ACTIVE')];
      sortDevicesForDisplay(input);
      expect(input.map(d => d.friendlyName)).toEqual(['B', 'A']);
    });
  });

  describe('healthBadge', () => {
    it('kennt jeden Status', () => {
      expect(healthBadge('ACTIVE')).toEqual({ label: 'Aktiv', cssClass: 'badge--active' });
      expect(healthBadge('SILENT')).toEqual({ label: 'Verstummt', cssClass: 'badge--silent' });
      expect(healthBadge('OFFLINE')).toEqual({ label: 'Offline', cssClass: 'badge--offline' });
      expect(healthBadge('UNKNOWN')).toEqual({ label: 'Unbekannt', cssClass: 'badge--neutral' });
      expect(healthBadge('INTERVIEW')).toEqual({ label: 'Interview', cssClass: 'badge--neutral' });
    });
  });

  describe('silentText', () => {
    it('formatiert Minuten, Stunden und Tage', () => {
      expect(silentText(null)).toBe('nie gehört');
      expect(silentText(30)).toBe('zuletzt gehört vor weniger als einer Minute');
      expect(silentText(90)).toBe('zuletzt gehört vor 1 Minute');
      expect(silentText(5 * 60)).toBe('zuletzt gehört vor 5 Minuten');
      expect(silentText(3 * 3600 + 20 * 60)).toBe('zuletzt gehört vor 3 Std. 20 Min.');
      expect(silentText(21 * 86400 + 3600)).toBe('zuletzt gehört vor 21 Tagen');
      expect(silentText(86400)).toBe('zuletzt gehört vor 1 Tag');
    });
  });

  describe('permitJoinRemainingSeconds', () => {
    const now = Date.parse('2026-09-15T10:00:00Z');

    it('rechnet aus dem Server-Ende, nie negativ', () => {
      expect(permitJoinRemainingSeconds('2026-09-15T10:03:20Z', now)).toBe(200);
      expect(permitJoinRemainingSeconds('2026-09-15T09:59:00Z', now)).toBe(0);
      expect(permitJoinRemainingSeconds(null, now)).toBe(0);
      expect(permitJoinRemainingSeconds('kaputt', now)).toBe(0);
    });
  });

  describe('bridgeEventText', () => {
    const event = (overrides: Partial<ZigbeeBridgeEvent>): ZigbeeBridgeEvent => ({
      type: 'device_joined', friendlyName: '0x00158d00aaaaaaaa', ieeeAddress: '0x00158d00aaaaaaaa',
      status: null, model: null, vendor: null, receivedAt: '2026-09-15T10:00:00Z', ...overrides
    });

    it('beschreibt jeden Ereignistyp', () => {
      expect(bridgeEventText(event({}))).toBe('0x00158d00aaaaaaaa beigetreten');
      expect(bridgeEventText(event({ type: 'device_interview', status: 'started' }))).toBe('0x00158d00aaaaaaaa: Interview läuft …');
      expect(bridgeEventText(event({ type: 'device_interview', status: 'successful', model: 'SNZB-03', vendor: 'SONOFF' })))
        .toBe('0x00158d00aaaaaaaa: Interview erfolgreich (SNZB-03, SONOFF)');
      expect(bridgeEventText(event({ type: 'device_interview', status: 'failed' }))).toBe('0x00158d00aaaaaaaa: Interview fehlgeschlagen');
      expect(bridgeEventText(event({ type: 'device_announce', friendlyName: 'Motion Büro' }))).toBe('Motion Büro hat sich gemeldet');
      expect(bridgeEventText(event({ type: 'device_leave', friendlyName: 'Motion Büro' }))).toBe('Motion Büro hat das Netz verlassen');
    });

    it('faellt ohne Namen auf die IEEE-Adresse zurueck', () => {
      expect(bridgeEventText(event({ friendlyName: null }))).toBe('0x00158d00aaaaaaaa beigetreten');
      expect(bridgeEventText(event({ friendlyName: null, ieeeAddress: null }))).toBe('Unbekanntes Gerät beigetreten');
    });
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/zigbee-device-view.util.spec.ts'`
Expected: Compile-Fehler, Modul nicht gefunden

- [ ] **Step 3: Util implementieren**

```ts
import { ZigbeeBridgeEvent, ZigbeeDevice, ZigbeeDeviceHealthStatus } from '../models/zigbee.model';

/**
 * Reine Anzeige-Logik der Zigbee-Seite (kein Angular): Sortierung, Badge, Texte,
 * Countdown. Einzige Definition — Seite und Tests fragen dieselben Funktionen.
 */

/** Reihenfolge der Sortierung: das Handlungsbeduerftige zuerst. */
const STATUS_RANK: Record<ZigbeeDeviceHealthStatus, number> = {
  OFFLINE: 0,
  SILENT: 0,
  UNKNOWN: 0,
  INTERVIEW: 0,
  ACTIVE: 1
};

export function sortDevicesForDisplay(devices: ZigbeeDevice[]): ZigbeeDevice[] {
  return [...devices].sort((a, b) =>
    STATUS_RANK[a.health.status] - STATUS_RANK[b.health.status]
    || a.friendlyName.localeCompare(b.friendlyName, 'de', { sensitivity: 'base' }));
}

export interface HealthBadge {
  label: string;
  cssClass: string;
}

/**
 * Exhaustiv ueber den Typ: ein sechster Status wird zum Compilerfehler statt still auf
 * das falsche Badge zu landen (Muster presenceRingClass).
 */
const BADGES: Record<ZigbeeDeviceHealthStatus, HealthBadge> = {
  ACTIVE: { label: 'Aktiv', cssClass: 'badge--active' },
  SILENT: { label: 'Verstummt', cssClass: 'badge--silent' },
  OFFLINE: { label: 'Offline', cssClass: 'badge--offline' },
  UNKNOWN: { label: 'Unbekannt', cssClass: 'badge--neutral' },
  INTERVIEW: { label: 'Interview', cssClass: 'badge--neutral' }
};

export function healthBadge(status: ZigbeeDeviceHealthStatus): HealthBadge {
  return BADGES[status];
}

export function silentText(silentSeconds: number | null): string {
  if (silentSeconds == null) {
    return 'nie gehört';
  }
  const minutes = Math.floor(silentSeconds / 60);
  if (minutes < 1) {
    return 'zuletzt gehört vor weniger als einer Minute';
  }
  if (minutes < 60) {
    return `zuletzt gehört vor ${minutes} ${minutes === 1 ? 'Minute' : 'Minuten'}`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    const rest = minutes % 60;
    return rest === 0
      ? `zuletzt gehört vor ${hours} Std.`
      : `zuletzt gehört vor ${hours} Std. ${rest} Min.`;
  }
  const days = Math.floor(hours / 24);
  return `zuletzt gehört vor ${days} ${days === 1 ? 'Tag' : 'Tagen'}`;
}

/** Countdown aus dem Server-Ende (permitJoinEnd), nie negativ. */
export function permitJoinRemainingSeconds(permitJoinEnd: string | null, nowMs: number): number {
  if (!permitJoinEnd) {
    return 0;
  }
  const end = Date.parse(permitJoinEnd);
  if (isNaN(end)) {
    return 0;
  }
  return Math.max(0, Math.round((end - nowMs) / 1000));
}

export function bridgeEventText(event: ZigbeeBridgeEvent): string {
  const name = event.friendlyName ?? event.ieeeAddress ?? 'Unbekanntes Gerät';
  switch (event.type) {
    case 'device_joined':
      return `${name} beigetreten`;
    case 'device_interview':
      if (event.status === 'successful') {
        const detail = [event.model, event.vendor].filter(Boolean).join(', ');
        return detail ? `${name}: Interview erfolgreich (${detail})` : `${name}: Interview erfolgreich`;
      }
      if (event.status === 'failed') {
        return `${name}: Interview fehlgeschlagen`;
      }
      return `${name}: Interview läuft …`;
    case 'device_announce':
      return `${name} hat sich gemeldet`;
    case 'device_leave':
      return `${name} hat das Netz verlassen`;
  }
}
```

- [ ] **Step 4: Test laufen lassen — grün**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/zigbee-device-view.util.spec.ts'`
Expected: `Executed 9 of 9 SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/zigbee-device-view.util.ts frontend/src/app/shared/zigbee-device-view.util.spec.ts
git commit -m "feat(zigbee): Anzeige-Util - Sortierung, Badge, Stille-Text, Countdown, Ereignistexte"
```

---

### Task 15: Zigbee-Seite umbauen (Komponente, Template, Styles, Tests)

**Files:**
- Modify: `frontend/src/app/pages/zigbee/zigbee.component.ts`
- Modify: `frontend/src/app/pages/zigbee/zigbee.component.html`
- Modify: `frontend/src/app/pages/zigbee/zigbee.component.scss`
- Create: `frontend/src/app/pages/zigbee/zigbee.component.spec.ts`

- [ ] **Step 1: Fehlschlagenden Komponententest schreiben**

```ts
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { signal } from '@angular/core';
import { NEVER, Subject, of, throwError } from 'rxjs';
import { ZigbeeComponent } from './zigbee.component';
import { ZigbeeService } from '../../services/zigbee.service';
import { ZigbeeLiveService } from '../../services/zigbee-live.service';
import { AuthService } from '../../services/auth.service';
import { ZigbeeBridgeStatus, ZigbeeDevice, ZigbeeDeviceHealthStatus } from '../../models/zigbee.model';

describe('ZigbeeComponent', () => {
  let serviceSpy: jasmine.SpyObj<ZigbeeService>;
  let liveSpy: jasmine.SpyObj<ZigbeeLiveService>;
  let bridgeInfo$: Subject<ZigbeeBridgeStatus>;

  const device = (name: string, status: ZigbeeDeviceHealthStatus, overrides: Partial<ZigbeeDevice> = {}): ZigbeeDevice => ({
    id: 1, friendlyName: name, ieeeAddress: `0x${name}`, knownToBridge: true, type: 'EndDevice',
    powerSource: 'Battery', battery: true, interviewCompleted: true, supported: true,
    model: 'SNZB-03', vendor: 'SONOFF', description: null, lastBatteryPercent: 80, lastLinkQuality: 100,
    lastSeen: null, entities: [],
    health: { status, basis: 'last-message', lastHeardAt: null, silentSeconds: 60 },
    ...overrides
  });

  const bridge = (overrides: Partial<ZigbeeBridgeStatus> = {}): ZigbeeBridgeStatus => ({
    version: '2.1.3', connected: true, registryLoaded: true, permitJoin: false, permitJoinEnd: null,
    availabilityCheckEnabled: true, ...overrides
  });

  function setup(isAdmin: boolean, devices: ZigbeeDevice[], bridgeStatus: ZigbeeBridgeStatus): ComponentFixture<ZigbeeComponent> {
    serviceSpy = jasmine.createSpyObj('ZigbeeService', [
      'getDevices', 'getHealth', 'getMeasurements', 'getBridge', 'getBridgeEvents', 'getFlowReferences',
      'openPermitJoin', 'closePermitJoin', 'renameDevice', 'interviewDevice', 'configureDevice',
      'removeDevice', 'purgeLocalData'
    ]);
    serviceSpy.getDevices.and.returnValue(of(devices));
    serviceSpy.getHealth.and.returnValue(of({
      health: 'OK', healthy: true, lastMessageAt: '', silentMinutes: 0, bridgeState: null, offlineDevices: []
    }));
    serviceSpy.getMeasurements.and.returnValue(of([]));
    serviceSpy.getBridge.and.returnValue(of(bridgeStatus));
    serviceSpy.getBridgeEvents.and.returnValue(of([]));
    serviceSpy.getFlowReferences.and.returnValue(of([]));
    serviceSpy.openPermitJoin.and.returnValue(of(void 0));
    serviceSpy.closePermitJoin.and.returnValue(of(void 0));
    serviceSpy.renameDevice.and.returnValue(of(void 0));
    serviceSpy.interviewDevice.and.returnValue(of(void 0));
    serviceSpy.configureDevice.and.returnValue(of(void 0));
    serviceSpy.removeDevice.and.returnValue(of(void 0));
    serviceSpy.purgeLocalData.and.returnValue(of({ friendlyName: 'x', measurements: 0, entities: 0 }));

    bridgeInfo$ = new Subject<ZigbeeBridgeStatus>();
    liveSpy = jasmine.createSpyObj('ZigbeeLiveService', ['getLiveStream', 'getBridgeEvents', 'getBridgeInfo', 'disconnect']);
    liveSpy.getLiveStream.and.returnValue(NEVER);
    liveSpy.getBridgeEvents.and.returnValue(NEVER);
    liveSpy.getBridgeInfo.and.returnValue(bridgeInfo$.asObservable());

    TestBed.configureTestingModule({
      imports: [ZigbeeComponent],
      providers: [
        { provide: ZigbeeService, useValue: serviceSpy },
        { provide: ZigbeeLiveService, useValue: liveSpy },
        { provide: AuthService, useValue: { isAdmin: signal(isAdmin) } }
      ]
    });
    const fixture = TestBed.createComponent(ZigbeeComponent);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it('zeigt kranke Geraete vor aktiven', () => {
    const fixture = setup(true, [device('Zeta', 'ACTIVE'), device('Alpha', 'SILENT')], bridge());

    const names = Array.from(fixture.nativeElement.querySelectorAll('.device-card h2'))
      .map(el => (el as HTMLElement).textContent?.trim());
    expect(names).toEqual(['Alpha', 'Zeta']);
  });

  it('blendet das Aktionsmenue fuer Nicht-Admins aus', () => {
    const fixture = setup(false, [device('A', 'ACTIVE')], bridge());

    expect(fixture.nativeElement.querySelector('.device-card .actions-toggle')).toBeNull();
    expect(fixture.nativeElement.querySelector('.permit-join')).toBeNull();
  });

  it('zeigt das Aktionsmenue und den Anlernknopf fuer Admins', () => {
    const fixture = setup(true, [device('A', 'ACTIVE')], bridge());

    expect(fixture.nativeElement.querySelector('.device-card .actions-toggle')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.permit-join')).not.toBeNull();
  });

  it('weist auf eine abgeschaltete Verfuegbarkeitspruefung hin', () => {
    const fixture = setup(false, [], bridge({ availabilityCheckEnabled: false }));

    expect(fixture.nativeElement.querySelector('.availability-hint')?.textContent).toContain('nicht aktiviert');
  });

  it('zeigt keinen Hinweis, solange die Bridge-Info fehlt', () => {
    const fixture = setup(false, [], bridge({ availabilityCheckEnabled: null }));

    expect(fixture.nativeElement.querySelector('.availability-hint')).toBeNull();
  });

  it('rechnet den Countdown aus permitJoinEnd des Servers', fakeAsync(() => {
    const end = new Date(Date.now() + 90_000).toISOString();
    const fixture = setup(true, [], bridge({ permitJoin: true, permitJoinEnd: end }));

    expect(fixture.componentInstance.permitJoinRemaining).toBeGreaterThanOrEqual(89);
    tick(2000);
    expect(fixture.componentInstance.permitJoinRemaining).toBeLessThanOrEqual(88);
    fixture.destroy();
  }));

  it('uebernimmt ein per SSE geoeffnetes Anlernfenster', fakeAsync(() => {
    const fixture = setup(true, [], bridge());
    bridgeInfo$.next(bridge({ permitJoin: true, permitJoinEnd: new Date(Date.now() + 60_000).toISOString() }));
    fixture.detectChanges();

    expect(fixture.componentInstance.permitJoinRemaining).toBeGreaterThan(0);
    fixture.destroy();
  }));

  describe('Umbenennen-Dialog', () => {
    it('laedt die Flow-Warnung beim Oeffnen und zeigt sie an', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      serviceSpy.getFlowReferences.and.returnValue(of([{ flowId: 4, name: 'Feuer-Verdacht', enabled: true }]));

      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.detectChanges();

      expect(serviceSpy.getFlowReferences).toHaveBeenCalledWith('A');
      expect(fixture.nativeElement.querySelector('.flow-warning')?.textContent).toContain('Feuer-Verdacht');
    });

    it('meldet einen gescheiterten Referenz-Abruf statt still gruen zu sein', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      serviceSpy.getFlowReferences.and.returnValue(throwError(() => new Error('down')));

      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('.flow-warning')?.textContent).toContain('konnte nicht geprüft werden');
    });

    it('loest das Geraet beim Bestaetigen neu auf und tut nichts, wenn es fehlt', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.componentInstance.dialog!.newName = 'B';
      fixture.componentInstance.devices = [];

      fixture.componentInstance.confirmDialog();

      expect(serviceSpy.renameDevice).not.toHaveBeenCalled();
      expect(fixture.componentInstance.dialog).toBeNull();
    });

    it('benennt ueber die IEEE-Adresse um', () => {
      const fixture = setup(true, [device('A', 'ACTIVE')], bridge());
      fixture.componentInstance.openDialog('rename', fixture.componentInstance.devices[0]);
      fixture.componentInstance.dialog!.newName = 'Bewegung Büro';

      fixture.componentInstance.confirmDialog();

      expect(serviceSpy.renameDevice).toHaveBeenCalledWith('0xA', 'Bewegung Büro');
      expect(fixture.componentInstance.dialog).toBeNull();
    });
  });

  describe('Entfernen-Dialog', () => {
    it('bietet nach einem Fehlschlag das Erzwingen an', () => {
      const fixture = setup(true, [device('A', 'SILENT')], bridge());
      serviceSpy.removeDevice.and.returnValue(throwError(() => new Error('device is not responding')));
      fixture.componentInstance.openDialog('remove', fixture.componentInstance.devices[0]);

      fixture.componentInstance.confirmDialog();
      fixture.detectChanges();

      expect(serviceSpy.removeDevice).toHaveBeenCalledWith('0xA', false);
      expect(fixture.componentInstance.dialog?.error).toBe('device is not responding');
      expect(fixture.nativeElement.querySelector('.dialog__force')).not.toBeNull();

      serviceSpy.removeDevice.and.returnValue(of(void 0));
      fixture.componentInstance.confirmDialog(true);

      expect(serviceSpy.removeDevice).toHaveBeenCalledWith('0xA', true);
      expect(fixture.componentInstance.dialog).toBeNull();
    });
  });

  it('bietet fuer ein z2m-unbekanntes Geraet nur das lokale Entfernen an', () => {
    const fixture = setup(true, [device('Alt', 'SILENT', { knownToBridge: false, id: 9 })], bridge());

    const card = fixture.nativeElement.querySelector('.device-card');
    expect(card.classList).toContain('device-card--orphan');
    expect(card.querySelector('.actions-toggle')).toBeNull();
    expect(card.querySelector('.purge-button')).not.toBeNull();
  });

  it('purge loest das Geraet ueber die DB-Id auf', () => {
    const fixture = setup(true, [device('Alt', 'SILENT', { knownToBridge: false, id: 9 })], bridge());
    fixture.componentInstance.openDialog('purge', fixture.componentInstance.devices[0]);

    fixture.componentInstance.confirmDialog();

    expect(serviceSpy.purgeLocalData).toHaveBeenCalledWith(9);
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/zigbee.component.spec.ts'`
Expected: Compile-Fehler (`openDialog`, `dialog`, `permitJoinRemaining` fehlen)

- [ ] **Step 3: Komponente schreiben** (`zigbee.component.ts` komplett ersetzen)

```ts
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { Observable, Subscription } from 'rxjs';
import { ZigbeeService } from '../../services/zigbee.service';
import { ZigbeeLiveService } from '../../services/zigbee-live.service';
import { AuthService } from '../../services/auth.service';
import {
  FlowReference,
  ZigbeeBridgeEvent,
  ZigbeeBridgeStatus,
  ZigbeeDevice,
  ZigbeeHealth,
  ZigbeeLiveEvent,
  ZigbeeMeasurementType
} from '../../models/zigbee.model';
import {
  bridgeEventText,
  healthBadge,
  permitJoinRemainingSeconds,
  silentText,
  sortDevicesForDisplay
} from '../../shared/zigbee-device-view.util';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

export type ZigbeeDialogKind = 'rename' | 'remove' | 'interview' | 'configure' | 'purge';

/**
 * Haelt nur Schluessel (ieeeAddress / DB-Id), nie das Geraeteobjekt: die Liste wird alle
 * 30 s neu geladen, beim Bestaetigen wird das Geraet aus der AKTUELLEN Liste aufgeloest
 * (Regel aus confirmToggle).
 */
export interface ZigbeeDialogState {
  kind: ZigbeeDialogKind;
  friendlyName: string;
  ieeeAddress: string | null;
  deviceId: number | null;
  battery: boolean;
  entityCount: number;
  newName: string;
  references: FlowReference[] | null;
  referencesFailed: boolean;
  busy: boolean;
  error: string | null;
  /** Entfernen ist einmal gescheitert — "Erzwingen" anbieten. */
  offerForce: boolean;
}

const PERMIT_JOIN_SECONDS = 240;
const DEVICES_REFRESH_MS = 30_000;
const EVENTS_VISIBLE_AFTER_CLOSE_MS = 120_000;

/**
 * Zigbee-Geraeteverwaltung: Bridge-Status, Anlernen, Ereignisse, Gerätekarten mit
 * Health-Badge und Entitaeten, Aktionen (ADMIN) und Verlaufschart.
 */
@Component({
  selector: 'app-zigbee',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './zigbee.component.html',
  styleUrl: './zigbee.component.scss'
})
export class ZigbeeComponent implements OnInit, OnDestroy {
  private readonly zigbeeService = inject(ZigbeeService);
  private readonly liveService = inject(ZigbeeLiveService);
  private readonly authService = inject(AuthService);

  readonly isAdmin = this.authService.isAdmin;
  readonly healthBadge = healthBadge;
  readonly silentText = silentText;
  readonly bridgeEventText = bridgeEventText;

  devices: ZigbeeDevice[] = [];
  health: ZigbeeHealth | null = null;
  bridge: ZigbeeBridgeStatus | null = null;
  bridgeEvents: ZigbeeBridgeEvent[] = [];
  permitJoinRemaining = 0;
  permitJoinBusy = false;
  loadError: string | null = null;
  notice: string | null = null;
  menuOpenFor: string | null = null;
  dialog: ZigbeeDialogState | null = null;

  selectedDevice?: string;
  selectedType: ZigbeeMeasurementType = 'TEMPERATURE';
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  chartOptions: any = null;

  private readonly subscriptions = new Subscription();
  private devicesSub?: Subscription;
  private historySub?: Subscription;
  private refreshTimer?: ReturnType<typeof setInterval>;
  private countdownTimer?: ReturnType<typeof setInterval>;
  private liveReloadTimer?: ReturnType<typeof setTimeout>;
  private permitJoinClosedAt: number | null = null;
  private initialLoadDone = false;

  ngOnInit(): void {
    this.loadHealth();
    this.loadBridge();
    this.loadBridgeEvents();
    this.loadDevices();
    this.refreshTimer = setInterval(() => { this.loadDevices(); this.loadHealth(); }, DEVICES_REFRESH_MS);
    this.countdownTimer = setInterval(() => this.tickCountdown(), 1000);
    this.subscriptions.add(this.liveService.getLiveStream().subscribe({
      next: (event) => this.applyLiveEvent(event),
      error: () => { /* SSE reconnects via browser */ }
    }));
    this.subscriptions.add(this.liveService.getBridgeEvents().subscribe({
      next: (event) => this.applyBridgeEvent(event),
      error: () => { /* SSE reconnects via browser */ }
    }));
    this.subscriptions.add(this.liveService.getBridgeInfo().subscribe({
      next: (status) => this.applyBridge(status),
      error: () => { /* SSE reconnects via browser */ }
    }));
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.devicesSub?.unsubscribe();
    this.historySub?.unsubscribe();
    clearInterval(this.refreshTimer);
    clearInterval(this.countdownTimer);
    clearTimeout(this.liveReloadTimer);
    this.liveService.disconnect();
  }

  // --- Laden -----------------------------------------------------------------

  /** Fehler bewusst still: ein nicht erreichbarer Health-Endpunkt darf die Seite nicht stoeren. */
  private loadHealth(): void {
    this.zigbeeService.getHealth().subscribe({
      next: (health) => (this.health = health),
      error: () => (this.health = null)
    });
  }

  private loadBridge(): void {
    this.zigbeeService.getBridge().subscribe({
      next: (status) => this.applyBridge(status),
      error: () => { /* Bridge-Status ist Zusatzinfo; die Geraeteliste traegt die Seite */ }
    });
  }

  private loadBridgeEvents(): void {
    this.zigbeeService.getBridgeEvents().subscribe({
      next: (events) => (this.bridgeEvents = events),
      error: () => { /* Ereignisliste ist optional */ }
    });
  }

  /** Nur der Erstabruf meldet einen Fehler; spaetere behalten den letzten Stand. */
  loadDevices(): void {
    this.devicesSub?.unsubscribe();
    this.devicesSub = this.zigbeeService.getDevices().subscribe({
      next: (devices) => {
        this.initialLoadDone = true;
        this.loadError = null;
        this.devices = sortDevicesForDisplay(devices);
        if (!this.selectedDevice && devices.length > 0) {
          this.selectedDevice = this.devices[0].friendlyName;
          this.loadHistory();
        }
      },
      error: (err: Error) => {
        if (!this.initialLoadDone) {
          this.loadError = err.message;
        }
      }
    });
  }

  loadHistory(): void {
    if (!this.selectedDevice) { return; }
    this.historySub?.unsubscribe();
    this.historySub = this.zigbeeService.getMeasurements(this.selectedDevice, this.selectedType).subscribe((measurements) => {
      this.chartOptions = {
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'time' },
        yAxis: { type: 'value' },
        series: [{
          type: 'line',
          showSymbol: false,
          data: measurements.map(m => [m.measuredAt, m.value])
        }]
      };
    });
  }

  private applyBridge(status: ZigbeeBridgeStatus): void {
    const wasOpen = this.bridge?.permitJoin ?? false;
    this.bridge = status;
    if (wasOpen && !status.permitJoin) {
      this.permitJoinClosedAt = Date.now();
    }
    this.tickCountdown();
  }

  private applyBridgeEvent(event: ZigbeeBridgeEvent): void {
    this.bridgeEvents = [event, ...this.bridgeEvents].slice(0, 50);
    // Ein beigetretenes oder fertig interviewtes Geraet soll sofort als Karte erscheinen
    this.scheduleLiveReload();
  }

  private applyLiveEvent(event: ZigbeeLiveEvent): void {
    const device = this.devices.find(d => d.friendlyName === event.friendlyName);
    if (device) {
      if (event.batteryPercent != null) { device.lastBatteryPercent = event.batteryPercent; }
      if (event.linkQuality != null) { device.lastLinkQuality = event.linkQuality; }
      device.lastSeen = event.measuredAt;
    }
    this.scheduleLiveReload();
  }

  /** Entprellt: viele Sensoren melden im Minutentakt, nicht bei jedem Event neu laden. */
  private scheduleLiveReload(): void {
    clearTimeout(this.liveReloadTimer);
    this.liveReloadTimer = setTimeout(() => this.loadDevices(), 2000);
  }

  private tickCountdown(): void {
    this.permitJoinRemaining = this.bridge?.permitJoin
      ? permitJoinRemainingSeconds(this.bridge.permitJoinEnd, Date.now())
      : 0;
  }

  // --- Anzeige-Helfer ---------------------------------------------------------

  get permitJoinActive(): boolean {
    return (this.bridge?.permitJoin ?? false) && this.permitJoinRemaining > 0;
  }

  /** Ausgeklappt waehrend des Anlernens und 2 min danach. */
  get eventsExpanded(): boolean {
    if (this.permitJoinActive) { return true; }
    return this.permitJoinClosedAt != null && Date.now() - this.permitJoinClosedAt < EVENTS_VISIBLE_AFTER_CLOSE_MS;
  }

  get showAvailabilityHint(): boolean {
    return this.bridge?.availabilityCheckEnabled === false;
  }

  /**
   * Zweite Kopie der Backend-Schwellen (zigbee.device-health.*), wie die 50-W-Schwelle des
   * Modus-Checks: reine Anzeige-Daempfung je Entitaet, das Urteil je Geraet kommt vom Server.
   * Wer die Properties aendert, zieht diese Zahlen nach.
   */
  entityStale(device: ZigbeeDevice, lastUpdated: string): boolean {
    const thresholdMs = (device.battery ? 25 * 3600 : 15 * 60) * 1000;
    const updated = Date.parse(lastUpdated);
    return !isNaN(updated) && Date.now() - updated > thresholdMs;
  }

  toggleMenu(ieeeAddress: string | null): void {
    this.menuOpenFor = this.menuOpenFor === ieeeAddress ? null : ieeeAddress;
  }

  // --- Anlernen ---------------------------------------------------------------

  openPermitJoin(): void {
    this.runPermitJoin(this.zigbeeService.openPermitJoin(PERMIT_JOIN_SECONDS));
  }

  closePermitJoin(): void {
    this.runPermitJoin(this.zigbeeService.closePermitJoin());
  }

  private runPermitJoin(action: Observable<void>): void {
    this.permitJoinBusy = true;
    this.notice = null;
    action.subscribe({
      next: () => { this.permitJoinBusy = false; this.loadBridge(); },
      error: (err: Error) => { this.permitJoinBusy = false; this.notice = err.message; }
    });
  }

  // --- Dialoge ----------------------------------------------------------------

  openDialog(kind: ZigbeeDialogKind, device: ZigbeeDevice): void {
    this.menuOpenFor = null;
    this.notice = null;
    this.dialog = {
      kind,
      friendlyName: device.friendlyName,
      ieeeAddress: device.ieeeAddress,
      deviceId: device.id,
      battery: device.battery,
      entityCount: device.entities.length,
      newName: device.friendlyName,
      references: null,
      referencesFailed: false,
      busy: false,
      error: null,
      offerForce: false
    };
    if (kind === 'rename' || kind === 'remove' || kind === 'purge') {
      this.loadReferences(this.dialog);
    }
  }

  /** "Benennen" aus der Ereignisliste: das Geraet steht evtl. noch nicht in devices. */
  openRenameForEvent(event: ZigbeeBridgeEvent): void {
    if (!event.ieeeAddress) { return; }
    const known = this.devices.find(d => d.ieeeAddress === event.ieeeAddress);
    this.openDialog('rename', known ?? {
      id: null, friendlyName: event.friendlyName ?? event.ieeeAddress, ieeeAddress: event.ieeeAddress,
      knownToBridge: true, type: null, powerSource: null, battery: true, interviewCompleted: true,
      supported: null, model: event.model, vendor: event.vendor, description: null,
      lastBatteryPercent: null, lastLinkQuality: null, lastSeen: null,
      health: { status: 'UNKNOWN', basis: null, lastHeardAt: null, silentSeconds: null }, entities: []
    });
  }

  /** Beim Oeffnen geladen; ein Abruffehler wird gezeigt, nie als "keine Flows" gewertet. */
  private loadReferences(dialog: ZigbeeDialogState): void {
    this.zigbeeService.getFlowReferences(dialog.friendlyName).subscribe({
      next: (refs) => { if (this.dialog === dialog) { dialog.references = refs; } },
      error: () => { if (this.dialog === dialog) { dialog.referencesFailed = true; } }
    });
  }

  closeDialog(): void {
    if (this.dialog?.busy) { return; }
    this.dialog = null;
  }

  /**
   * Loest das Geraet aus der aktuellen Liste neu auf. Ist es verschwunden (Refresh
   * waehrend der Dialog offen war), passiert nichts — ein Fehlgriff auf ein anderes
   * Geraet waere schlimmer als ein abgebrochener Dialog.
   */
  confirmDialog(force = false): void {
    const dialog = this.dialog;
    if (!dialog || dialog.busy) { return; }
    const current = this.resolveDialogDevice(dialog);
    if (!current) {
      this.dialog = null;
      this.notice = `„${dialog.friendlyName}" ist nicht mehr in der Liste — nichts geändert.`;
      return;
    }
    const action = this.buildAction(dialog, current, force);
    if (!action) { return; }
    dialog.busy = true;
    dialog.error = null;
    action.subscribe({
      next: () => {
        dialog.busy = false;
        this.dialog = null;
        this.loadDevices();
        this.loadBridge();
      },
      error: (err: Error) => {
        dialog.busy = false;
        dialog.error = err.message;
        if (dialog.kind === 'remove' && !force) {
          dialog.offerForce = true;
        }
      }
    });
  }

  private resolveDialogDevice(dialog: ZigbeeDialogState): ZigbeeDevice | undefined {
    if (dialog.kind === 'purge') {
      return this.devices.find(d => d.id != null && d.id === dialog.deviceId && !d.knownToBridge);
    }
    return this.devices.find(d => d.ieeeAddress != null && d.ieeeAddress === dialog.ieeeAddress && d.knownToBridge);
  }

  private buildAction(dialog: ZigbeeDialogState, device: ZigbeeDevice, force: boolean): Observable<unknown> | null {
    switch (dialog.kind) {
      case 'rename': {
        const name = dialog.newName.trim();
        if (!name || name === device.friendlyName) {
          dialog.error = 'Bitte einen neuen Namen eingeben.';
          return null;
        }
        return this.zigbeeService.renameDevice(device.ieeeAddress!, name);
      }
      case 'interview':
        return this.zigbeeService.interviewDevice(device.ieeeAddress!);
      case 'configure':
        return this.zigbeeService.configureDevice(device.ieeeAddress!);
      case 'remove':
        return this.zigbeeService.removeDevice(device.ieeeAddress!, force);
      case 'purge':
        return this.zigbeeService.purgeLocalData(device.id!);
    }
  }

  dialogTitle(kind: ZigbeeDialogKind): string {
    switch (kind) {
      case 'rename': return 'Gerät umbenennen';
      case 'interview': return 'Neu interviewen?';
      case 'configure': return 'Neu konfigurieren?';
      case 'remove': return 'Aus zigbee2mqtt entfernen?';
      case 'purge': return 'Aus Household Manager entfernen?';
    }
  }

  dialogConfirmLabel(kind: ZigbeeDialogKind): string {
    switch (kind) {
      case 'rename': return 'Umbenennen';
      case 'interview': return 'Interview starten';
      case 'configure': return 'Konfigurieren';
      case 'remove': return 'Entfernen';
      case 'purge': return 'Endgültig löschen';
    }
  }
}
```

- [ ] **Step 4: Template schreiben** (`zigbee.component.html` komplett ersetzen)

```html
<section class="zigbee-page">
  <header class="page-header">
    <h1>Zigbee-Geräte</h1>
    @if (isAdmin()) {
      <div class="permit-join">
        @if (permitJoinActive) {
          <span class="permit-join__countdown">Anlernen läuft — noch {{ permitJoinRemaining }} s</span>
          <button type="button" class="ghost" [disabled]="permitJoinBusy" (click)="closePermitJoin()">Beenden</button>
        } @else {
          <button type="button" [disabled]="permitJoinBusy || !bridge?.connected" (click)="openPermitJoin()">
            Anlernen (4 min)
          </button>
        }
      </div>
    }
  </header>

  @if (health && !health.healthy) {
    <div class="outage-banner">
      <strong>Zigbee-Anbindung gestört.</strong>
      @if (health.health === 'BRIDGE_OFFLINE') {
        <span>zigbee2mqtt meldet sich als offline.</span>
      } @else {
        <span>Seit {{ health.silentMinutes }} Minuten keine Nachricht empfangen.</span>
      }
      <span>Die angezeigten Werte sind nicht aktuell.</span>
    </div>
  }

  <div class="bridge-status">
    @if (bridge) {
      <span>zigbee2mqtt {{ bridge.version ?? '?' }}</span>
      <span [class.ok]="bridge.connected" [class.bad]="!bridge.connected">
        {{ bridge.connected ? 'verbunden' : 'nicht verbunden' }}
      </span>
      @if (!bridge.registryLoaded) {
        <span>Geräteverzeichnis wird geladen …</span>
      }
    }
  </div>

  @if (showAvailabilityHint) {
    <div class="availability-hint">
      Die Verfügbarkeitsprüfung ist in zigbee2mqtt nicht aktiviert. Der Status je Gerät beruht nur auf der
      letzten empfangenen Nachricht. (Einstellungen → Verfügbarkeit in der zigbee2mqtt-Oberfläche)
    </div>
  }

  @if (notice) {
    <div class="notice">{{ notice }}</div>
  }
  @if (loadError) {
    <div class="outage-banner">{{ loadError }}</div>
  }

  <details class="bridge-events" [open]="eventsExpanded">
    <summary>Ereignisse ({{ bridgeEvents.length }})</summary>
    @if (bridgeEvents.length === 0) {
      <p class="muted">Noch keine Ereignisse seit dem Start.</p>
    }
    <ul>
      @for (event of bridgeEvents; track event.receivedAt + event.type + (event.ieeeAddress ?? '')) {
        <li>
          <span class="muted">{{ event.receivedAt | date:'HH:mm:ss' }}</span>
          <span>{{ bridgeEventText(event) }}</span>
          @if (isAdmin() && event.type === 'device_interview' && event.status === 'successful' && event.ieeeAddress) {
            <button type="button" class="link" (click)="openRenameForEvent(event)">Benennen</button>
          }
        </li>
      }
    </ul>
  </details>

  <div class="device-grid">
    @for (device of devices; track device.ieeeAddress ?? device.friendlyName) {
      <article class="device-card" [class.device-card--orphan]="!device.knownToBridge">
        <header>
          <h2>{{ device.friendlyName }}</h2>
          @if (device.knownToBridge) {
            <span class="badge" [class]="'badge ' + healthBadge(device.health.status).cssClass">
              {{ healthBadge(device.health.status).label }}
            </span>
          } @else {
            <span class="badge badge--neutral">in zigbee2mqtt nicht bekannt</span>
          }
          @if (isAdmin() && device.knownToBridge && device.ieeeAddress) {
            <button type="button" class="actions-toggle" aria-label="Aktionen" (click)="toggleMenu(device.ieeeAddress)">⋯</button>
            @if (menuOpenFor === device.ieeeAddress) {
              <div class="actions-menu" role="menu">
                <button type="button" (click)="openDialog('rename', device)">Umbenennen</button>
                <button type="button" (click)="openDialog('interview', device)">Neu interviewen</button>
                <button type="button" (click)="openDialog('configure', device)">Neu konfigurieren</button>
                <button type="button" class="danger" (click)="openDialog('remove', device)">Aus zigbee2mqtt entfernen</button>
              </div>
            }
          }
        </header>

        <p class="health-text">
          @if (device.health.status === 'INTERVIEW') {
            Interview läuft
          } @else {
            {{ silentText(device.health.silentSeconds) }}
          }
        </p>

        <p class="meta-row">
          @if (device.model) { <span>{{ device.model }}</span> }
          @if (device.vendor) { <span>{{ device.vendor }}</span> }
          @if (device.powerSource) {
            <span>{{ device.battery ? '🔋' : '🔌' }} {{ device.powerSource }}</span>
          }
          @if (device.lastBatteryPercent != null) { <span>Bat: {{ device.lastBatteryPercent }}%</span> }
          @if (device.lastLinkQuality != null) { <span>LQ: {{ device.lastLinkQuality }}</span> }
        </p>

        @if (device.entities.length > 0) {
          <ul class="entities">
            @for (entity of device.entities; track entity.entityId) {
              <li [class.stale]="entityStale(device, entity.lastUpdated)">
                <span class="entity-name">{{ entity.displayName }}</span>
                <span class="entity-state">{{ entity.state }}</span>
                <span class="entity-updated" [title]="entity.entityId">{{ entity.lastUpdated | date:'dd.MM. HH:mm' }}</span>
              </li>
            }
          </ul>
        } @else {
          <p class="muted">Noch keine Entitäten — das Gerät hat nie Daten gesendet.</p>
        }

        @if (!device.knownToBridge && isAdmin() && device.id != null) {
          <button type="button" class="purge-button danger" (click)="openDialog('purge', device)">
            Aus Household Manager entfernen
          </button>
        }
      </article>
    }
  </div>

  <div class="history">
    <h2>Verlauf</h2>
    <div class="controls">
      <select [(ngModel)]="selectedDevice" (change)="loadHistory()">
        @for (device of devices; track device.friendlyName) {
          <option [value]="device.friendlyName">{{ device.friendlyName }}</option>
        }
      </select>
      <select [(ngModel)]="selectedType" (change)="loadHistory()">
        <option value="TEMPERATURE">Temperatur</option>
        <option value="HUMIDITY">Luftfeuchte</option>
        <option value="PRESSURE">Luftdruck</option>
        <option value="CONTACT">Kontakt</option>
        <option value="OCCUPANCY">Bewegung</option>
        <option value="ILLUMINANCE">Helligkeit</option>
        <option value="WATER_LEAK">Wasserleck</option>
      </select>
    </div>
    @if (chartOptions) {
      <div echarts [options]="chartOptions" class="chart"></div>
    }
  </div>

  @if (dialog; as d) {
    <div class="dialog-backdrop" (click)="closeDialog()">
      <div class="dialog" role="dialog" aria-modal="true" (click)="$event.stopPropagation()">
        <h3 class="dialog__title">{{ dialogTitle(d.kind) }}</h3>
        <p class="dialog__text"><strong>{{ d.friendlyName }}</strong></p>

        @if (d.kind === 'rename') {
          <label class="dialog__field">
            Neuer Name
            <input type="text" [(ngModel)]="d.newName" maxlength="64" [disabled]="d.busy">
          </label>
          <p class="dialog__hint">Entitäten bekommen neue IDs; Flows und Dashboard-Kacheln müssen nachgezogen werden.</p>
        }
        @if ((d.kind === 'interview' || d.kind === 'configure' || d.kind === 'remove') && d.battery) {
          <p class="dialog__hint">Batterie-Gerät: vorher wecken (z. B. Reset-Taste kurz drücken), sonst schlägt die Aktion fehl.</p>
        }
        @if (d.kind === 'purge') {
          <p class="dialog__text">Löscht alle Messwerte und {{ d.entityCount }} Entität(en) dieses Geräts endgültig.</p>
        }

        @if (d.kind === 'rename' || d.kind === 'remove' || d.kind === 'purge') {
          <div class="flow-warning">
            @if (d.referencesFailed) {
              <span>Flow-Verwendung konnte nicht geprüft werden.</span>
            } @else if (d.references === null) {
              <span class="muted">Prüfe Flow-Verwendung …</span>
            } @else if (d.references.length === 0) {
              <span class="muted">Wird in keinem Flow verwendet.</span>
            } @else {
              <span>Wird verwendet in:</span>
              <ul>
                @for (ref of d.references; track ref.flowId) {
                  <li>{{ ref.name }} ({{ ref.enabled ? 'aktiv' : 'inaktiv' }})</li>
                }
              </ul>
            }
          </div>
        }

        @if (d.error) {
          <p class="dialog__error">{{ d.error }}</p>
        }

        <div class="dialog__actions">
          <button type="button" class="dialog__cancel" [disabled]="d.busy" (click)="closeDialog()">Abbrechen</button>
          @if (d.kind === 'remove' && d.offerForce) {
            <button type="button" class="dialog__confirm dialog__force" [disabled]="d.busy" (click)="confirmDialog(true)">
              Erzwingen
            </button>
          }
          <button type="button" class="dialog__confirm"
                  [class.dialog__confirm--danger]="d.kind === 'remove' || d.kind === 'purge'"
                  [disabled]="d.busy" (click)="confirmDialog()">
            {{ d.busy ? 'Bitte warten …' : dialogConfirmLabel(d.kind) }}
          </button>
        </div>
      </div>
    </div>
  }
</section>
```

**Hinweis zum „Benennen"-Link in der Ereignisliste:** `openRenameForEvent` sucht das Gerät zuerst in `devices` (per IEEE) und baut sonst ein minimales Geräteobjekt aus dem Ereignis — das frisch beigetretene Gerät ist möglicherweise noch nicht in der Liste. Beim Bestätigen löst `confirmDialog` gegen `devices` auf; steht das Gerät dort noch nicht, sagt der Hinweis „nicht mehr in der Liste" — dann 2 s warten (Live-Reload) und erneut.

- [ ] **Step 5: Styles** (`zigbee.component.scss` komplett ersetzen)

```scss
.zigbee-page {
  padding: 1.5rem;

  .page-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    flex-wrap: wrap;

    h1 { margin: 0; }
  }

  .permit-join {
    display: flex;
    align-items: center;
    gap: 0.75rem;

    &__countdown { font-weight: 600; color: #2e7d32; }
  }

  button {
    padding: 0.45rem 0.9rem;
    border-radius: 6px;
    border: 1px solid #1976d2;
    background: #1976d2;
    color: #fff;
    cursor: pointer;
    font-weight: 600;

    &:disabled { opacity: 0.5; cursor: default; }
    &.ghost { background: transparent; color: #1976d2; }
    &.danger { background: #c62828; border-color: #c62828; }
    &.link { background: none; border: none; color: #1976d2; padding: 0 0.25rem; font-weight: 500; text-decoration: underline; }
  }

  .outage-banner, .availability-hint, .notice {
    margin: 1rem 0;
    padding: 0.75rem 1rem;
    border-radius: 8px;
  }
  .outage-banner { border: 1px solid #d9534f; background: rgba(217, 83, 79, 0.12); color: #d9534f; display: flex; gap: 0.5rem; flex-wrap: wrap; }
  .availability-hint { border: 1px solid #f0ad4e; background: rgba(240, 173, 78, 0.15); color: #8a5a00; }
  .notice { border: 1px solid #90a4ae; background: #eceff1; color: #37474f; }

  .bridge-status {
    display: flex; gap: 1rem; margin: 0.75rem 0; font-size: 0.85rem; color: #666;
    .ok { color: #2e7d32; }
    .bad { color: #c62828; }
  }

  .bridge-events {
    margin: 1rem 0 1.5rem;
    border: 1px solid #e0e0e0;
    border-radius: 8px;
    padding: 0.5rem 1rem;

    summary { cursor: pointer; font-weight: 600; }
    ul { list-style: none; padding: 0; margin: 0.5rem 0 0; max-height: 14rem; overflow-y: auto; }
    li { display: flex; gap: 0.75rem; align-items: baseline; padding: 0.15rem 0; font-size: 0.9rem; }
  }

  .muted { color: #999; font-size: 0.85rem; }

  .device-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
    gap: 1rem;
    margin-bottom: 2rem;
  }

  .device-card {
    border: 1px solid #e0e0e0;
    border-radius: 8px;
    padding: 1rem;
    position: relative;

    &--orphan { opacity: 0.65; border-style: dashed; }

    header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex-wrap: wrap;

      h2 { font-size: 1rem; margin: 0; flex: 1 1 auto; }
    }

    .badge {
      font-size: 0.7rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.03em;
      padding: 0.15rem 0.5rem;
      border-radius: 999px;

      &--active { background: #e8f5e9; color: #2e7d32; }
      &--silent { background: #fff8e1; color: #b26a00; }
      &--offline { background: #ffebee; color: #c62828; }
      &--neutral { background: #eceff1; color: #546e7a; }
    }

    .actions-toggle {
      background: transparent; border: 1px solid #ccc; color: #444; padding: 0 0.5rem; line-height: 1.4;
    }

    .actions-menu {
      position: absolute; right: 1rem; top: 2.75rem; z-index: 10;
      display: flex; flex-direction: column; gap: 0.25rem;
      background: #fff; border: 1px solid #ddd; border-radius: 6px; padding: 0.4rem;
      box-shadow: 0 6px 20px rgba(0, 0, 0, 0.15);

      button { text-align: left; background: transparent; color: #222; border: none; padding: 0.35rem 0.6rem; font-weight: 500; }
      button:hover { background: #f5f5f5; }
      button.danger { color: #c62828; }
    }

    .health-text { margin: 0.4rem 0 0; font-size: 0.8rem; color: #666; }
    .meta-row { display: flex; flex-wrap: wrap; gap: 0.6rem; margin: 0.5rem 0 0; font-size: 0.8rem; color: #666; }

    .entities {
      list-style: none; padding: 0; margin: 0.75rem 0 0;

      li {
        display: grid; grid-template-columns: 1fr auto auto; gap: 0.5rem; padding: 0.15rem 0; font-size: 0.85rem;
        &.stale { opacity: 0.5; }
        .entity-state { font-weight: 600; }
        .entity-updated { color: #999; font-size: 0.75rem; }
      }
    }

    .purge-button { margin-top: 0.75rem; width: 100%; }
  }

  .history {
    .controls { display: flex; gap: 0.5rem; margin-bottom: 1rem; select { padding: 0.35rem 0.5rem; } }
    .chart { width: 100%; height: 360px; }
  }

  .dialog-backdrop {
    position: fixed; inset: 0; background: rgba(0, 0, 0, 0.5);
    display: flex; align-items: center; justify-content: center; z-index: 1000;
  }

  .dialog {
    background: #fff; border-radius: 12px; padding: 1.5rem; max-width: 26rem; width: calc(100% - 2rem);
    box-shadow: 0 10px 30px rgba(0, 0, 0, 0.25);

    &__title { margin: 0 0 0.5rem; font-size: 1.1rem; }
    &__text { margin: 0 0 0.75rem; color: #555; }
    &__hint { margin: 0 0 0.75rem; font-size: 0.85rem; color: #8a5a00; }
    &__error { margin: 0.5rem 0; color: #c62828; font-size: 0.9rem; }
    &__field { display: flex; flex-direction: column; gap: 0.3rem; margin-bottom: 0.75rem; font-size: 0.85rem;
      input { padding: 0.45rem 0.6rem; border: 1px solid #ccc; border-radius: 6px; font-size: 1rem; } }
    &__actions { display: flex; gap: 0.5rem; justify-content: flex-end; margin-top: 1rem; flex-wrap: wrap; }
    &__cancel { background: #eceff1; border-color: #eceff1; color: #222; }
    &__confirm--danger, &__force { background: #c62828; border-color: #c62828; }

    .flow-warning {
      margin: 0.5rem 0; padding: 0.5rem 0.75rem; border-radius: 6px; background: #fff8e1; font-size: 0.85rem;
      ul { margin: 0.25rem 0 0; padding-left: 1.2rem; }
    }
  }
}
```

- [ ] **Step 6: Typprüfung und Komponententest**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm test -- --watch=false --browsers=ChromeHeadless --include='**/zigbee.component.spec.ts'`
Expected: keine TS-Fehler; `Executed 14 of 14 SUCCESS`

- [ ] **Step 7: Gesamten Frontend-Testlauf**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: nur die 3 Baseline-Fails (`AppComponent` ×2, `HeroComponent`)

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/pages/zigbee
git commit -m "feat(zigbee): Seite mit Bridge-Status, Anlernen, Ereignissen, Health-Badges, Entitaeten und Verwaltungsdialogen"
```

---

### Task 16: Spec nachziehen, CLAUDE.md, Memory

**Files:**
- Modify: `docs/superpowers/specs/2026-09-15-zigbee-geraeteverwaltung-design.md`
- Modify: `CLAUDE.md` (Abschnitt nach „Zigbee-Ausfallerkennung und MQTT-Härtung")
- Create: `C:\Users\bened\.claude\projects\C--Users-bened-IdeaProjects-Household-Manager\memory\zigbee-geraeteverwaltung.md` + Zeile in `MEMORY.md`

- [ ] **Step 1: Spec-Abweichung eintragen** — in der Aktionstabelle von Abschnitt 3 die Zeile „Aus HM entfernen" auf `DELETE /devices/local/{id}` ändern und im Absatz darunter ergänzen: „adressiert über die DB-Id, weil Bestandszeilen keine IEEE-Adresse tragen"; `GET /devices/{ieee}/references` → `GET /flow-references?friendlyName=` (Abschnitte 2 und 3). Security-Liste um `DELETE /v1/zigbee/devices/local/*` ergänzen.

- [ ] **Step 2: CLAUDE.md-Abschnitt** einfügen (nach dem Zigbee-Ausfallerkennungs-Abschnitt):

```markdown
### Zigbee-Geräteverwaltung (Status je Gerät, Anlernen, Umbenennen, Entfernen)
- Spec: `docs/superpowers/specs/2026-09-15-zigbee-geraeteverwaltung-design.md`. Anlass: vier Sensoren standen wochenlang mit altem Wert auf der Seite und sahen aus wie lebende
- **Wahrheit der Geräteliste ist zigbee2mqtt's `bridge/devices`** (`ZigbeeDeviceRegistry`, rein im Speicher, leer bis der Broker die retained Topics nachspielt — `registryLoaded` macht das Fenster sichtbar). Unsere `zigbee_device`-Tabelle liefert nur Batterie/LQ/`lastSeen`; Geräte nur dort erscheinen als `knownToBridge: false` statt zu verschwinden
- **`ZigbeeDeviceHealthResolver` ist die einzige Definition von „lebt das Gerät"** (Muster `TractiveHomeResolver`). Reihenfolge: z2m `offline` ⇒ OFFLINE; Interview offen ⇒ INTERVIEW; nie gehört ⇒ UNKNOWN (nie ein geratenes „aktiv"); still ≥ Schwelle ⇒ SILENT; sonst ACTIVE. Schwelle je Stromquelle in `application.properties` (`zigbee.device-health.*`): Batterie 25 h (z2m-Default, **unverifiziert am eigenen Park**), Netz 15 min (= Watchdog-Schwelle). Retained Nachrichten setzen die Geräte-Uhr nicht; nach Neustart zählt `lastSeen` aus der DB weiter
- **Ein „Ping" von Batterie-Geräten gibt es nicht** — schlafende Endgeräte, keine API dafür. Ob z2m's Verfügbarkeitsprüfung aktiv ist, kommt aus `bridge/info` (`availabilityCheckEnabled`, null solange keine Info kam); ist sie aus, zeigt die Seite den Hinweis und der Resolver läuft ohne Regel 1
- **Erstmals publiziert der MQTT-Client** (`ZigbeeBridgeCommands`, `ZigbeeBridgeRequestService`): Request auf `bridge/request/<x>` mit `transaction`-UUID, Antwort auf `bridge/response/<x>`, **10-s-Timeout** (sonst hinge der HTTP-Thread, wenn z2m weg ist), kein Publish ohne Verbindung (HiveMQ würde es puffern und ein Anlernfenster Minuten später „nachholen"). z2m-Fehler ⇒ 400 mit z2m-Text, Timeout/keine Verbindung ⇒ 502, **nie 401**. `permit_join` wird versionsabhängig gebaut (1.x `value`+`time`, 2.x nur `time`); `interview_completed` (1.x) und `interview_state` (2.x) werden beide gelesen
- **Zirkel im Container:** `ZigbeeMqttConfig` implementiert `ZigbeeBridgeCommands`, das Request- und Query-Service brauchen; deshalb holt die Config beide per `ObjectProvider`, nicht im Konstruktor
- **Geräte werden über die IEEE-Adresse adressiert** (Umbenennen ändert den Namen; z2m erlaubt `/` darin). Ausnahme: „Aus Household Manager entfernen" läuft über die **DB-Id** (`DELETE /v1/zigbee/devices/local/{id}`), weil Bestandszeilen keine IEEE tragen — und **nur** für Geräte, die z2m nicht mehr kennt (409 sonst); löscht Messwerte, Zeile, Entitäten **und** `entity_tile_visibility`-Zeilen (kein FK, sonst verwaiste NEVER-Regel). Flow-Referenzen per `GET /v1/zigbee/flow-references?friendlyName=` (String-Suche im Flow-JSON, falsch-positiv akzeptiert)
- **Umbenennen migriert keine Entitäten**: alte bleiben stehen (Zeile wird `knownToBridge: false`, per Purge aufräumbar), neue entstehen mit der nächsten Nachricht. Der Dialog warnt mit Flow-Namen, blockiert nicht
- `remove` ohne `force` braucht das Gerät wach; bei totem Batterie-Sensor schlägt es fehl und der Dialog bietet **„Erzwingen"** als zweite Stufe an — nie als Default (auf ein lebendes Gerät angewendet funkt es weiter und taucht beim Anlernen wieder auf)
- Security: schreibende Pfade sind **methodenspezifische** ADMIN-Matcher (`GET /devices/*/measurements` liegt darunter und bleibt KIOSK); `SecurityRulesTest` hat je Zeile einen MEMBER-Test. Audit `zigbee.permit-join.open/close`, `zigbee.device.rename/interview/configure/remove/purge`, jeweils nach Erfolg
- SSE `/v1/zigbee/live` trägt jetzt drei benannte Ereignisse (`live`, `bridge-event`, `bridge-info`); der Frontend-Service teilt eine `EventSource`, ein Unsubscribe entfernt nur seinen Listener
- Seite: kranke Geräte vor aktiven; Badge-Mapping exhaustiv über den Status-Typ (kein `default`); Dialoge lösen das Gerät beim Bestätigen aus der aktuellen Liste neu auf (Regel `confirmToggle`); Countdown aus `permitJoinEnd` des Servers, nicht aus einem lokalen Timer
- **Bewusste Grenzen:** kein Flow-Trigger auf Geräte-Gesundheit (Registry im Speicher; der Resolver wäre die eine Stelle für eine spätere `event.zigbee_device_health`-Entität); z2m-Optionen je Gerät bleiben in der z2m-Oberfläche; Ereignisliste überlebt keinen Neustart
```

- [ ] **Step 3: Memory-Datei** anlegen (Frontmatter wie die übrigen, `type: project`): Stand „gebaut 2026-09-15 auf `feature/zigbee-geraeteverwaltung`, PROD-Deploy und Realtest offen; permit_join/rename/remove nie gegen die echte Bridge gelaufen; 25-h-Schwelle unverifiziert; offen, ob das HA-Add-on 1.x oder 2.x ist". Zeile in `MEMORY.md` ergänzen.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-09-15-zigbee-geraeteverwaltung-design.md CLAUDE.md
git commit -m "docs(zigbee): Geraeteverwaltung in CLAUDE.md, Spec-Abweichung DB-Id nachgezogen"
```

---

### Task 17: Realtest gegen die lokale Umgebung

Kein Code. Voraussetzungen: `ZIGBEE_MQTT_PASSWORD` und **`ZIGBEE_MQTT_CLIENT_ID=household-manager-zigbee-dev`** (sonst kickt Dev die PROD-Verbindung, siehe CLAUDE.md „Dev-Falle").

- [ ] **Step 1: Backend starten** (IntelliJ-Run-Config, siehe Memory `telegram-lokal-abgeschaltet`) und Log beobachten: `zigbee2mqtt-Verzeichnis: N Geraete` muss binnen Sekunden nach `Zigbee MQTT subscribed` erscheinen. Fehlt die Zeile: `bridge/devices` kommt nicht retained oder das Payload ist unlesbar (Warnung im Log) — Payload dann per `mosquitto_sub -h 192.168.1.150 -u addons -P … -t zigbee2mqtt/bridge/devices -C 1` sichern und den Parser-Test damit füttern.

- [ ] **Step 2: Frontend starten** (`preview_start` mit `frontend`), `/zigbee` öffnen. Prüfen: Version und „verbunden" im Kopf; Hinweis zur Verfügbarkeitsprüfung ja/nein (Antwort auf die offene Frage aus dem Brainstorming — ins Memory); die vier verdächtigen Geräte (`Motion Büro`, `Motion Treppenhaus`, `Motion Küche`, `Sensor Gas`) stehen als SILENT oder OFFLINE oben; Entitäten mit `lastUpdated`.

- [ ] **Step 3: Anlernen-Fenster kurz öffnen und schließen** (Admin): Countdown erscheint, `bridge/info` per SSE aktualisiert ihn, „Beenden" schließt. Audit-Log zeigt `zigbee.permit-join.open/close`. **Nichts anlernen**, wenn kein neues Gerät gebraucht wird.

- [ ] **Step 4: Neu-Interview auf einen Netz-Router** (falls vorhanden) — Antwort binnen 10 s; auf einen schlafenden Batterie-Sensor — erwartet 502 nach 10 s mit Klartext im Dialog.

- [ ] **Step 5: Kein Rename/Remove im Realtest** ohne Bedarf — beide ändern PROD-sichtbare Namen/Geräte auf dem gemeinsamen Broker. Wer eines der vier toten Geräte tatsächlich entfernen will: erst `remove` (erwartet Fehler bei totem Gerät), dann „Erzwingen", dann Karte als „in zigbee2mqtt nicht bekannt", dann Purge.

- [ ] **Step 6: Backend-Gesamtlauf und Merge-Vorbereitung**

Run: `cd backend && mvn -q test`
Expected: nur `contextLoads` und `HealthControllerTest` rot (Baseline ohne Test-DB)

Danach `superpowers:finishing-a-development-branch`.
