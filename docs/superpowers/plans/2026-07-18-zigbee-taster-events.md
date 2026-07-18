# Zigbee-Taster als Event-Entitäten — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zigbee-Taster (zigbee2mqtt `action`-Feld) als EVENT-Entitäten im Entities-View sichtbar machen und als Flow-Trigger nutzbar, der bei **jedem** Tastendruck feuert.

**Architecture:** Eigener Event-Pfad neben der Zustands-Pipeline: Parser liest `action`, Mapper erzeugt eine EVENT-Entität, `EntityStateService.reportEvent` publiziert bei jedem Ereignis ein `EntityEventFired` (getrennt vom `EntityStateChangedEvent`), ein neuer Trigger-Node `entity-event-trigger` konsumiert es ohne Flanken-Verhalten. Spec: `docs/superpowers/specs/2026-07-18-zigbee-taster-events-design.md`.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 (Backend), Angular 19 (Frontend), JUnit 5 + Mockito + AssertJ.

**Build-Umgebung (WICHTIG):** Vor jedem Maven-Aufruf `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (Default-JDK der Maschine ist 17). Maven aus `backend/` aufrufen, es gibt kein `mvnw`. Die Tests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal immer fehl (Test-DB nicht erreichbar) — das ist vorbestehend und zu ignorieren.

---

### Task 1: Parser — `action`-Feld

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/parser/ParsedZigbeeMessage.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/service/ZigbeeMessageParser.java`
- Modify (Compile-Fix): `backend/src/test/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapperTest.java`
- Test: `backend/src/test/java/com/household/manager/zigbee/service/ZigbeeMessageParserTest.java`

- [ ] **Step 1: Failing Tests schreiben**

In `ZigbeeMessageParserTest` ergänzen (Stil der bestehenden Tests, AssertJ):

```java
@Test
void parsesActionFromButtonMessage() {
    String payload = "{\"action\":\"single\",\"battery\":100,\"linkquality\":90}";

    Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", payload);

    assertThat(result).isPresent();
    assertThat(result.get().action()).isEqualTo("single");
    assertThat(result.get().friendlyName()).isEqualTo("Flur-Taster");
}

@Test
void messageWithOnlyActionIsValid() {
    Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", "{\"action\":\"double\"}");

    assertThat(result).isPresent();
    assertThat(result.get().action()).isEqualTo("double");
    assertThat(result.get().measurements()).isEmpty();
}

@Test
void ignoresEmptyLegacyActionReset() {
    // zigbee2mqtt-Legacy-Verhalten: nach der Aktion folgt {"action": ""} als Reset.
    Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", "{\"action\":\"\"}");

    assertThat(result).isEmpty();
}

@Test
void discardsActionFromRetainedMessage() {
    // Retained-Nachrichten sind alte Zustände vom Broker — ein Backend-Neustart
    // darf den letzten Tastendruck nicht "nachfeuern".
    String payload = "{\"action\":\"single\",\"battery\":100,\"linkquality\":90}";

    Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Flur-Taster", payload, true);

    assertThat(result).isPresent();
    assertThat(result.get().action()).isNull();
}

@Test
void nonButtonMessageHasNullAction() {
    String payload = "{\"battery\":100,\"contact\":false,\"linkquality\":80}";

    Optional<ParsedZigbeeMessage> result = parser.parse("zigbee2mqtt/Haustuer", payload);

    assertThat(result).isPresent();
    assertThat(result.get().action()).isNull();
}
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=ZigbeeMessageParserTest
```
Erwartet: COMPILATION ERROR (`action()` und die 3-Arg-`parse` existieren noch nicht).

- [ ] **Step 3: Record erweitern**

`ParsedZigbeeMessage.java` — neue Komponente `action` (letzte Position):

