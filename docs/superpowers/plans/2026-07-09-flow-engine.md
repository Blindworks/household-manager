# Flow-Engine (Stufe 3a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Node-RED-inspirierte Flow-Engine im Backend: Flows als JSON-Graphen (Draft/Deploy), asynchrone Message-Passing-Ausführung, Node-Katalog v1 (Entity-Trigger mit Verweildauer, Cron-Trigger, Bedingung, Delay, Rate-Limit, Debug, Alexa-Ansage, Gerät schalten), REST-API inkl. Inject und node-types-Katalog.

**Architecture:** Ein Flow ist eine JSON-Definition (nodes + wires) in der Tabelle `flows` mit Draft/Deploy-Semantik. `FlowRegistry` hält deployte Flows als In-Memory-Graphen mit per-Node-State und Trigger-Index; `FlowEngine` traversiert Graphen asynchron auf einem eigenen Executor (Hop-Limit 100, Fehlerisolation pro Zweig). Node-Typen sind `NodeHandler`-Beans — neuer Typ = neues Bean. Spec: `docs/superpowers/specs/2026-07-09-flow-engine-design.md`.

**Tech Stack:** Spring Boot 3.4.1, Java 21, Liquibase, Lombok, Jackson, JUnit 5 + Mockito.

---

## Build-Umgebung (für jeden Backend-Schritt)

Vor jedem `mvn`-Aufruf (Bash):

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend
```

**Bekannt und zu ignorieren:** `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal fehl (Test-DB nicht erreichbar) — vorbestehend. Einzeltests immer mit `-Dtest=...`.

**Rot-Schritte:** Wo ein Step nur „Tests rot (Kompilierfehler)" sagt, gilt derselbe `mvn -q test -Dtest=...`-Befehl wie im Grün-Schritt desselben Tasks.

**Projektregeln:**
- JPA-Repositories NUR in `com.household.manager.repository` (JpaConfig-Scan).
- Schemaänderungen NUR über Liquibase (letzter Changeset: `20260708-0029`).
- Lombok; Controller-Konvention `@RequestMapping("/v1/...")` (Context-Path `/api`).
- Nur die pro Task gelisteten Dateien committen (plus notwendige Konstruktor-Fixes bestehender Tests — als Abweichung melden). Niemals `git add -A`. `.claude/`- und `nul`-Dateien nicht anfassen. Branch: `main` (vom Nutzer freigegeben).
- Falls ein bestehender Test eine geänderte Klasse direkt instanziiert: nur Konstruktor-Aufruf anpassen, Datei mit committen, als Abweichung melden.

**Vorhandene Bausteine (Stufe 2), auf denen aufgebaut wird:**
- `EntityStateChangedEvent(String entityId, String oldState, String newState, Map<String,Object> attributes, LocalDateTime timestamp)` — Spring-Event
- `EntityStateService.getByEntityId(String)` → `Optional<EntityState>`; `EntityState.getState()` → String
- `AlexaAnnouncementService.announce(String text, List<String> serialNumbers, AlexaTtsMode mode)`; `AlexaTtsMode` = SPEAK/ANNOUNCE
- `SmartDeviceService.turnOn(Long id)` / `turnOff(Long id)`
- `TaskScheduler`-Bean existiert (`SchedulingConfig`, Pool 4, Prefix "polling-")
- `ErrorResponse` (exception-Package): Builder mit timestamp/status/error/message/path/validationErrors
- `GlobalExceptionHandler` mit Handlern für IllegalArgumentException (400), MethodArgumentTypeMismatchException (400), Exception (500)

---

### Task 1: Liquibase, Flow-Entity, Repository

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260709-0030-create-flows-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (Include vor `</databaseChangeLog>`)
- Create: `backend/src/main/java/com/household/manager/model/entity/Flow.java`
- Create: `backend/src/main/java/com/household/manager/repository/FlowRepository.java`

- [ ] **Step 1: Changeset anlegen**

`20260709-0030-create-flows-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260709-0030-create-flows-table" author="household-manager">
        <preConditions onFail="MARK_RAN">
            <not>
                <tableExists tableName="flows"/>
            </not>
        </preConditions>
        <createTable tableName="flows">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="name" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="description" type="VARCHAR(1000)"/>
            <column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="draft_definition" type="LONGTEXT"/>
            <column name="deployed_definition" type="LONGTEXT"/>
            <column name="deployed_at" type="TIMESTAMP"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

</databaseChangeLog>
```

Master-Include (nach dem entity-states-Include):

```xml
    <!-- Flow Engine (Stage 3a) -->
    <include file="db/changelog/changes/20260709-0030-create-flows-table.xml"/>
```

- [ ] **Step 2: Entity anlegen**

`Flow.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ein Automatisierungs-Flow (Node-RED-Stil): Graph aus Nodes und Wires als JSON.
 * draft = Arbeitsstand des Editors, deployed = von der Engine ausgeführte Version.
 */
@Entity
@Table(name = "flows")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Flow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    /** Kill-Switch: deaktivierte Flows werden nicht ausgeführt. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Arbeitsstand des Editors (JSON, nebenwirkungsfrei speicherbar). */
    @Column(name = "draft_definition", columnDefinition = "LONGTEXT")
    private String draftDefinition;

    /** Von der Engine ausgeführte Version (JSON); NULL = nie deployt. */
    @Column(name = "deployed_definition", columnDefinition = "LONGTEXT")
    private String deployedDefinition;

    @Column(name = "deployed_at")
    private LocalDateTime deployedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Repository anlegen**

`FlowRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.Flow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlowRepository extends JpaRepository<Flow, Long> {

    List<Flow> findAllByOrderByNameAsc();

    List<Flow> findByEnabledTrueAndDeployedDefinitionNotNull();
}
```

- [ ] **Step 4: Kompilieren**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/Flow.java backend/src/main/java/com/household/manager/repository/FlowRepository.java
git commit -m "feat(flowengine): add Flow entity, repository and Liquibase changeset"
```

---