```java
package com.household.manager.zigbee.parser;

import java.util.List;

/**
 * Ergebnis des Parsens einer zigbee2mqtt-Gerätenachricht.
 * {@code action} ist die Taster-Aktion (z. B. "single", "double", "hold");
 * {@code null}, wenn die Nachricht keine (oder eine leere) Aktion enthält.
 */
public record ParsedZigbeeMessage(
        String friendlyName,
        Integer batteryPercent,
        Integer linkQuality,
        List<ZigbeeMeasurementValue> measurements,
        String action
) {
}
```

- [ ] **Step 4: Parser anpassen**

In `ZigbeeMessageParser.java`:

Die bestehende `parse(String, String)`-Methode wird zur Delegation (Signatur bleibt für alle bestehenden Aufrufer erhalten), die Logik wandert in eine 3-Arg-Variante:

```java
public Optional<ParsedZigbeeMessage> parse(String topic, String payload) {
    return parse(topic, payload, false);
}

/**
 * @param retained MQTT-Retained-Flag; Aktionen aus retained Nachrichten werden
 *                 verworfen, damit ein Reconnect keinen alten Tastendruck nachfeuert
 */
public Optional<ParsedZigbeeMessage> parse(String topic, String payload, boolean retained) {
```

Im Methodenrumpf nach dem Einlesen von `battery`/`linkQuality` ergänzen:

```java
        String action = retained ? null : actionOrNull(root);
```

Das Leer-Kriterium am Ende erweitern und den Konstruktor-Aufruf anpassen:

```java
        if (measurements.isEmpty() && battery == null && linkQuality == null && action == null) {
            return Optional.empty();
        }
        return Optional.of(new ParsedZigbeeMessage(friendlyName, battery, linkQuality, measurements, action));
```

Neue private Methode:

```java
    private String actionOrNull(JsonNode root) {
        JsonNode node = root.get("action");
        return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText() : null;
    }
```

- [ ] **Step 5: Compile-Fix in `ZigbeeEntityMapperTest`**

Alle sechs `new ParsedZigbeeMessage(...)`-Aufrufe bekommen `null` als fünftes Argument, z. B.:

```java
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Wohnzimmer Sensor", 87, 120,
                List.of(new ZigbeeMeasurementValue(MeasurementType.TEMPERATURE, new BigDecimal("21.5"), "°C")),
                null);
```

(Betrifft die Tests `mapsNumericMeasurementToSensorEntity`, `mapsClosedContactToOff`, `mapsOpenContactToOn`, `mapsZeroBinaryValueToOff`, `mapsOccupancyDetectedToOn`, `mapsMultipleMeasurementsToMultipleEntities`.)

- [ ] **Step 6: Tests laufen lassen — müssen bestehen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest='ZigbeeMessageParserTest,ZigbeeEntityMapperTest'
```
Erwartet: PASS (alle Tests grün).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee backend/src/test/java/com/household/manager
git commit -m "feat(zigbee): Taster-Aktionen im MQTT-Parser lesen"
```

---

### Task 2: `EntityDomain.EVENT`, `EntityEventFired`, `EntityStateWriter.upsertEvent`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntityDomain.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityEventFired.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntityStateWriter.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityStateWriterTest.java`

Kein Liquibase-Changeset nötig: die `domain`-Spalte speichert Enum-Namen als String.

- [ ] **Step 1: Failing Tests schreiben**

In `EntityStateWriterTest` ergänzen:

```java
    private EntityStateUpdate eventUpdate(String action) {
        return EntityStateUpdate.builder()
                .entityId("event.zigbee_flur_taster_action")
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Flur-Taster")
                .friendlyName("Flur-Taster Taster")
                .state(action)
                .attributes(Map.of("deviceClass", "button"))
                .build();
    }

    @Test
    void upsertEventCreatesEntityOnFirstPress() {
        when(repository.findByEntityId("event.zigbee_flur_taster_action")).thenReturn(Optional.empty());

        EntityEventFired event = writer.upsertEvent(eventUpdate("double"));

        assertEquals("double", event.action());
        assertEquals("event.zigbee_flur_taster_action", event.entityId());
        ArgumentCaptor<EntityState> captor = ArgumentCaptor.forClass(EntityState.class);
        verify(repository).save(captor.capture());
        assertEquals("double", captor.getValue().getState());
        assertEquals(EntityDomain.EVENT, captor.getValue().getDomain());
        assertNotNull(captor.getValue().getLastChanged());
    }

    @Test
    void upsertEventAlwaysFiresAndBumpsLastChangedEvenWhenActionUnchanged() {
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        EntityState existing = EntityState.builder()
                .id(2L)
                .entityId("event.zigbee_flur_taster_action")
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Flur-Taster")
                .friendlyName("Flur-Taster Taster")
                .state("single")
                .lastChanged(earlier)
                .lastUpdated(earlier)
                .build();
        when(repository.findByEntityId("event.zigbee_flur_taster_action")).thenReturn(Optional.of(existing));

        EntityEventFired event = writer.upsertEvent(eventUpdate("single"));

        assertEquals("single", event.action());
        assertTrue(existing.getLastChanged().isAfter(earlier));
        assertTrue(existing.getLastUpdated().isAfter(earlier));
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=EntityStateWriterTest
```
Erwartet: COMPILATION ERROR (`EntityDomain.EVENT`, `EntityEventFired`, `upsertEvent` fehlen).

- [ ] **Step 3: `EntityDomain.EVENT` ergänzen**

In `EntityDomain.java` nach `BINARY_SENSOR` einfügen:

```java
    /** Zustandsloses Ereignis (z. B. Zigbee-Taster); State = letzte Aktion. */
    EVENT,
```

(`isManualHelper()` und `idPrefix()` funktionieren unverändert; `idPrefix()` liefert `"event"`.)

- [ ] **Step 4: `EntityEventFired` anlegen**

```java
package com.household.manager.entitystate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Wird bei JEDEM Ereignis einer EVENT-Entität publiziert (z. B. Zigbee-Tastendruck) —
 * auch wenn die Aktion identisch zur vorherigen ist. Gegenstück zu
 * {@link EntityStateChangedEvent}, das nur Wertänderungen meldet.
 */
public record EntityEventFired(
        String entityId,
        String action,
        Map<String, Object> attributes,
        LocalDateTime timestamp
) {
}
```

- [ ] **Step 5: `upsertEvent` im Writer implementieren**

In `EntityStateWriter.java` nach `upsert(...)` ergänzen:

```java
    /**
     * Upsert für Ereignis-Entitäten (Domain EVENT): setzt den State auf die Aktion
     * und lastChanged bei JEDEM Ereignis (auch bei gleicher Aktion), damit der
     * Zeitpunkt des letzten Tastendrucks sichtbar bleibt.
     *
     * @return Event für jeden Aufruf — Ereignisse sind nie "unverändert"
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EntityEventFired upsertEvent(EntityStateUpdate update) {
        LocalDateTime now = LocalDateTime.now();
        String action = update.state() != null ? update.state() : STATE_UNKNOWN;

        EntityState entity = repository.findByEntityId(update.entityId())
                .orElseGet(() -> EntityState.builder()
                        .entityId(update.entityId())
                        .domain(update.domain())
                        .source(update.source())
                        .sourceRef(update.sourceRef())
                        .state(STATE_UNKNOWN)
                        .lastChanged(now)
                        .build());

        entity.setFriendlyName(update.friendlyName());
        entity.setAttributes(serializeAttributes(update.attributes()));
        entity.setState(action);
        entity.setLastChanged(now);
        entity.setLastUpdated(now);
        repository.save(entity);

        return new EntityEventFired(update.entityId(), action, update.attributes(), now);
    }
```