### Task 2: Flow-Definitionsmodell (JSON-Parsing) + NodeConfig

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/model/FlowDefinition.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/model/FlowNode.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/model/FlowWire.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/model/NodeConfig.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/model/FlowDefinitionParser.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/model/FlowDefinitionParserTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.flowengine.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlowDefinitionParserTest {

    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());

    private static final String JSON = """
            {
              "nodes": [
                { "id": "n1", "type": "entity-state-trigger", "name": "Waschmaschine",
                  "position": { "x": 80, "y": 120 },
                  "config": { "entityId": "sensor.x_power", "operator": "<", "value": "5", "forSeconds": 180 } },
                { "id": "n2", "type": "alexa-announce", "position": { "x": 420, "y": 120 },
                  "config": { "text": "Fertig", "mode": "ANNOUNCE", "deviceSerials": ["G09"] } }
              ],
              "wires": [ { "from": { "node": "n1", "port": 0 }, "to": { "node": "n2" } } ]
            }
            """;

    @Test
    void parsesNodesWiresAndConfig() {
        FlowDefinition def = parser.parse(JSON);

        assertEquals(2, def.nodes().size());
        FlowNode n1 = def.nodes().get(0);
        assertEquals("n1", n1.id());
        assertEquals("entity-state-trigger", n1.type());
        assertEquals(Optional.of("sensor.x_power"), n1.config().string("entityId"));
        assertEquals(Optional.of(180), n1.config().integer("forSeconds"));

        assertEquals(1, def.wires().size());
        assertEquals("n1", def.wires().get(0).from().node());
        assertEquals(0, def.wires().get(0).from().port());
        assertEquals("n2", def.wires().get(0).to().node());
    }

    @Test
    void configStringListAndMissingKeys() {
        FlowDefinition def = parser.parse(JSON);
        NodeConfig config = def.nodes().get(1).config();

        assertEquals(java.util.List.of("G09"), config.stringList("deviceSerials"));
        assertTrue(config.string("missing").isEmpty());
        assertTrue(config.integer("missing").isEmpty());
        assertTrue(config.stringList("missing").isEmpty());
    }

    @Test
    void invalidJsonThrowsWithReadableMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parser.parse("{ kaputt"));
        assertTrue(ex.getMessage().contains("Invalid flow definition"));
    }

    @Test
    void integerAcceptsNumericStrings() {
        FlowDefinition def = parser.parse(JSON.replace("\"forSeconds\": 180", "\"forSeconds\": \"180\""));
        assertEquals(Optional.of(180), def.nodes().get(0).config().integer("forSeconds"));
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowDefinitionParserTest`
Expected: Kompilierfehler „cannot find symbol"

- [ ] **Step 3: Implementierung**

`FlowNode.java`:

```java
package com.household.manager.flowengine.model;

/**
 * Eine Node im Flow-Graphen. position gehört dem Canvas-Editor; die Engine ignoriert sie.
 */
public record FlowNode(String id, String type, String name, Position position, NodeConfig config) {

    public record Position(double x, double y) {
    }
}
```

`FlowWire.java`:

```java
package com.household.manager.flowengine.model;

/**
 * Verbindung von einem Ausgangsport einer Node zum Eingang einer anderen.
 */
public record FlowWire(Endpoint from, Target to) {

    public record Endpoint(String node, int port) {
    }

    public record Target(String node) {
    }
}
```

`FlowDefinition.java`:

```java
package com.household.manager.flowengine.model;

import java.util.List;

/** Kompletter Flow-Graph, wie er als JSON in der flows-Tabelle liegt. */
public record FlowDefinition(List<FlowNode> nodes, List<FlowWire> wires) {
}
```

`NodeConfig.java`:

```java
package com.household.manager.flowengine.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typisierter, null-sicherer Zugriff auf die freie config-Map einer Node.
 */
public record NodeConfig(Map<String, Object> values) {

    public static NodeConfig empty() {
        return new NodeConfig(Map.of());
    }

    public Optional<String> string(String key) {
        Object value = values.get(key);
        return value != null ? Optional.of(String.valueOf(value)) : Optional.empty();
    }

    public Optional<Integer> integer(String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Optional.of(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public List<String> stringList(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return Collections.emptyList();
    }
}
```

`FlowDefinitionParser.java`:

```java
package com.household.manager.flowengine.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parst die JSON-Flow-Definition in das Modell. Wirft IllegalArgumentException
 * mit lesbarer Meldung bei kaputtem JSON (wird vom Deploy als 400 gemeldet).
 */
@Component
@RequiredArgsConstructor
public class FlowDefinitionParser {

    private final ObjectMapper objectMapper;

    public FlowDefinition parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<FlowNode> nodes = new ArrayList<>();
            for (JsonNode n : root.path("nodes")) {
                FlowNode.Position position = new FlowNode.Position(
                        n.path("position").path("x").asDouble(0),
                        n.path("position").path("y").asDouble(0));
                Map<String, Object> config = objectMapper.convertValue(
                        n.path("config").isMissingNode() ? objectMapper.createObjectNode() : n.path("config"),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                nodes.add(new FlowNode(
                        n.path("id").asText(null),
                        n.path("type").asText(null),
                        n.path("name").asText(null),
                        position,
                        new NodeConfig(config)));
            }
            List<FlowWire> wires = new ArrayList<>();
            for (JsonNode w : root.path("wires")) {
                wires.add(new FlowWire(
                        new FlowWire.Endpoint(w.path("from").path("node").asText(null), w.path("from").path("port").asInt(0)),
                        new FlowWire.Target(w.path("to").path("node").asText(null))));
            }
            return new FlowDefinition(List.copyOf(nodes), List.copyOf(wires));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid flow definition: " + ex.getMessage(), ex);
        }
    }
}
```

- [ ] **Step 4: Test grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowDefinitionParserTest`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine backend/src/test/java/com/household/manager/flowengine
git commit -m "feat(flowengine): add flow definition model and JSON parser"
```

---

### Task 3: FlowMessage, NodeResult, NodeHandler-Interfaces, StateComparator

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowMessage.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/NodeResult.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/NodeContext.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/NodeHandler.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/TriggerNodeHandler.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/StateComparator.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/StateComparatorTest.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/FlowMessageTest.java`

- [ ] **Step 1: Failing Tests schreiben**

`StateComparatorTest.java`:

```java
package com.household.manager.flowengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateComparatorTest {

    @Test
    void numericComparisonsParseStates() {
        assertTrue(StateComparator.matches("4.5", "<", "5"));
        assertFalse(StateComparator.matches("5.5", "<", "5"));
        assertTrue(StateComparator.matches("-150", "<", "-100"));
        assertTrue(StateComparator.matches("21.5", ">=", "21.5"));
        assertTrue(StateComparator.matches("7", ">", "5"));
        assertTrue(StateComparator.matches("5", "<=", "5"));
    }

    @Test
    void equalityWorksForStringsAndNumbers() {
        assertTrue(StateComparator.matches("on", "==", "on"));
        assertFalse(StateComparator.matches("on", "==", "off"));
        assertTrue(StateComparator.matches("5.0", "==", "5"));
        assertTrue(StateComparator.matches("on", "!=", "off"));
    }

    @Test
    void unavailableAndNonNumericNeverMatchNumericOperators() {
        assertFalse(StateComparator.matches("unavailable", "<", "5"));
        assertFalse(StateComparator.matches("unknown", ">", "5"));
        assertFalse(StateComparator.matches("on", "<", "5"));
        assertFalse(StateComparator.matches(null, "<", "5"));
    }

    @Test
    void unknownOperatorNeverMatches() {
        assertFalse(StateComparator.matches("5", "~", "5"));
    }
}
```

`FlowMessageTest.java`:

```java
package com.household.manager.flowengine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlowMessageTest {

    @Test
    void withCreatesNewMessageWithoutMutatingOriginal() {
        FlowMessage original = FlowMessage.of(Map.of("entityId", "sensor.x"));
        FlowMessage extended = original.with("note", "hi");

        assertNull(original.get("note"));
        assertEquals("hi", extended.get("note"));
        assertEquals("sensor.x", extended.get("entityId"));
    }

    @Test
    void mergedOverwritesExistingKeys() {
        FlowMessage msg = FlowMessage.of(Map.of("a", "1", "b", "2"));
        FlowMessage merged = msg.merged(Map.of("b", "3", "c", "4"));

        assertEquals("1", merged.get("a"));
        assertEquals("3", merged.get("b"));
        assertEquals("4", merged.get("c"));
    }

    @Test
    void valuesAreImmutable() {
        FlowMessage msg = FlowMessage.of(Map.of("a", "1"));
        assertThrows(UnsupportedOperationException.class, () -> msg.values().put("x", "y"));
    }
}
```

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen** (Kompilierfehler)

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="StateComparatorTest,FlowMessageTest"`

- [ ] **Step 3: Implementierung**

`FlowMessage.java`:

```java
package com.household.manager.flowengine;

import java.util.HashMap;
import java.util.Map;

/**
 * Die Nachricht, die durch die Wires wandert (Node-REDs "msg").
 * Unveränderlich — Verzweigung auf mehrere Wires braucht deshalb keine Kopie:
 * kein Zweig kann den Zustand eines anderen sehen oder ändern.
 */
public record FlowMessage(Map<String, Object> values) {

    public FlowMessage {
        values = Map.copyOf(values);
    }

    public static FlowMessage of(Map<String, Object> values) {
        return new FlowMessage(values);
    }

    public Object get(String key) {
        return values.get(key);
    }

    public FlowMessage with(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(values);
        copy.put(key, value);
        return new FlowMessage(copy);
    }

    public FlowMessage merged(Map<String, Object> other) {
        Map<String, Object> copy = new HashMap<>(values);
        copy.putAll(other);
        return new FlowMessage(copy);
    }
}
```

`NodeResult.java`:

```java
package com.household.manager.flowengine;

import java.util.List;
import java.util.Map;

/**
 * Ergebnis eines Node-Aufrufs: Messages je Ausgangsport.
 */
public record NodeResult(Map<Integer, List<FlowMessage>> outputs) {

    public static NodeResult none() {
        return new NodeResult(Map.of());
    }

    /** Eine Message auf Port 0. */
    public static NodeResult single(FlowMessage message) {
        return port(0, message);
    }

    public static NodeResult port(int port, FlowMessage message) {
        return new NodeResult(Map.of(port, List.of(message)));
    }
}
```

`NodeContext.java`:

```java
package com.household.manager.flowengine;

import org.springframework.scheduling.TaskScheduler;

import java.util.concurrent.ConcurrentMap;

/**
 * Laufzeitkontext einer deployten Node: per-Node-Zustand, asynchrone
 * Fortsetzung und Debug-Ausgabe. Wird von der Engine bereitgestellt.
 */
public interface NodeContext {

    long flowId();

    String nodeId();

    /** Per-Node-Zustand (Timer, lastFired, ...). Lebt bis zum Re-Deploy/Neustart. */
    ConcurrentMap<String, Object> state();

    /** Setzt die Traversierung asynchron ab diesem Node-Ausgang fort (Delay, Trigger-Feuern). */
    void emit(int port, FlowMessage message);

    TaskScheduler scheduler();

    /** Schreibt in den Debug-Ringpuffer dieser Node (genutzt von debug-Node und Fehlerpfaden). */
    void debug(String label, FlowMessage message);
}
```

`NodeHandler.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.flowengine.model.NodeConfig;

import java.util.List;
import java.util.Map;

/**
 * Ein Node-Typ der Flow-Engine. Ein Spring-Bean pro Typ; neue Typen = neues Bean.
 * Handler sind zustandslos — per-Node-Zustand liegt im NodeContext.
 */
public interface NodeHandler {

    /** Typ-Kennung, z. B. "entity-state-trigger". */
    String type();

    /** Anzahl der Ausgangsports (Bedingung: 2, Debug: 0, sonst meist 1). */
    int outputPorts();

    /** Konfig-Prüfung beim Deploy; Rückgabe = Fehlermeldungen (leer = ok). */
    List<String> validate(NodeConfig config);

    /** Verarbeitet eine eingehende Message. */
    NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx);

    /** Beschreibung der Config-Felder (Schlüssel → Kurzbeschreibung) für den node-types-Katalog. */
    default Map<String, String> configSchema() {
        return Map.of();
    }
}
```

`TriggerNodeHandler.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.flowengine.model.NodeConfig;

import java.util.Optional;

/**
 * Trigger-Nodes: kein Eingang; feuern über ctx.emit(0, msg).
 */
public interface TriggerNodeHandler extends NodeHandler {

    /** Entity, auf die dieser Trigger lauscht (für den Trigger-Index); leer bei schedule-trigger. */
    Optional<String> watchedEntityId(NodeConfig config);

    /** Reaktion auf ein Entity-Event (nur für den Trigger relevanter Entitäten aufgerufen). */
    default void onEntityEvent(EntityStateChangedEvent event, NodeConfig config, NodeContext ctx) {
    }

    /**
     * Beim Deploy aufgerufen (z. B. Cron registrieren).
     *
     * @return Cleanup, das beim Undeploy/Re-Deploy ausgeführt wird
     */
    default Runnable register(NodeConfig config, NodeContext ctx) {
        return () -> {
        };
    }

    /** Trigger haben keinen Eingang. */
    @Override
    default NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        return NodeResult.none();
    }
}
```

`StateComparator.java`:

```java
package com.household.manager.flowengine;

import java.math.BigDecimal;

/**
 * Vergleicht Entity-Zustände (Strings) mit einem Operator. Numerische Operatoren
 * parsen beide Seiten als Zahl; nicht parsebare Zustände (unavailable, unknown,
 * "on", null) matchen numerisch nie. ==/!= vergleichen numerisch, wenn beide
 * Seiten Zahlen sind (5.0 == 5), sonst als String.
 */
public final class StateComparator {

    private StateComparator() {
    }

    public static boolean matches(String state, String operator, String value) {
        if (state == null || operator == null || value == null) {
            return false;
        }
        BigDecimal left = parse(state);
        BigDecimal right = parse(value);
        boolean numeric = left != null && right != null;

        return switch (operator) {
            case "==" -> numeric ? left.compareTo(right) == 0 : state.equals(value);
            case "!=" -> numeric ? left.compareTo(right) != 0 : !state.equals(value);
            case "<" -> numeric && left.compareTo(right) < 0;
            case "<=" -> numeric && left.compareTo(right) <= 0;
            case ">" -> numeric && left.compareTo(right) > 0;
            case ">=" -> numeric && left.compareTo(right) >= 0;
            default -> false;
        };
    }

    private static BigDecimal parse(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="StateComparatorTest,FlowMessageTest"`
Expected: Tests run: 7, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine backend/src/test/java/com/household/manager/flowengine
git commit -m "feat(flowengine): add message, node handler contracts and state comparator"
```

---

### Task 4: DebugBuffer + FlowGraph + FlowValidator

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/DebugBuffer.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowGraph.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowValidator.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/ValidationResult.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/FlowValidatorTest.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/DebugBufferTest.java`

- [ ] **Step 1: Failing Tests schreiben**

`DebugBufferTest.java`:

```java
package com.household.manager.flowengine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugBufferTest {

    @Test
    void keepsAtMost100EntriesPerNodeNewestLast() {
        DebugBuffer buffer = new DebugBuffer();
        for (int i = 0; i < 150; i++) {
            buffer.add(1L, "n1", "label", FlowMessage.of(Map.of("i", i)));
        }

        var entries = buffer.entries(1L, "n1");
        assertEquals(100, entries.size());
        assertEquals(50, entries.get(0).message().get("i"));
        assertEquals(149, entries.get(99).message().get("i"));
    }

    @Test
    void unknownNodeReturnsEmptyListAndClearRemovesFlow() {
        DebugBuffer buffer = new DebugBuffer();
        assertTrue(buffer.entries(9L, "nope").isEmpty());

        buffer.add(2L, "n1", null, FlowMessage.of(Map.of()));
        buffer.clearFlow(2L);
        assertTrue(buffer.entries(2L, "n1").isEmpty());
    }
}
```

`FlowValidatorTest.java`:

```java
package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FlowValidatorTest {

    /** Minimaler Test-Handler: Typ "test-action", 1 Ausgang, verlangt config.text. */
    private static class TestActionHandler implements NodeHandler {
        public String type() { return "test-action"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) {
            return config.string("text").isPresent() ? List.of() : List.of("text fehlt");
        }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) { return NodeResult.single(m); }
    }

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.empty(); }
    }

    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());
    private final com.household.manager.entitystate.EntityStateService entityStateService =
            org.mockito.Mockito.mock(com.household.manager.entitystate.EntityStateService.class);
    private final FlowValidator validator = new FlowValidator(
            List.of(new TestActionHandler(), new TestTriggerHandler()), entityStateService);

    private String def(String nodesJson, String wiresJson) {
        return "{ \"nodes\": [" + nodesJson + "], \"wires\": [" + wiresJson + "] }";
    }

    private static final String TRIGGER = "{ \"id\": \"t\", \"type\": \"test-trigger\", \"config\": {} }";
    private static final String ACTION = "{ \"id\": \"a\", \"type\": \"test-action\", \"config\": { \"text\": \"hi\" } }";

    @Test
    void validDefinitionHasNoErrors() {
        ValidationResult result = validator.validate(parser.parse(def(
                TRIGGER + "," + ACTION,
                "{ \"from\": { \"node\": \"t\", \"port\": 0 }, \"to\": { \"node\": \"a\" } }")));
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void unknownNodeTypeIsAnError() {
        ValidationResult result = validator.validate(parser.parse(def(
                "{ \"id\": \"x\", \"type\": \"does-not-exist\", \"config\": {} }", "")));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("does-not-exist"));
    }

    @Test
    void wireToMissingNodeAndInvalidPortAreErrors() {
        ValidationResult result = validator.validate(parser.parse(def(
                TRIGGER + "," + ACTION,
                "{ \"from\": { \"node\": \"t\", \"port\": 5 }, \"to\": { \"node\": \"ghost\" } }")));
        assertEquals(2, result.errors().size());
    }

    @Test
    void duplicateNodeIdAndMissingIdAreErrors() {
        ValidationResult result = validator.validate(parser.parse(def(
                TRIGGER + "," + TRIGGER + ", { \"type\": \"test-action\", \"config\": { \"text\": \"x\" } }", "")));
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void nodeConfigErrorsArePrefixedWithNodeId() {
        ValidationResult result = validator.validate(parser.parse(def(
                "{ \"id\": \"a\", \"type\": \"test-action\", \"config\": {} }", "")));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("a"));
        assertTrue(result.errors().get(0).contains("text fehlt"));
    }

    @Test
    void unknownEntityIdProducesWarningNotError() {
        org.mockito.Mockito.when(entityStateService.getByEntityId("sensor.ghost"))
                .thenReturn(Optional.empty());
        ValidationResult result = validator.validate(parser.parse(def(
                "{ \"id\": \"a\", \"type\": \"test-action\", \"config\": { \"text\": \"x\", \"entityId\": \"sensor.ghost\" } }", "")));

        assertTrue(result.errors().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("sensor.ghost"));
    }
}
```

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

`DebugBuffer.java`:

```java
package com.household.manager.flowengine;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ring-Puffer für Debug-Nodes: letzte 100 Messages pro (Flow, Node).
 * Grundlage der Debug-Sidebar in Stufe 3b.
 */
@Component
public class DebugBuffer {

    public record DebugEntry(LocalDateTime timestamp, String label, Map<String, Object> message) {
    }

    private static final int MAX_ENTRIES = 100;

    private final Map<String, Deque<DebugEntry>> buffers = new ConcurrentHashMap<>();

    public void add(long flowId, String nodeId, String label, FlowMessage message) {
        Deque<DebugEntry> deque = buffers.computeIfAbsent(key(flowId, nodeId), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(new DebugEntry(LocalDateTime.now(), label, message.values()));
            while (deque.size() > MAX_ENTRIES) {
                deque.removeFirst();
            }
        }
    }

    public List<DebugEntry> entries(long flowId, String nodeId) {
        Deque<DebugEntry> deque = buffers.get(key(flowId, nodeId));
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    public void clearFlow(long flowId) {
        buffers.keySet().removeIf(key -> key.startsWith(flowId + ":"));
    }

    private String key(long flowId, String nodeId) {
        return flowId + ":" + nodeId;
    }
}
```

`FlowGraph.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.flowengine.model.FlowWire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kompilierte, unveränderliche Sicht auf eine Flow-Definition für die Ausführung:
 * Nodes per id, Wires als Adjazenz (nodeId+port -> Ziel-Nodes).
 */
public class FlowGraph {

    private final Map<String, FlowNode> nodesById = new HashMap<>();
    private final Map<String, List<String>> targets = new HashMap<>();

    public FlowGraph(FlowDefinition definition) {
        for (FlowNode node : definition.nodes()) {
            nodesById.put(node.id(), node);
        }
        for (FlowWire wire : definition.wires()) {
            targets.computeIfAbsent(portKey(wire.from().node(), wire.from().port()), k -> new ArrayList<>())
                    .add(wire.to().node());
        }
    }

    public FlowNode node(String nodeId) {
        return nodesById.get(nodeId);
    }

    public java.util.Collection<FlowNode> nodes() {
        return nodesById.values();
    }

    public List<String> targetsOf(String nodeId, int port) {
        return targets.getOrDefault(portKey(nodeId, port), List.of());
    }

    private String portKey(String nodeId, int port) {
        return nodeId + "#" + port;
    }
}
```

`ValidationResult.java`:

```java
package com.household.manager.flowengine;

import java.util.List;

/** Ergebnis der Deploy-Validierung: Fehler blockieren, Warnungen nicht. */
public record ValidationResult(List<String> errors, List<String> warnings) {

    public boolean valid() {
        return errors.isEmpty();
    }
}
```

`FlowValidator.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.flowengine.model.FlowWire;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validiert eine Flow-Definition beim Deploy: Node-IDs, bekannte Typen,
 * Wire-Referenzen/Ports und die Config jeder Node (delegiert an den Handler).
 * Unbekannte Entitäten sind WARNUNGEN, keine Fehler (Entitäten kommen und gehen).
 */
@Component
public class FlowValidator {

    private final Map<String, NodeHandler> handlersByType = new HashMap<>();
    private final com.household.manager.entitystate.EntityStateService entityStateService;

    public FlowValidator(List<NodeHandler> handlers,
                         com.household.manager.entitystate.EntityStateService entityStateService) {
        for (NodeHandler handler : handlers) {
            handlersByType.put(handler.type(), handler);
        }
        this.entityStateService = entityStateService;
    }

    public ValidationResult validate(FlowDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Set<String> nodeIds = new HashSet<>();
        for (FlowNode node : definition.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add("Node ohne id");
                continue;
            }
            if (!nodeIds.add(node.id())) {
                errors.add("Doppelte Node-id: " + node.id());
            }
            NodeHandler handler = handlersByType.get(node.type());
            if (handler == null) {
                errors.add("Node '" + node.id() + "': unbekannter Typ '" + node.type() + "'");
                continue;
            }
            for (String configError : handler.validate(node.config())) {
                errors.add("Node '" + node.id() + "': " + configError);
            }
            node.config().string("entityId").ifPresent(entityId -> {
                if (entityStateService.getByEntityId(entityId).isEmpty()) {
                    warnings.add("Node '" + node.id() + "': Entität '" + entityId
                            + "' ist (noch) unbekannt — Trigger/Bedingung greift erst, wenn sie existiert");
                }
            });
        }

        for (FlowWire wire : definition.wires()) {
            FlowNode from = findNode(definition, wire.from().node());
            if (from == null) {
                errors.add("Wire von unbekannter Node '" + wire.from().node() + "'");
            } else {
                NodeHandler handler = handlersByType.get(from.type());
                if (handler != null && (wire.from().port() < 0 || wire.from().port() >= handler.outputPorts())) {
                    errors.add("Wire von Node '" + from.id() + "' Port " + wire.from().port()
                            + " existiert nicht (Ports: 0-" + (handler.outputPorts() - 1) + ")");
                }
            }
            if (findNode(definition, wire.to().node()) == null) {
                errors.add("Wire zu unbekannter Node '" + wire.to().node() + "'");
            }
        }

        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    private FlowNode findNode(FlowDefinition definition, String id) {
        return definition.nodes().stream().filter(n -> n.id() != null && n.id().equals(id)).findFirst().orElse(null);
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="FlowValidatorTest,DebugBufferTest"`
Expected: Tests run: 8, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine backend/src/test/java/com/household/manager/flowengine
git commit -m "feat(flowengine): add debug buffer, compiled flow graph and deploy validation"
```

---

### Task 5: Engine-Kern (Registry, Traversierung, Executor)

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowEngineConfig.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowRegistry.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowEngine.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/FlowEngineTest.java`

**Designkern:** `FlowRegistry` hält `DeployedFlow`-Objekte (Graph, per-Node-State, Cleanups, NodeContexts). `FlowEngine.runFrom(flowId, nodeId, port, msg)` traversiert synchron (wird von Aufrufern auf den Executor gelegt); `NodeContext.emit` legt eine neue Traversierung auf den Executor. Hop-Limit 100 pro `runFrom`-Aufruf; asynchrone Fortsetzungen (Delay) starten ein frisches Budget — dokumentierte, bewusste Entscheidung.

- [ ] **Step 1: Failing Test schreiben**

`FlowEngineTest.java`:

```java
package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FlowEngineTest {

    private final List<String> received = new CopyOnWriteArrayList<>();

    /** Sammelt empfangene Messages; 1 Ausgang, reicht weiter. */
    private class RecordingHandler implements NodeHandler {
        public String type() { return "recorder"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            received.add(ctx.nodeId() + ":" + m.get("v"));
            return NodeResult.single(m);
        }
    }

    /** Router: schickt auf Port 0 oder 1 je nach config.port. */
    private static class RouterHandler implements NodeHandler {
        public String type() { return "router"; }
        public int outputPorts() { return 2; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            return NodeResult.port(c.integer("port").orElse(0), m);
        }
    }

    /** Wirft immer. */
    private static class FailingHandler implements NodeHandler {
        public String type() { return "failing"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public NodeResult handle(FlowMessage m, NodeConfig c, NodeContext ctx) {
            throw new IllegalStateException("boom");
        }
    }

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.of("sensor.x"); }
    }

    private FlowEngine engine;
    private FlowRegistry registry;
    private final FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());

    @BeforeEach
    void setUp() {
        List<NodeHandler> handlers = List.of(
                new RecordingHandler(), new RouterHandler(), new FailingHandler(), new TestTriggerHandler());
        registry = new FlowRegistry(handlers);
        // Synchroner "Executor" (Runnable::run) macht die Tests deterministisch.
        engine = new FlowEngine(registry, Runnable::run,
                mock(org.springframework.scheduling.TaskScheduler.class), new DebugBuffer());
        registry.setEngine(engine);
    }

    private void deploy(long flowId, String json) {
        registry.deploy(flowId, parser.parse(json));
    }