- [ ] **Step 6: Tests laufen lassen — müssen bestehen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=EntityStateWriterTest
```
Erwartet: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate backend/src/test/java/com/household/manager/entitystate
git commit -m "feat(entitystate): EVENT-Domain und EntityEventFired mit upsertEvent"
```

---

### Task 3: `EntityStateService.reportEvent`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntityStateService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityStateServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

In `EntityStateServiceTest` ergänzen (der bestehende `update()`-Helper wird wiederverwendet — der Writer ist gemockt, die Domain im Update ist hier irrelevant):

```java
    @Test
    void reportEventAlwaysPublishes() {
        EntityEventFired event = new EntityEventFired(
                "event.zigbee_flur_taster_action", "single", Map.of(), LocalDateTime.now());
        when(writer.upsertEvent(any())).thenReturn(event);

        service.reportEvent(update());

        verify(eventPublisher).publishEvent(event);
    }

    @Test
    void reportEventSwallowsWriterExceptionsSoCallerIsNeverBroken() {
        when(writer.upsertEvent(any())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> service.reportEvent(update()));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reportEventSwallowsPublisherExceptions() {
        when(writer.upsertEvent(any())).thenReturn(new EntityEventFired(
                "event.zigbee_flur_taster_action", "single", Map.of(), LocalDateTime.now()));
        doThrow(new RuntimeException("listener broken")).when(eventPublisher).publishEvent(any(Object.class));

        assertDoesNotThrow(() -> service.reportEvent(update()));
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=EntityStateServiceTest
```
Erwartet: COMPILATION ERROR (`reportEvent` fehlt).

- [ ] **Step 3: `reportEvent` implementieren**

In `EntityStateService.java` nach `reportState(...)` ergänzen und `publishSafely` auf `Object` verallgemeinern (ein Publikationspfad für beide Event-Typen):

```java
    /**
     * Meldet ein Ereignis einer EVENT-Entität (z. B. Zigbee-Tastendruck).
     * Publiziert bei JEDEM Aufruf ein {@link EntityEventFired} — auch bei
     * wiederholt gleicher Aktion. Gleiche Fehlertoleranz wie {@link #reportState}.
     */
    public void reportEvent(EntityStateUpdate update) {
        try {
            EntityEventFired event = writer.upsertEvent(update);
            publishSafely(event);
        } catch (Exception ex) {
            log.warn("Failed to report entity event for {}: {}", update.entityId(), ex.getMessage());
        }
    }
```

Bestehende Methode anpassen (nur Parametertyp und Log-Text-Neutralität):

```java
    private void publishSafely(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception ex) {
            log.warn("Entity event listener failed: {}", ex.getMessage());
        }
    }
```

Hinweis: der Aufruf in `reportState` (`event.ifPresent(this::publishSafely)`) kompiliert unverändert weiter.

- [ ] **Step 4: Tests laufen lassen — müssen bestehen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=EntityStateServiceTest
```
Erwartet: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate backend/src/test/java/com/household/manager/entitystate
git commit -m "feat(entitystate): reportEvent-Pfad fuer Ereignis-Entitaeten"
```

---

### Task 4: `ZigbeeEntityMapper.mapAction`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapper.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapperTest.java`

- [ ] **Step 1: Failing Tests schreiben**

In `ZigbeeEntityMapperTest` ergänzen (Import `static org.junit.jupiter.api.Assertions.assertTrue;` hinzufügen):

```java
    @Test
    void mapsActionToEventEntity() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage("Flur-Taster", 100, 90, List.of(), "single");

        EntityStateUpdate update = mapper.mapAction(message).orElseThrow();

        assertEquals("event.zigbee_flur_taster_action", update.entityId());
        assertEquals(EntityDomain.EVENT, update.domain());
        assertEquals("single", update.state());
        assertEquals("button", update.attributes().get("deviceClass"));
        assertEquals(100, update.attributes().get("batteryPercent"));
        assertEquals(90, update.attributes().get("linkQuality"));
        assertEquals("Flur-Taster Taster", update.friendlyName());
        assertEquals("Flur-Taster", update.sourceRef());
    }

    @Test
    void mapActionIsEmptyWithoutAction() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage("Haustür", null, null, List.of(), null);

        assertTrue(mapper.mapAction(message).isEmpty());
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=ZigbeeEntityMapperTest
```
Erwartet: COMPILATION ERROR (`mapAction` fehlt).

- [ ] **Step 3: `mapAction` implementieren**

In `ZigbeeEntityMapper.java` (Import `java.util.Optional` ergänzen) nach `map(...)`:

```java
    /**
     * Erzeugt aus einer Taster-Aktion eine EVENT-Entität ("<Name> Taster").
     * Leer, wenn die Nachricht keine Aktion enthält. Konsumenten melden das
     * Ergebnis über {@code EntityStateService.reportEvent} (nicht reportState).
     */
    public Optional<EntityStateUpdate> mapAction(ParsedZigbeeMessage message) {
        if (message.action() == null) {
            return Optional.empty();
        }
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("deviceClass", "button");
        if (message.batteryPercent() != null) {
            attributes.put("batteryPercent", message.batteryPercent());
        }
        if (message.linkQuality() != null) {
            attributes.put("linkQuality", message.linkQuality());
        }
        return Optional.of(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.EVENT, EntitySource.ZIGBEE, message.friendlyName(), "action"))
                .domain(EntityDomain.EVENT)
                .source(EntitySource.ZIGBEE)
                .sourceRef(message.friendlyName())
                .friendlyName(message.friendlyName() + " Taster")
                .state(message.action())
                .attributes(attributes)
                .build());
    }
```

- [ ] **Step 4: Tests laufen lassen — müssen bestehen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=ZigbeeEntityMapperTest
```
Erwartet: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/mapper backend/src/test/java/com/household/manager/entitystate/mapper
git commit -m "feat(entitystate): Zigbee-Taster-Aktionen auf EVENT-Entitaeten mappen"
```

---

### Task 5: MQTT-Verdrahtung in `ZigbeeMqttConfig`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`

Reines Glue-Code-Wiring analog zum bestehenden Hook-Muster; kein eigener Unit-Test (die Bausteine sind einzeln getestet, die Klasse hat bisher bewusst keinen Test).

- [ ] **Step 1: Retained-Flag durchreichen**

In `handle(...)` den Parser-Aufruf ändern:

```java
            Optional<ParsedZigbeeMessage> parsed = parser.parse(topic, payload, publish.isRetain());
```

- [ ] **Step 2: Ereignis melden**

In `reportEntityStates(...)` innerhalb des bestehenden try-Blocks ergänzen:

```java
    private void reportEntityStates(ParsedZigbeeMessage message) {
        try {
            zigbeeEntityMapper.map(message).forEach(entityStateService::reportState);
            zigbeeEntityMapper.mapAction(message).ifPresent(entityStateService::reportEvent);
        } catch (Exception ex) {
            log.warn("Failed to report zigbee entity states for {}: {}",
                    message.friendlyName(), ex.getMessage());
        }
    }
```

- [ ] **Step 3: Modul kompiliert und alle Backend-Tests grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test
```
Erwartet: PASS — bis auf die bekannten, vorbestehenden DB-Fehlschläge `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` (Test-DB lokal nicht erreichbar, ignorieren).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "feat(zigbee): Taster-Ereignisse an die Entity-Schicht melden"
```

---

### Task 6: Flow-Trigger-Node `entity-event-trigger`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/flowengine/TriggerNodeHandler.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/EntityEventTriggerHandler.java`
- Test (neu): `backend/src/test/java/com/household/manager/flowengine/nodes/EntityEventTriggerHandlerTest.java`
- Test (erweitern): `backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java`

- [ ] **Step 1: Failing Tests schreiben**