    @Test
    void traversesLinearChain() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "r1", "type": "recorder", "config": {} },
                    { "id": "r2", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "r1" } },
                    { "from": { "node": "r1", "port": 0 }, "to": { "node": "r2" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(List.of("r1:1", "r2:1"), received);
    }

    @Test
    void routerSendsOnlyToWiredPort() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "rt", "type": "router", "config": { "port": 1 } },
                    { "id": "yes", "type": "recorder", "config": {} },
                    { "id": "no", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "rt" } },
                    { "from": { "node": "rt", "port": 0 }, "to": { "node": "yes" } },
                    { "from": { "node": "rt", "port": 1 }, "to": { "node": "no" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(List.of("no:1"), received);
    }

    @Test
    void failingNodeAbortsOnlyItsBranch() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "bad", "type": "failing", "config": {} },
                    { "id": "afterBad", "type": "recorder", "config": {} },
                    { "id": "good", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "bad" } },
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "good" } },
                    { "from": { "node": "bad", "port": 0 }, "to": { "node": "afterBad" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(List.of("good:1"), received);
    }

    @Test
    void hopLimitStopsCycles() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "a", "type": "recorder", "config": {} },
                    { "id": "b", "type": "recorder", "config": {} } ],
                  "wires": [
                    { "from": { "node": "t", "port": 0 }, "to": { "node": "a" } },
                    { "from": { "node": "a", "port": 0 }, "to": { "node": "b" } },
                    { "from": { "node": "b", "port": 0 }, "to": { "node": "a" } } ] }
                """);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertEquals(100, received.size());
    }

    @Test
    void undeployedFlowIsIgnored() {
        deploy(1L, """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "r1", "type": "recorder", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "r1" } } ] }
                """);
        registry.undeploy(1L);

        engine.runFrom(1L, "t", 0, FlowMessage.of(Map.of("v", "1")));

        assertTrue(received.isEmpty());
    }

    @Test
    void perNodeStateSurvivesAcrossExecutionsButNotRedeploy() {
        String def = """
                { "nodes": [
                    { "id": "t", "type": "test-trigger", "config": {} },
                    { "id": "r1", "type": "recorder", "config": {} } ],
                  "wires": [ { "from": { "node": "t", "port": 0 }, "to": { "node": "r1" } } ] }
                """;
        deploy(1L, def);
        registry.context(1L, "r1").state().put("k", "v");
        assertEquals("v", registry.context(1L, "r1").state().get("k"));

        deploy(1L, def); // Re-Deploy
        assertNull(registry.context(1L, "r1").state().get("k"));
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

`FlowEngineConfig.java`:

```java
package com.household.manager.flowengine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Eigener Thread-Pool für Flow-Ausführungen: kein Flow — egal wie langsam —
 * blockiert je einen Polling-Thread, MQTT-Callback oder Schaltbefehl.
 */
@Configuration
public class FlowEngineConfig {

    @Bean(name = "flowEngineExecutor")
    public Executor flowEngineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("flow-engine-");
        executor.initialize();
        return executor;
    }
}
```

`FlowRegistry.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowNode;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Hält die deployten Flows als In-Memory-Graphen inkl. per-Node-State,
 * NodeContexts, Trigger-Cleanups und Trigger-Index. Atomarer Swap pro Flow.
 */
@Component
@Slf4j
public class FlowRegistry {

    public record TriggerRef(long flowId, String nodeId) {
    }

    static class DeployedFlow {
        final FlowGraph graph;
        final Map<String, NodeContext> contexts = new HashMap<>();
        final List<Runnable> cleanups = new ArrayList<>();

        DeployedFlow(FlowGraph graph) {
            this.graph = graph;
        }
    }

    private final Map<String, NodeHandler> handlersByType = new HashMap<>();
    private final ConcurrentMap<Long, DeployedFlow> deployed = new ConcurrentHashMap<>();
    /** entityId -> Trigger, die darauf lauschen. Wird bei jedem Deploy/Undeploy neu aufgebaut. */
    private volatile Map<String, List<TriggerRef>> triggerIndex = Map.of();

    /** Zirkularität Engine<->Registry wird per Setter aufgelöst (Engine braucht Registry, Contexts brauchen Engine). */
    @Setter
    private FlowEngine engine;

    public FlowRegistry(List<NodeHandler> handlers) {
        for (NodeHandler handler : handlers) {
            handlersByType.put(handler.type(), handler);
        }
    }

    public NodeHandler handler(String type) {
        return handlersByType.get(type);
    }

    public java.util.Collection<NodeHandler> handlers() {
        return handlersByType.values();
    }

    public void deploy(long flowId, FlowDefinition definition) {
        undeploy(flowId);
        DeployedFlow flow = new DeployedFlow(new FlowGraph(definition));
        for (FlowNode node : flow.graph.nodes()) {
            flow.contexts.put(node.id(), new EngineNodeContext(flowId, node.id()));
        }
        // Trigger registrieren (z. B. Cron) — Cleanups für Undeploy sammeln
        for (FlowNode node : flow.graph.nodes()) {
            if (handler(node.type()) instanceof TriggerNodeHandler trigger) {
                flow.cleanups.add(trigger.register(node.config(), flow.contexts.get(node.id())));
            }
        }
        deployed.put(flowId, flow);
        rebuildTriggerIndex();
        log.info("Flow {} deployed ({} nodes)", flowId, flow.graph.nodes().size());
    }

    public void undeploy(long flowId) {
        DeployedFlow old = deployed.remove(flowId);
        if (old != null) {
            old.cleanups.forEach(cleanup -> {
                try {
                    cleanup.run();
                } catch (Exception ex) {
                    log.warn("Flow {} cleanup failed: {}", flowId, ex.getMessage());
                }
            });
            rebuildTriggerIndex();
            log.info("Flow {} undeployed", flowId);
        }
    }

    public Optional<FlowGraph> graph(long flowId) {
        DeployedFlow flow = deployed.get(flowId);
        return flow != null ? Optional.of(flow.graph) : Optional.empty();
    }

    public NodeContext context(long flowId, String nodeId) {
        DeployedFlow flow = deployed.get(flowId);
        return flow != null ? flow.contexts.get(nodeId) : null;
    }

    public List<TriggerRef> triggersFor(String entityId) {
        return triggerIndex.getOrDefault(entityId, List.of());
    }

    private void rebuildTriggerIndex() {
        Map<String, List<TriggerRef>> index = new HashMap<>();
        deployed.forEach((flowId, flow) -> {
            for (FlowNode node : flow.graph.nodes()) {
                if (handler(node.type()) instanceof TriggerNodeHandler trigger) {
                    trigger.watchedEntityId(node.config()).ifPresent(entityId ->
                            index.computeIfAbsent(entityId, k -> new ArrayList<>())
                                    .add(new TriggerRef(flowId, node.id())));
                }
            }
        });
        index.replaceAll((k, v) -> List.copyOf(v));
        triggerIndex = Map.copyOf(index);
    }

    /** NodeContext-Implementierung; delegiert emit/debug an die Engine. */
    private class EngineNodeContext implements NodeContext {
        private final long flowId;
        private final String nodeId;
        private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();

        EngineNodeContext(long flowId, String nodeId) {
            this.flowId = flowId;
            this.nodeId = nodeId;
        }

        public long flowId() {
            return flowId;
        }

        public String nodeId() {
            return nodeId;
        }

        public ConcurrentMap<String, Object> state() {
            return state;
        }

        public void emit(int port, FlowMessage message) {
            engine.emitAsync(flowId, nodeId, port, message);
        }

        public org.springframework.scheduling.TaskScheduler scheduler() {
            return engine.scheduler();
        }

        public void debug(String label, FlowMessage message) {
            engine.debugBuffer().add(flowId, nodeId, label, message);
        }
    }
}
```

`FlowEngine.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Traversiert deployte Flow-Graphen. Hop-Limit 100 pro Ausführung
 * (asynchrone Fortsetzungen via emit starten ein frisches Budget);
 * Fehler in einer Node brechen nur deren Zweig ab.
 */
@Component
@Slf4j
public class FlowEngine {

    static final int HOP_LIMIT = 100;

    private final FlowRegistry registry;
    private final Executor executor;
    private final TaskScheduler scheduler;
    private final DebugBuffer debugBuffer;

    public FlowEngine(FlowRegistry registry,
                      @Qualifier("flowEngineExecutor") Executor executor,
                      TaskScheduler scheduler,
                      DebugBuffer debugBuffer) {
        this.registry = registry;
        this.executor = executor;
        this.scheduler = scheduler;
        this.debugBuffer = debugBuffer;
    }

    /** Asynchrone Fortsetzung ab einem Node-Ausgang (von NodeContext.emit gerufen). */
    public void emitAsync(long flowId, String nodeId, int port, FlowMessage message) {
        executor.execute(() -> runFrom(flowId, nodeId, port, message));
    }

    /**
     * Traversiert ab dem Ausgangsport einer Node (typisch: gefeuerter Trigger).
     * Läuft im Aufrufer-Thread — Aufrufer legen dies auf den flowEngineExecutor.
     */
    public void runFrom(long flowId, String nodeId, int port, FlowMessage message) {
        FlowGraph graph = registry.graph(flowId).orElse(null);
        if (graph == null) {
            return;
        }
        record Work(String nodeId, FlowMessage message) {
        }
        Deque<Work> queue = new ArrayDeque<>();
        for (String target : graph.targetsOf(nodeId, port)) {
            queue.add(new Work(target, message));
        }

        int hops = 0;
        while (!queue.isEmpty()) {
            if (++hops > HOP_LIMIT) {
                log.warn("Flow {}: hop limit {} reached, aborting execution (cycle?)", flowId, HOP_LIMIT);
                return;
            }
            Work work = queue.poll();
            FlowNode node = graph.node(work.nodeId());
            if (node == null) {
                continue;
            }
            NodeHandler handler = registry.handler(node.type());
            NodeContext ctx = registry.context(flowId, node.id());
            if (handler == null || ctx == null) {
                continue;
            }
            try {
                NodeResult result = handler.handle(work.message(), node.config(), ctx);
                for (Map.Entry<Integer, List<FlowMessage>> output : result.outputs().entrySet()) {
                    for (FlowMessage outMessage : output.getValue()) {
                        for (String target : graph.targetsOf(node.id(), output.getKey())) {
                            queue.add(new Work(target, outMessage));
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Flow {} node {} failed, aborting branch: {}", flowId, node.id(), ex.getMessage());
                debugBuffer.add(flowId, node.id(), "ERROR: " + ex.getMessage(), work.message());
            }
        }
    }

    TaskScheduler scheduler() {
        return scheduler;
    }

    DebugBuffer debugBuffer() {
        return debugBuffer;
    }
}
```

**Hinweis Zirkularität:** `FlowEngine` braucht `FlowRegistry` (Konstruktor), die Registry-Contexts brauchen die Engine — aufgelöst über `registry.setEngine(engine)`. Damit Spring das automatisch macht, bekommt Task 11 einen kleinen `FlowEngineBootstrap` (`@PostConstruct`), der `registry.setEngine(engine)` setzt und die Flows lädt. In den Tests geschieht das von Hand.

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowEngineTest`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine backend/src/test/java/com/household/manager/flowengine
git commit -m "feat(flowengine): add registry, traversal engine with hop limit and branch isolation"
```

---

### Task 6: EntityStateTrigger (inkl. Verweildauer) + Engine-Anbindung

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/EntityStateTriggerHandler.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowEngineListener.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/EntityStateTriggerHandlerTest.java`

- [ ] **Step 1: Failing Test schreiben**

`EntityStateTriggerHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityStateTriggerHandlerTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private TaskScheduler taskScheduler;

    private EntityStateTriggerHandler handler;
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new java.util.ArrayList<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        handler = new EntityStateTriggerHandler(entityStateService);
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "t"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    private NodeConfig config(Map<String, Object> values) {
        return new NodeConfig(values);
    }

    private EntityStateChangedEvent event(String oldState, String newState) {
        return new EntityStateChangedEvent("sensor.x", oldState, newState, Map.of("unit", "W"), LocalDateTime.now());
    }

    @Test
    void firesOnTransitionIntoRange() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5"));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);

        assertEquals(1, emitted.size());
        assertEquals("sensor.x", emitted.get(0).get("entityId"));
        assertEquals("4", emitted.get(0).get("newState"));
    }

    @Test
    void doesNotRefireWhileStayingInRange() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5"));

        handler.onEntityEvent(event("4", "3"), cfg, ctx);

        assertTrue(emitted.isEmpty());
    }

    @Test
    void changedOperatorFiresOnEveryChange() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "changed"));

        handler.onEntityEvent(event("on", "off"), cfg, ctx);
        handler.onEntityEvent(event("off", "on"), cfg, ctx);

        assertEquals(2, emitted.size());
    }

    @Test
    void forSecondsSchedulesTimerInsteadOfFiring() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5", "forSeconds", 180));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);

        assertTrue(emitted.isEmpty());
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void timerFiresOnlyIfConditionStillHolds() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5", "forSeconds", 180));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> timerTask = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(timerTask.capture(), any(Instant.class));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);

        // Bedingung gilt noch -> feuert
        EntityState current = EntityState.builder().entityId("sensor.x").state("3").build();
        when(entityStateService.getByEntityId("sensor.x")).thenReturn(Optional.of(current));
        timerTask.getValue().run();
        assertEquals(1, emitted.size());

        // Bedingung gilt nicht mehr -> feuert nicht
        emitted.clear();
        handler.onEntityEvent(event("10", "4"), cfg, ctx);
        when(entityStateService.getByEntityId("sensor.x"))
                .thenReturn(Optional.of(EntityState.builder().entityId("sensor.x").state("50").build()));
        timerTask.getValue().run();
        assertTrue(emitted.isEmpty());
    }

    @Test
    void leavingRangeCancelsPendingTimer() {
        NodeConfig cfg = config(Map.of("entityId", "sensor.x", "operator", "<", "value", "5", "forSeconds", 180));
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        handler.onEntityEvent(event("10", "4"), cfg, ctx);   // Timer startet
        handler.onEntityEvent(event("4", "50"), cfg, ctx);   // verlässt Bereich

        verify(future).cancel(false);
        assertTrue(emitted.isEmpty());
    }

    @Test
    void validateRequiresEntityIdOperatorAndValueForNumericOps() {
        assertFalse(handler.validate(config(Map.of())).isEmpty());
        assertFalse(handler.validate(config(Map.of("entityId", "e", "operator", "<"))).isEmpty());
        assertTrue(handler.validate(config(Map.of("entityId", "e", "operator", "changed"))).isEmpty());
        assertTrue(handler.validate(config(Map.of("entityId", "e", "operator", "<", "value", "5"))).isEmpty());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

`EntityStateTriggerHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.StateComparator;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Trigger auf Entity-Zustandsänderungen. Edge-getriggert: feuert beim Übergang
 * IN den passenden Bereich (nicht bei jeder Änderung innerhalb). Mit forSeconds
 * startet stattdessen ein Timer; bei Ablauf wird der aktuelle Zustand erneut
 * geprüft; Verlassen des Bereichs storniert den Timer.
 */
@Component
@RequiredArgsConstructor
public class EntityStateTriggerHandler implements TriggerNodeHandler {

    static final String STATE_KEY_TIMER = "pendingTimer";
    private static final String OP_CHANGED = "changed";

    private final EntityStateService entityStateService;

    @Override
    public String type() {
        return "entity-state-trigger";
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
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").isEmpty()) {
            errors.add("entityId fehlt");
        }
        String operator = config.string("operator").orElse(null);
        if (operator == null) {
            errors.add("operator fehlt");
        } else if (!OP_CHANGED.equals(operator) && config.string("value").isEmpty()) {
            errors.add("value fehlt (nur bei operator 'changed' optional)");
        }
        return errors;
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of(
                "entityId", "Entity-ID, auf die gelauscht wird",
                "operator", "<, <=, >, >=, ==, != oder changed",
                "value", "Vergleichswert (entfällt bei changed)",
                "forSeconds", "optional: Bedingung muss so lange ununterbrochen gelten");
    }

    @Override
    public void onEntityEvent(EntityStateChangedEvent event, NodeConfig config, NodeContext ctx) {
        String operator = config.string("operator").orElse(OP_CHANGED);

        if (OP_CHANGED.equals(operator)) {
            ctx.emit(0, toMessage(event, ctx));
            return;
        }

        String value = config.string("value").orElse(null);
        boolean nowMatches = StateComparator.matches(event.newState(), operator, value);
        boolean beforeMatched = StateComparator.matches(event.oldState(), operator, value);
        Integer forSeconds = config.integer("forSeconds").orElse(null);

        if (nowMatches && !beforeMatched) {
            if (forSeconds == null || forSeconds <= 0) {
                ctx.emit(0, toMessage(event, ctx));
            } else {
                startTimer(event, config, ctx, operator, value, forSeconds);
            }
        } else if (!nowMatches) {
            cancelTimer(ctx);
        }
    }

    private void startTimer(EntityStateChangedEvent event, NodeConfig config, NodeContext ctx,
                            String operator, String value, int forSeconds) {
        cancelTimer(ctx);
        String entityId = config.string("entityId").orElseThrow();
        ScheduledFuture<?> future = ctx.scheduler().schedule(() -> {
            ctx.state().remove(STATE_KEY_TIMER);
            String currentState = entityStateService.getByEntityId(entityId)
                    .map(e -> e.getState()).orElse(null);
            if (StateComparator.matches(currentState, operator, value)) {
                ctx.emit(0, toMessage(event, ctx).with("newState", currentState));
            }
        }, Instant.now().plusSeconds(forSeconds));
        ctx.state().put(STATE_KEY_TIMER, future);
    }

    private void cancelTimer(NodeContext ctx) {
        Object pending = ctx.state().remove(STATE_KEY_TIMER);
        if (pending instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    private FlowMessage toMessage(EntityStateChangedEvent event, NodeContext ctx) {
        Map<String, Object> values = new HashMap<>();
        values.put("entityId", event.entityId());
        values.put("oldState", event.oldState());
        values.put("newState", event.newState());
        values.put("attributes", event.attributes());
        values.put("timestamp", event.timestamp());
        values.put("triggerNodeId", ctx.nodeId());
        return FlowMessage.of(values);
    }
}
```

`FlowEngineListener.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.flowengine.model.FlowNode;
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
        for (FlowRegistry.TriggerRef ref : registry.triggersFor(event.entityId())) {
            executor.execute(() -> {
                try {
                    FlowGraph graph = registry.graph(ref.flowId()).orElse(null);
                    NodeContext ctx = registry.context(ref.flowId(), ref.nodeId());
                    if (graph == null || ctx == null) {
                        return;
                    }
                    FlowNode node = graph.node(ref.nodeId());
                    if (registry.handler(node.type()) instanceof TriggerNodeHandler trigger) {
                        trigger.onEntityEvent(event, node.config(), ctx);
                    }
                } catch (Exception ex) {
                    log.warn("Flow {} trigger {} failed: {}", ref.flowId(), ref.nodeId(), ex.getMessage());
                }
            });
        }
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityStateTriggerHandlerTest`
Expected: Tests run: 7, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine backend/src/test/java/com/household/manager/flowengine
git commit -m "feat(flowengine): add entity state trigger with dwell time and async event listener"
```

---

### Task 7: ScheduleTrigger (Cron)

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/ScheduleTriggerHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/ScheduleTriggerHandlerTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleTriggerHandlerTest {

    @Mock
    private TaskScheduler taskScheduler;

    private final ScheduleTriggerHandler handler = new ScheduleTriggerHandler();
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new java.util.ArrayList<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "s"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    @Test
    void registerSchedulesCronAndCleanupCancels() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(task.capture(), any(CronTrigger.class));

        Runnable cleanup = handler.register(new NodeConfig(Map.of("cron", "0 0 7 * * *")), ctx);

        task.getValue().run();
        assertEquals(1, emitted.size());
        assertEquals("s", emitted.get(0).get("triggerNodeId"));

        cleanup.run();
        verify(future).cancel(false);
    }

    @Test
    void validateRejectsMissingOrInvalidCron() {
        assertFalse(handler.validate(new NodeConfig(Map.of())).isEmpty());
        assertFalse(handler.validate(new NodeConfig(Map.of("cron", "kaputt"))).isEmpty());
        assertTrue(handler.validate(new NodeConfig(Map.of("cron", "0 0 7 * * *"))).isEmpty());
    }

    @Test
    void watchesNoEntity() {
        assertTrue(handler.watchedEntityId(NodeConfig.empty()).isEmpty());
    }
}
```

- [ ] **Step 2: Test rot** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Zeitplan-Trigger (Spring-Cron). Wird beim Deploy registriert; das
 * Cleanup-Runnable storniert den Job beim Undeploy/Re-Deploy.
 */
@Component
public class ScheduleTriggerHandler implements TriggerNodeHandler {

    @Override
    public String type() {
        return "schedule-trigger";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public Optional<String> watchedEntityId(NodeConfig config) {
        return Optional.empty();
    }

    @Override
    public List<String> validate(NodeConfig config) {
        Optional<String> cron = config.string("cron");
        if (cron.isEmpty()) {
            return List.of("cron fehlt");
        }
        if (!CronExpression.isValidExpression(cron.get())) {
            return List.of("cron ist kein gültiger Spring-Cron-Ausdruck: " + cron.get());
        }
        return List.of();
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of("cron", "Spring-Cron-Ausdruck, z. B. '0 0 7 * * *' (täglich 7:00)");
    }

    @Override
    public Runnable register(NodeConfig config, NodeContext ctx) {
        String cron = config.string("cron").orElseThrow();
        ScheduledFuture<?> future = ctx.scheduler().schedule(
                () -> ctx.emit(0, FlowMessage.of(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "triggerNodeId", ctx.nodeId()))),
                new CronTrigger(cron));
        return () -> future.cancel(false);
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ScheduleTriggerHandlerTest`
Expected: Tests run: 3, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/ScheduleTriggerHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/ScheduleTriggerHandlerTest.java
git commit -m "feat(flowengine): add cron schedule trigger node"
```

---

### Task 8: Bedingungs- und Debug-Node

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/EntityConditionHandler.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/DebugNodeHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/EntityConditionHandlerTest.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/DebugNodeHandlerTest.java`

- [ ] **Step 1: Failing Tests schreiben**

`EntityConditionHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityConditionHandlerTest {

    @Mock
    private EntityStateService entityStateService;

    private EntityConditionHandler handler() {
        return new EntityConditionHandler(entityStateService);
    }

    private final NodeConfig cfg = new NodeConfig(Map.of(
            "entityId", "sensor.weather_dwd_temperature", "operator", "<", "value", "5"));
    private final FlowMessage msg = FlowMessage.of(Map.of("v", "1"));

    @Test
    void routesToPort0WhenConditionTrue() {
        when(entityStateService.getByEntityId("sensor.weather_dwd_temperature"))
                .thenReturn(Optional.of(EntityState.builder().state("3.2").build()));

        NodeResult result = handler().handle(msg, cfg, null);

        assertTrue(result.outputs().containsKey(0));
        assertFalse(result.outputs().containsKey(1));
    }

    @Test
    void routesToPort1WhenConditionFalseOrEntityMissing() {
        when(entityStateService.getByEntityId("sensor.weather_dwd_temperature"))
                .thenReturn(Optional.of(EntityState.builder().state("12").build()));
        assertTrue(handler().handle(msg, cfg, null).outputs().containsKey(1));

        when(entityStateService.getByEntityId("sensor.weather_dwd_temperature")).thenReturn(Optional.empty());
        assertTrue(handler().handle(msg, cfg, null).outputs().containsKey(1));
    }

    @Test
    void validateRequiresAllFields() {
        assertEquals(3, handler().validate(NodeConfig.empty()).size());
        assertTrue(handler().validate(cfg).isEmpty());
    }
}
```

`DebugNodeHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class DebugNodeHandlerTest {

    @Test
    void writesToDebugAndHasNoOutputs() {
        StringBuilder debugged = new StringBuilder();
        NodeContext ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "d"; }
            public ConcurrentMap<String, Object> state() { return new ConcurrentHashMap<>(); }
            public void emit(int port, FlowMessage message) { }
            public org.springframework.scheduling.TaskScheduler scheduler() { return null; }
            public void debug(String label, FlowMessage message) { debugged.append(label).append("|").append(message.get("v")); }
        };
        DebugNodeHandler handler = new DebugNodeHandler();

        NodeResult result = handler.handle(FlowMessage.of(Map.of("v", "42")),
                new NodeConfig(Map.of("label", "hier")), ctx);

        assertEquals("hier|42", debugged.toString());
        assertTrue(result.outputs().isEmpty());
        assertEquals(0, handler.outputPorts());
        assertTrue(handler.validate(NodeConfig.empty()).isEmpty());
    }
}
```

- [ ] **Step 2: Tests rot** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

`EntityConditionHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prüft den AKTUELLEN Zustand einer beliebigen Entität (Cross-Entity-Bedingung).
 * Port 0 = wahr, Port 1 = falsch. Unbekannte Entität wertet zu falsch.
 */
@Component
@RequiredArgsConstructor
public class EntityConditionHandler implements NodeHandler {

    private final EntityStateService entityStateService;

    @Override
    public String type() {
        return "entity-condition";
    }

    @Override
    public int outputPorts() {
        return 2;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("entityId").isEmpty()) {
            errors.add("entityId fehlt");
        }
        if (config.string("operator").isEmpty()) {
            errors.add("operator fehlt");
        }
        if (config.string("value").isEmpty()) {
            errors.add("value fehlt");
        }
        return errors;
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of(
                "entityId", "Entity, deren aktueller Zustand geprüft wird",
                "operator", "<, <=, >, >=, == oder !=",
                "value", "Vergleichswert");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String entityId = config.string("entityId").orElse("");
        String operator = config.string("operator").orElse("");
        String value = config.string("value").orElse("");

        String currentState = entityStateService.getByEntityId(entityId)
                .map(e -> e.getState()).orElse(null);
        boolean matches = StateComparator.matches(currentState, operator, value);
        return NodeResult.port(matches ? 0 : 1, message);
    }
}
```

`DebugNodeHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Schreibt jede eingehende Message in den Debug-Ringpuffer (via NodeContext).
 * Kein Ausgang — reine Senke, Node-REDs wichtigstes Entwicklungswerkzeug.
 */
@Component
public class DebugNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return "debug";
    }

    @Override
    public int outputPorts() {
        return 0;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        return List.of();
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of("label", "optionale Beschriftung des Eintrags");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        ctx.debug(config.string("label").orElse(null), message);
        return NodeResult.none();
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="EntityConditionHandlerTest,DebugNodeHandlerTest"`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes backend/src/test/java/com/household/manager/flowengine/nodes
git commit -m "feat(flowengine): add entity condition and debug nodes"
```

---

### Task 9: Delay- und Rate-Limit-Node

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/DelayNodeHandler.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/RateLimitNodeHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/DelayNodeHandlerTest.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/RateLimitNodeHandlerTest.java`

- [ ] **Step 1: Failing Tests schreiben**

`DelayNodeHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class DelayNodeHandlerTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Test
    void schedulesEmitAndReturnsNoImmediateOutput() {
        List<FlowMessage> emitted = new java.util.ArrayList<>();
        NodeContext ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "d"; }
            public ConcurrentMap<String, Object> state() { return new ConcurrentHashMap<>(); }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(task.capture(), any(Instant.class));

        DelayNodeHandler handler = new DelayNodeHandler();
        NodeResult result = handler.handle(FlowMessage.of(Map.of("v", "1")),
                new NodeConfig(Map.of("seconds", 300)), ctx);

        assertTrue(result.outputs().isEmpty());
        assertTrue(emitted.isEmpty());
        task.getValue().run();
        assertEquals(1, emitted.size());
    }

    @Test
    void validateRequiresPositiveSeconds() {
        DelayNodeHandler handler = new DelayNodeHandler();
        assertFalse(handler.validate(NodeConfig.empty()).isEmpty());
        assertFalse(handler.validate(new NodeConfig(Map.of("seconds", 0))).isEmpty());
        assertTrue(handler.validate(new NodeConfig(Map.of("seconds", 5))).isEmpty());
    }
}
```

`RateLimitNodeHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitNodeHandlerTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-09T10:00:00Z"));
    private RateLimitNodeHandler handler;
    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private NodeContext ctx;

    @BeforeEach
    void setUp() {
        handler = new RateLimitNodeHandler(new Clock() {
            public Instant instant() { return now.get(); }
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
        });
        ctx = new NodeContext() {
            public long flowId() { return 1L; }
            public String nodeId() { return "r"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { }
            public org.springframework.scheduling.TaskScheduler scheduler() { return null; }
            public void debug(String label, FlowMessage message) { }
        };
    }

    private final NodeConfig cfg = new NodeConfig(Map.of("minIntervalSeconds", 1800));
    private final FlowMessage msg = FlowMessage.of(Map.of());

    @Test
    void firstMessagePassesSecondWithinIntervalIsDropped() {
        assertFalse(handler.handle(msg, cfg, ctx).outputs().isEmpty());
        now.set(now.get().plusSeconds(60));
        assertTrue(handler.handle(msg, cfg, ctx).outputs().isEmpty());
    }

    @Test
    void messageAfterIntervalPassesAgain() {
        handler.handle(msg, cfg, ctx);
        now.set(now.get().plusSeconds(1801));
        assertFalse(handler.handle(msg, cfg, ctx).outputs().isEmpty());
    }

    @Test
    void validateRequiresPositiveInterval() {
        assertFalse(handler.validate(NodeConfig.empty()).isEmpty());
        assertTrue(handler.validate(cfg).isEmpty());
    }
}
```

- [ ] **Step 2: Tests rot** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

`DelayNodeHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reicht die Message nach konfigurierten Sekunden weiter (nicht-blockierend
 * über den TaskScheduler). Offene Delays verfallen bei Neustart/Re-Deploy.
 */
@Component
public class DelayNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return "delay";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        Integer seconds = config.integer("seconds").orElse(null);
        if (seconds == null || seconds <= 0) {
            return List.of("seconds fehlt oder ist nicht > 0");
        }
        return List.of();
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of("seconds", "Verzögerung in Sekunden");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        int seconds = config.integer("seconds").orElse(0);
        ctx.scheduler().schedule(() -> ctx.emit(0, message), Instant.now().plusSeconds(seconds));
        return NodeResult.none();
    }
}
```

`RateLimitNodeHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Drossel: lässt höchstens eine Message pro Intervall durch, Überschuss wird
 * verworfen (gegen Ansage-Spam bei flatternden Werten). Zustand in-memory.
 */
@Component
public class RateLimitNodeHandler implements NodeHandler {

    static final String STATE_KEY_LAST_PASSED = "lastPassed";

    private final Clock clock;

    public RateLimitNodeHandler() {
        this(Clock.systemDefaultZone());
    }

    RateLimitNodeHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "rate-limit";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        Integer interval = config.integer("minIntervalSeconds").orElse(null);
        if (interval == null || interval <= 0) {
            return List.of("minIntervalSeconds fehlt oder ist nicht > 0");
        }
        return List.of();
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of("minIntervalSeconds", "Mindestabstand zwischen zwei durchgelassenen Messages");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long intervalSeconds = config.integer("minIntervalSeconds").orElse(0);
        Instant now = clock.instant();
        Instant lastPassed = (Instant) ctx.state().get(STATE_KEY_LAST_PASSED);
        if (lastPassed != null && lastPassed.plusSeconds(intervalSeconds).isAfter(now)) {
            return NodeResult.none();
        }
        ctx.state().put(STATE_KEY_LAST_PASSED, now);
        return NodeResult.single(message);
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="DelayNodeHandlerTest,RateLimitNodeHandlerTest"`
Expected: Tests run: 5, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes backend/src/test/java/com/household/manager/flowengine/nodes
git commit -m "feat(flowengine): add delay and rate limit nodes"
```

---

### Task 10: Aktions-Nodes (Alexa, Gerät schalten)

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/AlexaAnnounceNodeHandler.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/SwitchDeviceNodeHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/AlexaAnnounceNodeHandlerTest.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/SwitchDeviceNodeHandlerTest.java`

- [ ] **Step 1: Failing Tests schreiben**

`AlexaAnnounceNodeHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.AlexaAnnouncementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlexaAnnounceNodeHandlerTest {

    @Mock
    private AlexaAnnouncementService announcementService;

    private AlexaAnnounceNodeHandler handler() {
        return new AlexaAnnounceNodeHandler(announcementService);
    }

    @Test
    void announcesWithResolvedPlaceholders() {
        NodeConfig cfg = new NodeConfig(Map.of(
                "text", "Temperatur ist {newState} Grad ({entityId})",
                "mode", "ANNOUNCE",
                "deviceSerials", List.of("G09")));
        FlowMessage msg = FlowMessage.of(Map.of(
                "entityId", "sensor.x", "newState", "21.5", "oldState", "20"));

        NodeResult result = handler().handle(msg, cfg, null);

        verify(announcementService).announce("Temperatur ist 21.5 Grad (sensor.x)", List.of("G09"), AlexaTtsMode.ANNOUNCE);
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void missingPlaceholderValuesRenderEmpty() {
        NodeConfig cfg = new NodeConfig(Map.of(
                "text", "Wert: {newState}", "mode", "SPEAK", "deviceSerials", List.of("G09")));

        handler().handle(FlowMessage.of(Map.of()), cfg, null);

        verify(announcementService).announce("Wert: ", List.of("G09"), AlexaTtsMode.SPEAK);
    }

    @Test
    void validateRequiresTextModeAndDevices() {
        assertEquals(3, handler().validate(NodeConfig.empty()).size());
        assertFalse(handler().validate(new NodeConfig(Map.of(
                "text", "x", "mode", "FALSCH", "deviceSerials", List.of("G09")))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of(
                "text", "x", "mode", "ANNOUNCE", "deviceSerials", List.of("G09")))).isEmpty());
    }
}
```

`SwitchDeviceNodeHandlerTest.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.SmartDeviceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SwitchDeviceNodeHandlerTest {

    @Mock
    private SmartDeviceService smartDeviceService;

    private SwitchDeviceNodeHandler handler() {
        return new SwitchDeviceNodeHandler(smartDeviceService);
    }

    private final FlowMessage msg = FlowMessage.of(Map.of());

    @Test
    void turnsOnAndPassesMessageThrough() {
        NodeResult result = handler().handle(msg, new NodeConfig(Map.of("deviceId", 42, "action", "on")), null);

        verify(smartDeviceService).turnOn(42L);
        assertFalse(result.outputs().isEmpty());
    }

    @Test
    void turnsOff() {
        handler().handle(msg, new NodeConfig(Map.of("deviceId", 42, "action", "off")), null);
        verify(smartDeviceService).turnOff(42L);
    }

    @Test
    void validateRequiresDeviceIdAndValidAction() {
        assertEquals(2, handler().validate(NodeConfig.empty()).size());
        assertFalse(handler().validate(new NodeConfig(Map.of("deviceId", 1, "action", "toggle"))).isEmpty());
        assertTrue(handler().validate(new NodeConfig(Map.of("deviceId", 1, "action", "on"))).isEmpty());
    }
}
```

- [ ] **Step 2: Tests rot** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

`AlexaAnnounceNodeHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.AlexaAnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aktions-Node: Alexa-Ansage über den bestehenden AlexaAnnouncementService.
 * Platzhalter im Text: {entityId}, {newState}, {oldState}.
 */
@Component
@RequiredArgsConstructor
public class AlexaAnnounceNodeHandler implements NodeHandler {

    private final AlexaAnnouncementService announcementService;

    @Override
    public String type() {
        return "alexa-announce";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string("text").isEmpty()) {
            errors.add("text fehlt");
        }
        String mode = config.string("mode").orElse(null);
        if (mode == null) {
            errors.add("mode fehlt");
        } else {
            try {
                AlexaTtsMode.valueOf(mode);
            } catch (IllegalArgumentException ex) {
                errors.add("mode muss SPEAK oder ANNOUNCE sein");
            }
        }
        if (config.stringList("deviceSerials").isEmpty()) {
            errors.add("deviceSerials fehlt oder ist leer");
        }
        return errors;
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of(
                "text", "Ansagetext; Platzhalter: {entityId}, {newState}, {oldState}",
                "mode", "SPEAK (ohne Signalton) oder ANNOUNCE (mit Signalton)",
                "deviceSerials", "Liste der Alexa-Seriennummern");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        String text = render(config.string("text").orElse(""), message);
        AlexaTtsMode mode = AlexaTtsMode.valueOf(config.string("mode").orElse("ANNOUNCE"));
        announcementService.announce(text, config.stringList("deviceSerials"), mode);
        return NodeResult.single(message);
    }

    private String render(String template, FlowMessage message) {
        return template
                .replace("{entityId}", stringValue(message, "entityId"))
                .replace("{newState}", stringValue(message, "newState"))
                .replace("{oldState}", stringValue(message, "oldState"));
    }

    private String stringValue(FlowMessage message, String key) {
        Object value = message.get(key);
        return value != null ? String.valueOf(value) : "";
    }
}
```

`SwitchDeviceNodeHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.service.SmartDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aktions-Node: schaltet ein SmartDevice (Kasa/Tapo/Meross) ein oder aus.
 * Der resultierende Entity-Zustand kann weitere Flows triggern — gegen
 * Ping-Pong zwischen Flows schützt die rate-limit-Node (Editor-Empfehlung).
 */
@Component
@RequiredArgsConstructor
public class SwitchDeviceNodeHandler implements NodeHandler {

    private final SmartDeviceService smartDeviceService;

    @Override
    public String type() {
        return "switch-device";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.integer("deviceId").isEmpty()) {
            errors.add("deviceId fehlt");
        }
        String action = config.string("action").orElse(null);
        if (action == null || (!action.equals("on") && !action.equals("off"))) {
            errors.add("action muss 'on' oder 'off' sein");
        }
        return errors;
    }

    @Override
    public Map<String, String> configSchema() {
        return Map.of(
                "deviceId", "ID des SmartDevice (siehe Geräte-Seite)",
                "action", "'on' oder 'off'");
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        long deviceId = config.integer("deviceId").orElseThrow();
        String action = config.string("action").orElse("on");
        if ("on".equals(action)) {
            smartDeviceService.turnOn(deviceId);
        } else {
            smartDeviceService.turnOff(deviceId);
        }
        return NodeResult.single(message);
    }
}
```

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="AlexaAnnounceNodeHandlerTest,SwitchDeviceNodeHandlerTest"`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes backend/src/test/java/com/household/manager/flowengine/nodes
git commit -m "feat(flowengine): add alexa announce and switch device action nodes"
```

---

### Task 11: FlowService (CRUD, Deploy, Inject, Startup-Bootstrap)

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowService.java`
- Create: `backend/src/main/java/com/household/manager/flowengine/FlowEngineBootstrap.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/FlowServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

`FlowServiceTest.java`:

```java
package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlowServiceTest {

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.empty(); }
    }

    private static final String VALID_DEF = """
            { "nodes": [ { "id": "t", "type": "test-trigger", "config": {} } ], "wires": [] }
            """;

    @Mock
    private FlowRepository flowRepository;
    @Mock
    private FlowEngine engine;

    private FlowRegistry registry;
    private FlowService service;

    @BeforeEach
    void setUp() {
        registry = new FlowRegistry(List.of(new TestTriggerHandler()));
        registry.setEngine(engine);
        FlowDefinitionParser parser = new FlowDefinitionParser(new ObjectMapper());
        FlowValidator validator = new FlowValidator(List.of(new TestTriggerHandler()),
                mock(com.household.manager.entitystate.EntityStateService.class));
        service = new FlowService(flowRepository, parser, validator, registry, engine);
        lenient().when(flowRepository.save(any(Flow.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Flow flow(Long id, String draft, String deployed, boolean enabled) {
        return Flow.builder().id(id).name("f").enabled(enabled)
                .draftDefinition(draft).deployedDefinition(deployed).build();
    }

    @Test
    void deployValidDraftCopiesToDeployedAndRegisters() {
        Flow entity = flow(1L, VALID_DEF, null, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        ValidationResult result = service.deploy(1L);

        assertTrue(result.valid());
        assertEquals(VALID_DEF, entity.getDeployedDefinition());
        assertNotNull(entity.getDeployedAt());
        assertTrue(registry.graph(1L).isPresent());
    }

    @Test
    void deployInvalidDraftReturnsErrorsAndDoesNotRegister() {
        Flow entity = flow(1L, "{ \"nodes\": [ { \"id\": \"x\", \"type\": \"nope\", \"config\": {} } ], \"wires\": [] }", null, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        ValidationResult result = service.deploy(1L);

        assertFalse(result.valid());
        assertNull(entity.getDeployedDefinition());
        assertTrue(registry.graph(1L).isEmpty());
    }

    @Test
    void disableUndeploysEnableRedeploys() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));

        service.deploy(1L);
        assertTrue(registry.graph(1L).isPresent());

        service.setEnabled(1L, false);
        assertFalse(entity.isEnabled());
        assertTrue(registry.graph(1L).isEmpty());

        service.setEnabled(1L, true);
        assertTrue(registry.graph(1L).isPresent());
    }

    @Test
    void deleteUndeploysAndDeletes() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));
        service.deploy(1L);

        service.delete(1L);

        assertTrue(registry.graph(1L).isEmpty());
        verify(flowRepository).deleteById(1L);
    }

    @Test
    void injectFiresTriggerNodeWithMergedPayload() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));
        service.deploy(1L);

        service.inject(1L, "t", Map.of("newState", "5"));

        verify(engine).emitAsync(eq(1L), eq("t"), eq(0), argThat(msg ->
                "5".equals(msg.get("newState")) && "t".equals(msg.get("triggerNodeId"))));
    }

    @Test
    void injectOnUnknownNodeThrows() {
        Flow entity = flow(1L, VALID_DEF, VALID_DEF, true);
        when(flowRepository.findById(1L)).thenReturn(Optional.of(entity));
        service.deploy(1L);

        assertThrows(IllegalArgumentException.class, () -> service.inject(1L, "ghost", Map.of()));
    }
}
```

- [ ] **Step 2: Test rot** (Kompilierfehler)

- [ ] **Step 3: Implementierung**

`FlowService.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinition;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CRUD + Deploy-Orchestrierung für Flows. Deploy validiert den Draft,
 * kopiert ihn nach deployed und registriert den Flow in der Engine-Registry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowService {

    private final FlowRepository flowRepository;
    private final FlowDefinitionParser parser;
    private final FlowValidator validator;
    private final FlowRegistry registry;
    private final FlowEngine engine;

    @Transactional(readOnly = true)
    public List<Flow> getAll() {
        return flowRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Flow> getById(Long id) {
        return flowRepository.findById(id);
    }

    @Transactional
    public Flow create(String name, String description) {
        Flow flow = Flow.builder().name(name).description(description).enabled(true)
                .draftDefinition("{ \"nodes\": [], \"wires\": [] }").build();
        return flowRepository.save(flow);
    }

    @Transactional
    public Flow update(Long id, String name, String description, String draftDefinition) {
        Flow flow = require(id);
        if (name != null) {
            flow.setName(name);
        }
        if (description != null) {
            flow.setDescription(description);
        }
        if (draftDefinition != null) {
            parser.parse(draftDefinition); // wirft IllegalArgumentException bei kaputtem JSON -> 400
            flow.setDraftDefinition(draftDefinition);
        }
        return flowRepository.save(flow);
    }

    /**
     * Validiert den Draft; bei Erfolg draft -> deployed + Registry-Reload.
     * Bei Fehlern bleibt der bisherige Deploy-Stand unangetastet.
     */
    @Transactional
    public ValidationResult deploy(Long id) {
        Flow flow = require(id);
        FlowDefinition definition = parser.parse(flow.getDraftDefinition());
        ValidationResult result = validator.validate(definition);
        if (!result.valid()) {
            return result;
        }
        flow.setDeployedDefinition(flow.getDraftDefinition());
        flow.setDeployedAt(LocalDateTime.now());
        flowRepository.save(flow);
        if (flow.isEnabled()) {
            registry.deploy(id, definition);
        }
        return result;
    }

    @Transactional
    public Flow setEnabled(Long id, boolean enabled) {
        Flow flow = require(id);
        flow.setEnabled(enabled);
        flowRepository.save(flow);
        if (!enabled) {
            registry.undeploy(id);
        } else if (flow.getDeployedDefinition() != null) {
            registry.deploy(id, parser.parse(flow.getDeployedDefinition()));
        }
        return flow;
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        registry.undeploy(id);
        flowRepository.deleteById(id);
    }

    /** Feuert eine deployte Trigger-Node von Hand (Test-Inject). */
    public void inject(Long flowId, String nodeId, Map<String, Object> payload) {
        FlowGraph graph = registry.graph(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow " + flowId + " ist nicht deployt"));
        FlowNode node = graph.node(nodeId);
        if (node == null || !(registry.handler(node.type()) instanceof TriggerNodeHandler)) {
            throw new IllegalArgumentException("Node '" + nodeId + "' ist keine deployte Trigger-Node");
        }
        Map<String, Object> values = new HashMap<>();
        values.put("timestamp", LocalDateTime.now());
        values.put("triggerNodeId", nodeId);
        if (payload != null) {
            values.putAll(payload);
        }
        engine.emitAsync(flowId, nodeId, 0, FlowMessage.of(values));
    }

    private Flow require(Long id) {
        return flowRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found with ID: " + id));
    }
}
```

`FlowEngineBootstrap.java`:

```java
package com.household.manager.flowengine;

import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Verdrahtet Engine<->Registry (Zirkularität) und lädt beim Start alle
 * deployten, aktiven Flows aus der DB in die Registry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowEngineBootstrap {

    private final FlowRegistry registry;
    private final FlowEngine engine;
    private final FlowRepository flowRepository;
    private final FlowDefinitionParser parser;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        registry.setEngine(engine);
        for (Flow flow : flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()) {
            try {
                registry.deploy(flow.getId(), parser.parse(flow.getDeployedDefinition()));
            } catch (Exception ex) {
                log.error("Flow {} ({}) konnte beim Start nicht geladen werden: {}",
                        flow.getId(), flow.getName(), ex.getMessage());
            }
        }
        log.info("Flow engine started");
    }
}
```

**Achtung:** `registry.setEngine(engine)` muss auch VOR dem ersten Deploy via REST gesetzt sein — `ApplicationReadyEvent` feuert vor Request-Annahme, das reicht.

- [ ] **Step 4: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowServiceTest`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine backend/src/test/java/com/household/manager/flowengine
git commit -m "feat(flowengine): add flow service with deploy orchestration and startup bootstrap"
```

---

### Task 12: REST-API (FlowController + DTOs)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/FlowSummaryResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/FlowDetailResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/CreateFlowRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/UpdateFlowRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/NodeTypeResponse.java`
- Create: `backend/src/main/java/com/household/manager/controller/FlowController.java`
- Test: `backend/src/test/java/com/household/manager/controller/FlowControllerTest.java`

- [ ] **Step 1: Failing Test schreiben**

`FlowControllerTest.java`:

```java
package com.household.manager.controller;

import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.flowengine.*;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.model.entity.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class FlowControllerTest {

    private static class TestTriggerHandler implements TriggerNodeHandler {
        public String type() { return "test-trigger"; }
        public int outputPorts() { return 1; }
        public List<String> validate(NodeConfig config) { return List.of(); }
        public Optional<String> watchedEntityId(NodeConfig config) { return Optional.empty(); }
        public Map<String, String> configSchema() { return Map.of("k", "beschreibung"); }
    }

    @Mock
    private FlowService flowService;
    @Mock
    private DebugBuffer debugBuffer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FlowController controller = new FlowController(flowService, debugBuffer,
                List.of(new TestTriggerHandler()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Flow flow() {
        return Flow.builder().id(1L).name("Test").enabled(true)
                .draftDefinition("{ \"nodes\": [], \"wires\": [] }").build();
    }

    @Test
    void listsFlows() throws Exception {
        when(flowService.getAll()).thenReturn(List.of(flow()));

        mockMvc.perform(get("/v1/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].deployed").value(false));
    }

    @Test
    void createsFlow() throws Exception {
        when(flowService.create("Neu", "Desc")).thenReturn(flow());

        mockMvc.perform(post("/v1/flows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neu\",\"description\":\"Desc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test"));
    }

    @Test
    void getReturns404ForUnknownFlow() throws Exception {
        when(flowService.getById(9L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/flows/9")).andExpect(status().isNotFound());
    }

    @Test
    void deployReturns400WithErrorsWhenInvalid() throws Exception {
        when(flowService.deploy(1L)).thenReturn(new ValidationResult(List.of("kaputt"), List.of()));

        mockMvc.perform(post("/v1/flows/1/deploy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("kaputt"));
    }

    @Test
    void deployReturns200WithWarningsWhenValid() throws Exception {
        when(flowService.deploy(1L)).thenReturn(new ValidationResult(List.of(), List.of("warnung")));

        mockMvc.perform(post("/v1/flows/1/deploy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value("warnung"));
    }

    @Test
    void injectDelegatesToService() throws Exception {
        mockMvc.perform(post("/v1/flows/1/nodes/t/inject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"newState\":\"5\"}}"))
                .andExpect(status().isAccepted());

        verify(flowService).inject(eq(1L), eq("t"), any());
    }

    @Test
    void listsNodeTypes() throws Exception {
        mockMvc.perform(get("/v1/flows/node-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("test-trigger"))
                .andExpect(jsonPath("$[0].trigger").value(true))
                .andExpect(jsonPath("$[0].outputPorts").value(1))
                .andExpect(jsonPath("$[0].configSchema.k").value("beschreibung"));
    }
}
```

- [ ] **Step 2: Test rot** (Kompilierfehler)

- [ ] **Step 3: DTOs implementieren**

`FlowSummaryResponse.java`:

```java
package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record FlowSummaryResponse(
        Long id, String name, String description, boolean enabled, boolean deployed,
        LocalDateTime deployedAt, LocalDateTime updatedAt) {
}
```

`FlowDetailResponse.java`:

```java
package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record FlowDetailResponse(
        Long id, String name, String description, boolean enabled, boolean deployed,
        String draftDefinition, String deployedDefinition,
        LocalDateTime deployedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
```

`CreateFlowRequest.java`:

```java
package com.household.manager.dto;

public record CreateFlowRequest(String name, String description) {
}
```

`UpdateFlowRequest.java`:

```java
package com.household.manager.dto;

public record UpdateFlowRequest(String name, String description, String draftDefinition) {
}
```

`NodeTypeResponse.java`:

```java
package com.household.manager.dto;

import lombok.Builder;

import java.util.Map;

@Builder
public record NodeTypeResponse(String type, int outputPorts, boolean trigger, Map<String, String> configSchema) {
}
```

- [ ] **Step 4: Controller implementieren**

`FlowController.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.*;
import com.household.manager.flowengine.DebugBuffer;
import com.household.manager.flowengine.FlowService;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.ValidationResult;
import com.household.manager.model.entity.Flow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * REST-API der Flow-Engine (CRUD, Deploy, Inject, Debug, Node-Katalog).
 */
@RestController
@RequestMapping("/v1/flows")
public class FlowController {

    private final FlowService flowService;
    private final DebugBuffer debugBuffer;
    private final List<NodeHandler> handlers;

    public FlowController(FlowService flowService, DebugBuffer debugBuffer, List<NodeHandler> handlers) {
        this.flowService = flowService;
        this.debugBuffer = debugBuffer;
        this.handlers = handlers;
    }

    @GetMapping
    public List<FlowSummaryResponse> getFlows() {
        return flowService.getAll().stream().map(this::toSummary).toList();
    }

    @PostMapping
    public FlowDetailResponse createFlow(@RequestBody CreateFlowRequest request) {
        return toDetail(flowService.create(request.name(), request.description()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlowDetailResponse> getFlow(@PathVariable Long id) {
        return flowService.getById(id)
                .map(flow -> ResponseEntity.ok(toDetail(flow)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public FlowDetailResponse updateFlow(@PathVariable Long id, @RequestBody UpdateFlowRequest request) {
        return toDetail(flowService.update(id, request.name(), request.description(), request.draftDefinition()));
    }

    @PostMapping("/{id}/deploy")
    public ResponseEntity<ValidationResult> deploy(@PathVariable Long id) {
        ValidationResult result = flowService.deploy(id);
        return result.valid() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/{id}/enable")
    public FlowSummaryResponse enable(@PathVariable Long id) {
        return toSummary(flowService.setEnabled(id, true));
    }

    @PostMapping("/{id}/disable")
    public FlowSummaryResponse disable(@PathVariable Long id) {
        return toSummary(flowService.setEnabled(id, false));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlow(@PathVariable Long id) {
        flowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/nodes/{nodeId}/inject")
    public ResponseEntity<Void> inject(@PathVariable Long id, @PathVariable String nodeId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body != null && body.get("payload") instanceof Map<?, ?> p
                ? castPayload(p) : Map.of();
        flowService.inject(id, nodeId, payload);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/nodes/{nodeId}/debug")
    public List<DebugBuffer.DebugEntry> debugEntries(@PathVariable Long id, @PathVariable String nodeId) {
        return debugBuffer.entries(id, nodeId);
    }

    @GetMapping("/node-types")
    public List<NodeTypeResponse> nodeTypes() {
        return handlers.stream()
                .map(handler -> NodeTypeResponse.builder()
                        .type(handler.type())
                        .outputPorts(handler.outputPorts())
                        .trigger(handler instanceof TriggerNodeHandler)
                        .configSchema(handler.configSchema())
                        .build())
                .sorted(Comparator.comparing(NodeTypeResponse::type))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castPayload(Map<?, ?> payload) {
        return (Map<String, Object>) payload;
    }

    private FlowSummaryResponse toSummary(Flow flow) {
        return FlowSummaryResponse.builder()
                .id(flow.getId()).name(flow.getName()).description(flow.getDescription())
                .enabled(flow.isEnabled()).deployed(flow.getDeployedDefinition() != null)
                .deployedAt(flow.getDeployedAt()).updatedAt(flow.getUpdatedAt())
                .build();
    }

    private FlowDetailResponse toDetail(Flow flow) {
        return FlowDetailResponse.builder()
                .id(flow.getId()).name(flow.getName()).description(flow.getDescription())
                .enabled(flow.isEnabled()).deployed(flow.getDeployedDefinition() != null)
                .draftDefinition(flow.getDraftDefinition()).deployedDefinition(flow.getDeployedDefinition())
                .deployedAt(flow.getDeployedAt()).createdAt(flow.getCreatedAt()).updatedAt(flow.getUpdatedAt())
                .build();
    }
}
```

**Hinweis Routen-Reihenfolge:** `GET /v1/flows/node-types` und `GET /v1/flows/{id}` — Spring matcht das statische Segment `node-types` vor der `{id}`-Variable, kein Konflikt.

- [ ] **Step 5: Tests grün**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=FlowControllerTest`
Expected: Tests run: 7, Failures: 0

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto backend/src/main/java/com/household/manager/controller/FlowController.java backend/src/test/java/com/household/manager/controller/FlowControllerTest.java
git commit -m "feat(flowengine): add REST API for flows, deploy, inject and node type catalog"
```

---

### Task 13: Gesamtverifikation

- [ ] **Step 1: Alle Flow-Engine-Tests**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="Flow*Test,*NodeHandlerTest,*TriggerHandlerTest,StateComparatorTest,DebugBufferTest,EntityConditionHandlerTest"`
Expected: alle grün

- [ ] **Step 2: Kompletter Backend-Testlauf**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn test`
Expected: nur die bekannten DB-Umgebungsfehler (`HouseholdManagerApplicationTests`, `HealthControllerTest`), KEINE neuen Failures.

- [ ] **Step 3: Spec-Abgleich** (`docs/superpowers/specs/2026-07-09-flow-engine-design.md`)

- [ ] Tabelle `flows` mit draft/deployed + Master-Include (Task 1)
- [ ] JSON-Definition mit nodes/wires/position/ports (Task 2)
- [ ] Message-Passing, Immutabilität statt Kopie, Hop-Limit 100, Fehlerisolation pro Zweig (Task 3+5)
- [ ] Asynchroner Listener auf eigenem Executor, kein @TransactionalEventListener (Task 6)
- [ ] Alle 8 Node-Typen inkl. Validierung (Tasks 6–10)
- [ ] Verweildauer-Logik: Timer + Re-Check + Storno (Task 6)
- [ ] Deploy-Validierung mit Fehlern (400) (Tasks 4, 11, 12)
- [ ] Startup lädt deployte enabled Flows; enable/disable; delete (Task 11)
- [ ] REST komplett inkl. inject + debug + node-types (Task 12)

- [ ] **Step 4: Manueller Smoke-Test (dokumentieren, nur wenn lokale DB läuft — sonst überspringen und im Report vermerken)**

Flow per REST anlegen (curl), deployen, injecten, Debug-Puffer prüfen.

---

## Anmerkungen für die Ausführung

- Tasks 6–12 hängen von Tasks 2–5 ab; innerhalb 6–10 sind die Node-Handler unabhängig voneinander.
- Kanonische Regel aus Stufe 2 gilt weiter: bestehende Tests, die Klassen direkt instanziieren und durch neue Konstruktor-Parameter brechen, minimal fixen und mitcommitten (als Abweichung melden).
- `AlexaAnnouncementService` und `SmartDeviceService` sind immer vorhandene Beans; `AnkerSolix`-artige @ConditionalOnProperty-Fälle gibt es unter den Aktions-Nodes nicht.