Neue Datei `EntityEventTriggerHandlerTest.java` (NodeContext-Stub wie in `EntityStateTriggerHandlerTest`, aber ohne Scheduler-Bedarf):

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class EntityEventTriggerHandlerTest {

    private final EntityEventTriggerHandler handler = new EntityEventTriggerHandler();
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new ArrayList<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "t"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return null; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    private EntityEventFired event(String action) {
        return new EntityEventFired("event.zigbee_flur_taster_action", action,
                Map.of("deviceClass", "button"), LocalDateTime.now());
    }

    private NodeConfig config(Map<String, Object> values) {
        return new NodeConfig(values);
    }

    @Test
    void firesOnEveryEventEvenWithSameAction() {
        NodeConfig cfg = config(Map.of("entityId", "event.zigbee_flur_taster_action"));

        handler.onEntityEventFired(event("single"), cfg, ctx);
        handler.onEntityEventFired(event("single"), cfg, ctx);

        assertEquals(2, emitted.size());
        assertEquals("single", emitted.get(0).get("action"));
        assertEquals("event.zigbee_flur_taster_action", emitted.get(0).get("entityId"));
        assertEquals("t", emitted.get(0).get("triggerNodeId"));
    }

    @Test
    void actionFilterMatchesExactly() {
        NodeConfig cfg = config(Map.of("entityId", "event.zigbee_flur_taster_action", "action", "double"));

        handler.onEntityEventFired(event("single"), cfg, ctx);
        assertTrue(emitted.isEmpty());

        handler.onEntityEventFired(event("double"), cfg, ctx);
        assertEquals(1, emitted.size());
    }

    @Test
    void blankFilterFiresForAnyAction() {
        NodeConfig cfg = config(Map.of("entityId", "event.zigbee_flur_taster_action", "action", ""));

        handler.onEntityEventFired(event("hold"), cfg, ctx);

        assertEquals(1, emitted.size());
    }

    @Test
    void validateRequiresEntityId() {
        assertFalse(handler.validate(config(Map.of())).isEmpty());
        assertTrue(handler.validate(config(Map.of("entityId", "e"))).isEmpty());
    }

    @Test
    void watchedEntityIdComesFromConfig() {
        assertEquals("e", handler.watchedEntityId(config(Map.of("entityId", "e"))).orElseThrow());
    }
}
```

In `NodeCatalogFieldsTest` ergänzen:

```java
    @Test
    void entityEventTriggerFieldsAndPorts() {
        var h = new EntityEventTriggerHandler();
        var fields = h.fields();
        assertEquals(NodeFieldType.ENTITY_REF, field(fields, "entityId").type());
        assertTrue(field(fields, "entityId").required());
        assertFalse(field(fields, "action").required());
        assertEquals(List.of("Ausgang"), h.portLabels());
    }
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest='EntityEventTriggerHandlerTest,NodeCatalogFieldsTest'
```
Erwartet: COMPILATION ERROR (`EntityEventTriggerHandler` und `onEntityEventFired` fehlen).

- [ ] **Step 3: `TriggerNodeHandler` erweitern**

In `TriggerNodeHandler.java` (Import `com.household.manager.entitystate.EntityEventFired` ergänzen), nach `onEntityEvent`:

```java
    /**
     * Reaktion auf ein Ereignis einer EVENT-Entität. Wird — anders als
     * {@link #onEntityEvent} — bei JEDEM Ereignis aufgerufen, auch bei
     * wiederholt gleicher Aktion.
     */
    default void onEntityEventFired(EntityEventFired event, NodeConfig config, NodeContext ctx) {
    }
```

- [ ] **Step 4: Handler implementieren**

Neue Datei `EntityEventTriggerHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Trigger auf Ereignisse von EVENT-Entitäten (z. B. Zigbee-Taster). Feuert bei
 * JEDEM Ereignis — auch bei wiederholt gleicher Aktion (kein Flanken-Verhalten,
 * kein forSeconds: Ereignisse haben keine Verweildauer). Optionaler
 * Aktions-Filter mit exaktem String-Vergleich; leer = jede Aktion.
 */
@Component
public class EntityEventTriggerHandler implements TriggerNodeHandler {

    @Override
    public String type() {
        return "entity-event-trigger";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public Optional<String> watchedEntityId(NodeConfig config) {
        return config.string("entityId");
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field("entityId", "Entity", NodeFieldType.ENTITY_REF, true),
                NodeFieldDescriptor.field("action", "Aktion (leer = jede)", NodeFieldType.STRING, false));
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").isEmpty()) {
            errors.add("entityId fehlt");
        }
        return errors;
    }

    @Override
    public void onEntityEventFired(EntityEventFired event, NodeConfig config, NodeContext ctx) {
        String filter = config.string("action").orElse(null);
        if (filter != null && !filter.isBlank() && !filter.equals(event.action())) {
            return;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("entityId", event.entityId());
        values.put("action", event.action());
        values.put("attributes", event.attributes());
        values.put("timestamp", event.timestamp());
        values.put("triggerNodeId", ctx.nodeId());
        ctx.emit(0, FlowMessage.of(values));
    }
}
```

- [ ] **Step 5: Tests laufen lassen — müssen bestehen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest='EntityEventTriggerHandlerTest,NodeCatalogFieldsTest'
```
Erwartet: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine backend/src/test/java/com/household/manager/flowengine
git commit -m "feat(flowengine): entity-event-trigger fuer Taster-Ereignisse"
```

---

### Task 7: `FlowEngineListener` verteilt `EntityEventFired`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/flowengine/FlowEngineListener.java`

Glue-Code analog zum bestehenden (untesteten) Listener; die Fachlogik steckt in den einzeln getesteten Handlern. Die Verteil-Logik wird in eine gemeinsame Methode gezogen (DRY).

- [ ] **Step 1: Listener umbauen**

Kompletter neuer Inhalt der Klasse (Imports: zusätzlich `com.household.manager.entitystate.EntityEventFired` und `com.household.manager.flowengine.model.NodeConfig`):

```java
package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Verbindet die Entity-Schicht mit der Flow-Engine: Entity-Events werden
 * asynchron (eigener Pool) an die passenden Trigger-Nodes verteilt.
 * Bewusst KEIN @TransactionalEventListener (verwirft Events ohne aktive TX —
 * der Polling-Normalfall; siehe Stufe-2-Spec).
 */
@Component
@Slf4j
public class FlowEngineListener {

    private final FlowRegistry registry;
    private final Executor executor;

    public FlowEngineListener(FlowRegistry registry, @Qualifier("flowEngineExecutor") Executor executor) {
        this.registry = registry;
        this.executor = executor;
    }

    @EventListener
    public void onEntityStateChanged(EntityStateChangedEvent event) {
        dispatch(event.entityId(), (trigger, config, ctx) -> trigger.onEntityEvent(event, config, ctx));
    }

    @EventListener
    public void onEntityEventFired(EntityEventFired event) {
        dispatch(event.entityId(), (trigger, config, ctx) -> trigger.onEntityEventFired(event, config, ctx));
    }

    private void dispatch(String entityId, TriggerInvocation invocation) {
        for (FlowRegistry.TriggerRef ref : registry.triggersFor(entityId)) {
            executor.execute(() -> {
                try {
                    FlowGraph graph = registry.graph(ref.flowId()).orElse(null);
                    NodeContext ctx = registry.context(ref.flowId(), ref.nodeId());
                    if (graph == null || ctx == null) {
                        return;
                    }
                    FlowNode node = graph.node(ref.nodeId());
                    if (node == null) {
                        return;
                    }
                    if (registry.handler(node.type()) instanceof TriggerNodeHandler trigger) {
                        invocation.invoke(trigger, node.config(), ctx);
                    }
                } catch (Exception ex) {
                    log.warn("Flow {} trigger {} failed: {}", ref.flowId(), ref.nodeId(), ex.getMessage());
                }
            });
        }
    }

    @FunctionalInterface
    private interface TriggerInvocation {
        void invoke(TriggerNodeHandler trigger, NodeConfig config, NodeContext ctx);
    }
}
```

- [ ] **Step 2: Alle Backend-Tests grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test
```
Erwartet: PASS (bis auf die bekannten DB-Fehlschläge, siehe Header).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/FlowEngineListener.java
git commit -m "feat(flowengine): EntityEventFired an Trigger-Nodes verteilen"
```

---

### Task 8: Frontend — Domain, Filter, Node-Label

**Files:**
- Modify: `frontend/src/app/models/entity-state.model.ts`
- Modify: `frontend/src/app/pages/entities/entities.component.html`
- Modify: `frontend/src/app/pages/flows/node-catalog.ts`

- [ ] **Step 1: `EntityDomain`-Union erweitern**

In `entity-state.model.ts`:

```ts
export type EntityDomain =
  | 'SWITCH' | 'SENSOR' | 'BINARY_SENSOR' | 'EVENT'
  | 'INPUT_BOOLEAN' | 'INPUT_NUMBER' | 'INPUT_TEXT' | 'INPUT_SELECT';
```

- [ ] **Step 2: Domain-Filter im Entities-View**

In `entities.component.html` nach der Option `BINARY_SENSOR` einfügen:

```html
      <option value="EVENT">Event</option>
```

- [ ] **Step 3: Node-Label im Katalog**

In `node-catalog.ts` in der `LABELS`-Map nach `'entity-state-trigger'` einfügen:

```ts
  'entity-event-trigger': 'Taster-Trigger',
```

(Kategorie kommt aus dem Trigger-Flag des Backend-Katalogs; das schema-getriebene Config-Panel braucht keine Änderung.)

- [ ] **Step 4: Frontend-Tests laufen lassen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```
Erwartet: PASS (die bestehenden Specs fixieren keine vollständige Label-Liste; sollte doch ein Spec die Katalog-Typen aufzählen und fehlschlagen, den neuen Typ dort analog zu `entity-state-trigger` ergänzen).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/models/entity-state.model.ts frontend/src/app/pages/entities/entities.component.html frontend/src/app/pages/flows/node-catalog.ts
git commit -m "feat(frontend): EVENT-Domain und Taster-Trigger-Label"
```

---

### Task 9: Gesamtverifikation

- [ ] **Step 1: Voller Backend-Testlauf**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test
```
Erwartet: PASS bis auf die zwei bekannten, vorbestehenden DB-Fehlschläge (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest`).

- [ ] **Step 2: Frontend-Build**

```bash
cd frontend && npx ng build --configuration production
```
Erwartet: Build erfolgreich.

- [ ] **Step 3: Manuelle End-to-End-Prüfung (sobald ein realer Taster erreichbar ist)**

1. Backend + Frontend starten, physischen Zigbee-Taster drücken.
2. Entities-View: Entität `event.zigbee_<name>_action` erscheint mit letzter Aktion und „gerade eben".
3. Zweimal dieselbe Taste drücken: `Geändert`-Zeitpunkt aktualisiert sich beide Male.
4. Flow anlegen: `Taster-Trigger` (Aktions-Filter z. B. `single`) → `Debug`-Node; Taste drücken → Debug-Eintrag pro Druck, auch bei wiederholt gleicher Taste.
5. Backend neu starten, KEINEN Taster drücken: es darf kein Geister-Trigger feuern (Retained-Schutz).

Hinweis: laut Projektgedächtnis steht die zigbee2mqtt-Umgebung ggf. noch vor dem HA-Cutover — falls kein realer Taster erreichbar ist, diesen Schritt dokumentiert offenlassen.
