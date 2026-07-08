# Entity-/State-Schicht Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generische, Home-Assistant-inspirierte Entity-/State-Schicht: alle Integrationen spiegeln ihre Zustände in eine `entity_states`-Tabelle; Zustandsänderungen feuern `EntityStateChangedEvent` (Spring-Events); REST-API + Frontend-Übersichtsseite.

**Architecture:** Spiegel-Schicht (nicht-invasiv). Neue Facade `EntityStateService.reportState(...)` ist die einzige Schreibstelle; sie delegiert an einen transaktionalen `EntityStateWriter` (REQUIRES_NEW, damit Fehler nie die Host-Transaktion der Integration vergiften) und publiziert bei Wertänderung ein Event **nach** dem Commit. Pro Integration ein Mapper + ein einzeiliger Hook. Spec: `docs/superpowers/specs/2026-07-08-entity-state-layer-design.md`.

**Tech Stack:** Spring Boot 3.4.1, Java 21, Liquibase, Lombok, JUnit 5 + Mockito; Angular 19 standalone, SCSS.

**Abweichung von der Spec:** REST-Basis ist `/api/v1/entities` (nicht `/api/entities`) — konsistent mit den übrigen Controllern (`/v1/...` bei `server.servlet.context-path=/api`).

---

## Build-Umgebung (für jeden Backend-Schritt)

Vor jedem `mvn`-Aufruf (Bash, aus `backend/`):

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend
```

**Bekannt und zu ignorieren:** `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal fehl („Access denied for user 'root'@'localhost'") — Test-DB ist auf dieser Maschine nicht erreichbar, vorbestehend. Einzeltests deshalb immer mit `-Dtest=...` ausführen.

**Wichtige Projektregeln:**
- Alle JPA-Repositories MÜSSEN in `com.household.manager.repository` liegen (JpaConfig scannt nur dieses Package).
- Schemaänderungen NUR über Liquibase-Changesets.
- Lombok verwenden (`@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`).

**KANONISCHES HOOK-MUSTER (Review-Erkenntnis aus Task 6, gilt für ALLE Hooks in Tasks 6–12):**
Das Mapping läuft außerhalb des try/catch der Facade — deshalb muss der private Report-Helper in der Integration selbst die Fehlergrenze sein. Jeder Hook-Helper wrappt seinen GESAMTEN Rumpf (Mapping + reportState-Aufrufe) in `try { ... } catch (Exception ex) { log.warn("Failed to report entity state ...: {}", ex.getMessage()); }`. Damit kann weder ein Mapper-Fehler (z. B. `EntityIds.build` bei leerem Slug) noch sonst irgendetwas aus der Spiegel-Schicht eine Integration brechen, eine Polling-Schleife abbrechen oder eine Transaktion zurückrollen. Die Code-Blöcke der Tasks 7–12 unten sind entsprechend zu interpretieren: Helper-Rumpf immer so wrappen.

---

### Task 1: Enums, JPA-Entity, Repository, Liquibase-Changeset

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityDomain.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/EntityState.java`
- Create: `backend/src/main/java/com/household/manager/repository/EntityStateRepository.java`
- Create: `backend/src/main/resources/db/changelog/changes/20260708-0029-create-entity-states-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (Include am Ende, nach Zeile 66)

- [ ] **Step 1: Enums anlegen**

`backend/src/main/java/com/household/manager/entitystate/EntityDomain.java`:

```java
package com.household.manager.entitystate;

/**
 * Domain einer Entität (Home-Assistant-Stil). Bestimmt das Präfix der Entity-ID.
 */
public enum EntityDomain {
    SWITCH,
    SENSOR,
    BINARY_SENSOR;

    /** Präfix für Entity-IDs, z. B. "sensor" oder "binary_sensor". */
    public String idPrefix() {
        return name().toLowerCase();
    }
}
```

`backend/src/main/java/com/household/manager/entitystate/EntitySource.java`:

```java
package com.household.manager.entitystate;

/**
 * Herkunftsintegration einer Entität.
 */
public enum EntitySource {
    KASA,
    TAPO,
    MEROSS,
    ZIGBEE,
    SHELLY,
    TASMOTA,
    AIRROHR,
    WEATHER,
    ANKER_SOLIX
}
```

- [ ] **Step 2: JPA-Entity anlegen**

`backend/src/main/java/com/household/manager/model/entity/EntityState.java`:

```java
package com.household.manager.model.entity;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Aktueller Zustand einer generischen Entität (Spiegel-Schicht über den Integrationen).
 * Eine Zeile pro Entität; Historie liegt in den Fachtabellen der Integrationen.
 */
@Entity
@Table(name = "entity_states")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Eindeutige, stabile Entity-ID, z. B. "sensor.zigbee_wohnzimmer_temperature". */
    @Column(name = "entity_id", nullable = false, unique = true, length = 150)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 20)
    private EntityDomain domain;

    /** Anzeigename; wird bei jedem Update mitaktualisiert. */
    @Column(name = "friendly_name", nullable = false, length = 255)
    private String friendlyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private EntitySource source;

    /** Stabile ID im Quellsystem (deviceId, Seriennummer, Sensor-ID). */
    @Column(name = "source_ref", nullable = false, length = 255)
    private String sourceRef;

    /** Aktueller Zustand als String ("on", "21.5", "unavailable", "unknown"). */
    @Column(name = "state", nullable = false, length = 255)
    private String state;

    /** Attribute als JSON-String (unit, deviceClass, Zusatzwerte). */
    @Column(name = "attributes", columnDefinition = "TEXT")
    private String attributes;

    /** Zeitpunkt der letzten Wertänderung. */
    @Column(name = "last_changed", nullable = false)
    private LocalDateTime lastChanged;

    /** Zeitpunkt des letzten Updates (auch ohne Wertänderung). */
    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Repository anlegen**

`backend/src/main/java/com/household/manager/repository/EntityStateRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntityStateRepository extends JpaRepository<EntityState, Long> {

    Optional<EntityState> findByEntityId(String entityId);

    List<EntityState> findAllByOrderByEntityIdAsc();

    List<EntityState> findByDomainOrderByEntityIdAsc(EntityDomain domain);

    List<EntityState> findBySourceOrderByEntityIdAsc(EntitySource source);

    List<EntityState> findByDomainAndSourceOrderByEntityIdAsc(EntityDomain domain, EntitySource source);

    void deleteByEntityId(String entityId);
}
```

- [ ] **Step 4: Liquibase-Changeset anlegen**

`backend/src/main/resources/db/changelog/changes/20260708-0029-create-entity-states-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260708-0029-create-entity-states-table" author="household-manager">
        <preConditions onFail="MARK_RAN">
            <not>
                <tableExists tableName="entity_states"/>
            </not>
        </preConditions>
        <createTable tableName="entity_states">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="entity_id" type="VARCHAR(150)">
                <constraints nullable="false" unique="true" uniqueConstraintName="uk_entity_states_entity_id"/>
            </column>
            <column name="domain" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="friendly_name" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="source" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="source_ref" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="state" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="attributes" type="TEXT"/>
            <column name="last_changed" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="last_updated" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <createIndex tableName="entity_states" indexName="idx_entity_states_domain">
            <column name="domain"/>
        </createIndex>

        <createIndex tableName="entity_states" indexName="idx_entity_states_source">
            <column name="source"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

In `db.changelog-master.xml` vor `</databaseChangeLog>` einfügen:

```xml
    <!-- Generic Entity/State Layer -->
    <include file="db/changelog/changes/20260708-0029-create-entity-states-table.xml"/>
```

- [ ] **Step 5: Kompilieren**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate backend/src/main/java/com/household/manager/model/entity/EntityState.java backend/src/main/java/com/household/manager/repository/EntityStateRepository.java backend/src/main/resources/db/changelog
git commit -m "feat(entitystate): add EntityState entity, repository and Liquibase changeset"
```

---

### Task 2: EntityIds-Utility (Entity-ID-Bildung + Slugify)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityIds.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityIdsTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/EntityIdsTest.java`:

```java
package com.household.manager.entitystate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityIdsTest {

    @Test
    void buildsIdFromDomainSourceAndRef() {
        String id = EntityIds.build(EntityDomain.SWITCH, EntitySource.KASA, "8006A1B2", null);
        assertEquals("switch.kasa_8006a1b2", id);
    }

    @Test
    void buildsIdWithMeasurementSuffix() {
        String id = EntityIds.build(EntityDomain.SENSOR, EntitySource.ZIGBEE, "Wohnzimmer Sensor", "temperature");
        assertEquals("sensor.zigbee_wohnzimmer_sensor_temperature", id);
    }

    @Test
    void slugReplacesUmlautsAndSpecialCharacters() {
        assertEquals("kueche_tuer", EntityIds.slug("Küche/Tür"));
    }

    @Test
    void slugCollapsesConsecutiveSeparatorsAndTrims() {
        assertEquals("bad_sensor", EntityIds.slug("  Bad -- Sensor  "));
    }

    @Test
    void binarySensorDomainUsesUnderscorePrefix() {
        String id = EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.ZIGBEE, "Tür", "contact");
        assertEquals("binary_sensor.zigbee_tuer_contact", id);
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityIdsTest`
Expected: Kompilierfehler „cannot find symbol: EntityIds“

- [ ] **Step 3: Implementierung**

`backend/src/main/java/com/household/manager/entitystate/EntityIds.java`:

```java
package com.household.manager.entitystate;

/**
 * Bildet stabile Entity-IDs nach dem Schema
 * {@code <domain>.<source>_<slug(ref)>[_<suffix>]}.
 * IDs werden maschinell aus stabilen Referenzen erzeugt, nie aus änderbaren Anzeigenamen.
 */
public final class EntityIds {

    private EntityIds() {
    }

    public static String build(EntityDomain domain, EntitySource source, String sourceRef, String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append(domain.idPrefix())
                .append('.')
                .append(slug(source.name()))
                .append('_')
                .append(slug(sourceRef));
        if (suffix != null && !suffix.isBlank()) {
            sb.append('_').append(slug(suffix));
        }
        return sb.toString();
    }

    /**
     * Normalisiert beliebigen Text zu einem ID-Segment: Kleinbuchstaben,
     * Umlaute transliteriert, alles andere zu '_' (ohne Doppel-/Randunterstriche).
     */
    public static String slug(String input) {
        String lower = input.toLowerCase()
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss");
        String replaced = lower.replaceAll("[^a-z0-9]+", "_");
        return replaced.replaceAll("^_+|_+$", "");
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityIdsTest`
Expected: Tests run: 5, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntityIds.java backend/src/test/java/com/household/manager/entitystate/EntityIdsTest.java
git commit -m "feat(entitystate): add EntityIds utility for stable entity id generation"
```

---

### Task 3: EntityStateUpdate, Event, Writer und Facade

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityStateUpdate.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityStateChangedEvent.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityStateWriter.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityStateService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityStateWriterTest.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityStateServiceTest.java`

**Designentscheidung (wichtig fürs Verständnis):** `EntityStateService` (Facade, kein `@Transactional`, fängt ALLE Fehler) → `EntityStateWriter` (`@Transactional(REQUIRES_NEW)`). Zwei Klassen, weil Springs Proxy-Transaktionen bei Selbstaufruf nicht greifen und weil ein Commit-Fehler der Spiegel-Schicht sonst die Transaktion der aufrufenden Integration (z. B. `SmartDeviceService.turnOn`) mit abräumen würde. Das Event wird von der Facade **nach** erfolgreichem Writer-Aufruf publiziert (also nach Commit), damit Listener den committeten Zustand sehen.

- [ ] **Step 1: DTO und Event anlegen**

`backend/src/main/java/com/household/manager/entitystate/EntityStateUpdate.java`:

```java
package com.household.manager.entitystate;

import lombok.Builder;

import java.util.Map;

/**
 * Zustandsmeldung einer Integration an die Entity-Schicht.
 * Unbekannte Entity-IDs werden automatisch registriert (Upsert).
 */
@Builder
public record EntityStateUpdate(
        String entityId,
        EntityDomain domain,
        EntitySource source,
        String sourceRef,
        String friendlyName,
        String state,
        Map<String, Object> attributes
) {
}
```

`backend/src/main/java/com/household/manager/entitystate/EntityStateChangedEvent.java`:

```java
package com.household.manager.entitystate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Wird publiziert, wenn sich der Zustandswert einer Entität geändert hat
 * (nicht bei bloßer Aktualisierung ohne Wertänderung).
 * Grundstein für die spätere Regel-Engine: dort einfach per @EventListener konsumieren.
 */
public record EntityStateChangedEvent(
        String entityId,
        String oldState,
        String newState,
        Map<String, Object> attributes,
        LocalDateTime timestamp
) {
}
```

- [ ] **Step 2: Failing Tests für den Writer schreiben**

`backend/src/test/java/com/household/manager/entitystate/EntityStateWriterTest.java`:

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityStateWriterTest {

    @Mock
    private EntityStateRepository repository;

    private EntityStateWriter writer;

    @BeforeEach
    void setUp() {
        writer = new EntityStateWriter(repository, new ObjectMapper());
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private EntityStateUpdate update(String state) {
        return EntityStateUpdate.builder()
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .state(state)
                .attributes(Map.of("unit", "°C"))
                .build();
    }

    @Test
    void newEntityIsCreatedAndEventReturned() {
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.empty());

        Optional<EntityStateChangedEvent> event = writer.upsert(update("21.5"));

        assertTrue(event.isPresent());
        assertEquals("unknown", event.get().oldState());
        assertEquals("21.5", event.get().newState());

        ArgumentCaptor<EntityState> captor = ArgumentCaptor.forClass(EntityState.class);
        verify(repository).save(captor.capture());
        EntityState saved = captor.getValue();
        assertEquals("21.5", saved.getState());
        assertEquals(EntityDomain.SENSOR, saved.getDomain());
        assertNotNull(saved.getLastChanged());
        assertNotNull(saved.getLastUpdated());
    }

    @Test
    void unchangedStateBumpsLastUpdatedButReturnsNoEvent() {
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        EntityState existing = existingEntity("21.5", earlier);
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.of(existing));

        Optional<EntityStateChangedEvent> event = writer.upsert(update("21.5"));

        assertTrue(event.isEmpty());
        assertEquals(earlier, existing.getLastChanged());
        assertTrue(existing.getLastUpdated().isAfter(earlier));
    }

    @Test
    void changedStateUpdatesLastChangedAndReturnsEvent() {
        LocalDateTime earlier = LocalDateTime.now().minusHours(1);
        EntityState existing = existingEntity("20.0", earlier);
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.of(existing));

        Optional<EntityStateChangedEvent> event = writer.upsert(update("21.5"));

        assertTrue(event.isPresent());
        assertEquals("20.0", event.get().oldState());
        assertEquals("21.5", event.get().newState());
        assertTrue(existing.getLastChanged().isAfter(earlier));
    }

    @Test
    void friendlyNameIsRefreshedOnEveryUpdate() {
        EntityState existing = existingEntity("21.5", LocalDateTime.now().minusHours(1));
        existing.setFriendlyName("Alter Name");
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.of(existing));

        writer.upsert(update("21.5"));

        assertEquals("Wohnzimmer Temperatur", existing.getFriendlyName());
    }

    @Test
    void nullStateIsStoredAsUnknown() {
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(Optional.empty());

        Optional<EntityStateChangedEvent> event = writer.upsert(update(null));

        assertTrue(event.isEmpty());
    }

    private EntityState existingEntity(String state, LocalDateTime timestamps) {
        return EntityState.builder()
                .id(1L)
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .state(state)
                .lastChanged(timestamps)
                .lastUpdated(timestamps)
                .build();
    }
}
```

- [ ] **Step 3: Tests ausführen — müssen fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityStateWriterTest`
Expected: Kompilierfehler „cannot find symbol: EntityStateWriter“

- [ ] **Step 4: Writer implementieren**

`backend/src/main/java/com/household/manager/entitystate/EntityStateWriter.java`:

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Transaktionaler Upsert für Entitätszustände. REQUIRES_NEW, damit ein Fehler
 * der Spiegel-Schicht niemals die Transaktion der aufrufenden Integration vergiftet.
 * Nur von {@link EntityStateService} aufzurufen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntityStateWriter {

    static final String STATE_UNKNOWN = "unknown";

    private final EntityStateRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Legt die Entität bei Bedarf an und aktualisiert ihren Zustand.
     *
     * @return Event, wenn sich der Zustandswert geändert hat; sonst leer
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<EntityStateChangedEvent> upsert(EntityStateUpdate update) {
        LocalDateTime now = LocalDateTime.now();
        String newState = update.state() != null ? update.state() : STATE_UNKNOWN;

        EntityState entity = repository.findByEntityId(update.entityId())
                .orElseGet(() -> EntityState.builder()
                        .entityId(update.entityId())
                        .domain(update.domain())
                        .source(update.source())
                        .sourceRef(update.sourceRef())
                        .state(STATE_UNKNOWN)
                        .lastChanged(now)
                        .build());

        String oldState = entity.getState();
        entity.setFriendlyName(update.friendlyName());
        entity.setAttributes(serializeAttributes(update.attributes()));
        entity.setLastUpdated(now);

        boolean changed = !newState.equals(oldState);
        if (changed) {
            entity.setState(newState);
            entity.setLastChanged(now);
        }
        repository.save(entity);

        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new EntityStateChangedEvent(
                update.entityId(), oldState, newState, update.attributes(), now));
    }

    private String serializeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize entity attributes: {}", ex.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 5: Writer-Tests ausführen — müssen grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityStateWriterTest`
Expected: Tests run: 5, Failures: 0

- [ ] **Step 6: Failing Tests für die Facade schreiben**

`backend/src/test/java/com/household/manager/entitystate/EntityStateServiceTest.java`:

```java
package com.household.manager.entitystate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityStateServiceTest {

    @Mock
    private EntityStateWriter writer;

    @Mock
    private com.household.manager.repository.EntityStateRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EntityStateService service;

    private EntityStateUpdate update() {
        return EntityStateUpdate.builder()
                .entityId("switch.kasa_abc")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Steckdose")
                .state("on")
                .attributes(Map.of())
                .build();
    }

    @Test
    void publishesEventWhenWriterReportsChange() {
        EntityStateChangedEvent event = new EntityStateChangedEvent(
                "switch.kasa_abc", "off", "on", Map.of(), LocalDateTime.now());
        when(writer.upsert(any())).thenReturn(Optional.of(event));

        service.reportState(update());

        verify(eventPublisher).publishEvent(event);
    }

    @Test
    void publishesNoEventWhenStateUnchanged() {
        when(writer.upsert(any())).thenReturn(Optional.empty());

        service.reportState(update());

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void swallowsWriterExceptionsSoCallerIsNeverBroken() {
        when(writer.upsert(any())).thenThrow(new RuntimeException("DB down"));

        assertDoesNotThrow(() -> service.reportState(update()));
    }

    @Test
    void swallowsListenerExceptionsFromEventPublishing() {
        when(writer.upsert(any())).thenReturn(Optional.of(new EntityStateChangedEvent(
                "switch.kasa_abc", "off", "on", Map.of(), LocalDateTime.now())));
        doThrow(new RuntimeException("listener failed")).when(eventPublisher).publishEvent(any(Object.class));

        assertDoesNotThrow(() -> service.reportState(update()));
    }
}
```

- [ ] **Step 7: Tests ausführen — müssen fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityStateServiceTest`
Expected: Kompilierfehler „cannot find symbol: EntityStateService“

- [ ] **Step 8: Facade implementieren**

`backend/src/main/java/com/household/manager/entitystate/EntityStateService.java`:

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Facade der Entity-/State-Schicht und einzige Schreibstelle.
 * <p>
 * {@link #reportState} ist bewusst absolut fehlertolerant: Persistenz- oder
 * Listener-Fehler werden geloggt und niemals an die aufrufende Integration
 * weitergegeben (Polling/MQTT/Schaltbefehle dürfen dadurch nie brechen).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EntityStateService {

    private final EntityStateWriter writer;
    private final EntityStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Meldet einen Entitätszustand (Upsert). Publiziert bei Wertänderung ein
     * {@link EntityStateChangedEvent} nach erfolgreichem Commit.
     */
    public void reportState(EntityStateUpdate update) {
        try {
            Optional<EntityStateChangedEvent> event = writer.upsert(update);
            event.ifPresent(this::publishSafely);
        } catch (Exception ex) {
            log.warn("Failed to report entity state for {}: {}", update.entityId(), ex.getMessage());
        }
    }

    private void publishSafely(EntityStateChangedEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception ex) {
            log.warn("Entity state event listener failed for {}: {}", event.entityId(), ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<EntityState> getAll() {
        return repository.findAllByOrderByEntityIdAsc();
    }

    @Transactional(readOnly = true)
    public List<EntityState> find(EntityDomain domain, EntitySource source) {
        if (domain != null && source != null) {
            return repository.findByDomainAndSourceOrderByEntityIdAsc(domain, source);
        }
        if (domain != null) {
            return repository.findByDomainOrderByEntityIdAsc(domain);
        }
        if (source != null) {
            return repository.findBySourceOrderByEntityIdAsc(source);
        }
        return repository.findAllByOrderByEntityIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<EntityState> getByEntityId(String entityId) {
        return repository.findByEntityId(entityId);
    }

    @Transactional
    public boolean deleteByEntityId(String entityId) {
        if (repository.findByEntityId(entityId).isEmpty()) {
            return false;
        }
        repository.deleteByEntityId(entityId);
        return true;
    }
}
```

- [ ] **Step 9: Alle Tests des Packages ausführen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="EntityState*Test,EntityIdsTest"`
Expected: alle grün (9 Tests)

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate backend/src/test/java/com/household/manager/entitystate
git commit -m "feat(entitystate): add reportState facade with change detection and state-changed events"
```

---

### Task 4: Debug-Logging-Listener

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityStateLoggingListener.java`

In Stufe 1 ist dies der einzige Event-Konsument; er macht Zustandsänderungen im Log sichtbar. Kein eigener Test (reine Log-Weiterleitung ohne Logik).

- [ ] **Step 1: Listener anlegen**

```java
package com.household.manager.entitystate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loggt jede Zustandsänderung. Einziger Event-Konsument in Ausbaustufe 1;
 * die spätere Regel-Engine hört auf dieselben Events.
 */
@Component
@Slf4j
public class EntityStateLoggingListener {

    @EventListener
    public void onStateChanged(EntityStateChangedEvent event) {
        log.debug("Entity {} changed: {} -> {}", event.entityId(), event.oldState(), event.newState());
    }
}
```

- [ ] **Step 2: Kompilieren + Commit**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntityStateLoggingListener.java
git commit -m "feat(entitystate): log entity state changes via event listener"
```

---

### Task 5: REST-API (`EntityStateController` + Response-DTO)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/EntityStateResponse.java`
- Create: `backend/src/main/java/com/household/manager/controller/EntityStateController.java`
- Test: `backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java`

- [ ] **Step 1: Failing Controller-Test schreiben (Standalone-MockMvc, keine DB)**

`backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java`:

```java
package com.household.manager.controller;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class EntityStateControllerTest {

    @Mock
    private EntityStateService entityStateService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EntityStateController(entityStateService)).build();
    }

    private EntityState sensor() {
        return EntityState.builder()
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .state("21.5")
                .attributes("{\"unit\":\"°C\"}")
                .lastChanged(LocalDateTime.of(2026, 7, 8, 12, 0))
                .lastUpdated(LocalDateTime.of(2026, 7, 8, 12, 5))
                .build();
    }

    @Test
    void listsAllEntities() throws Exception {
        when(entityStateService.find(null, null)).thenReturn(List.of(sensor()));

        mockMvc.perform(get("/v1/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value("sensor.zigbee_wohnzimmer_temperature"))
                .andExpect(jsonPath("$[0].state").value("21.5"))
                .andExpect(jsonPath("$[0].attributes.unit").value("°C"));
    }

    @Test
    void filtersByDomainAndSource() throws Exception {
        when(entityStateService.find(EntityDomain.SENSOR, EntitySource.ZIGBEE)).thenReturn(List.of(sensor()));

        mockMvc.perform(get("/v1/entities").param("domain", "SENSOR").param("source", "ZIGBEE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].domain").value("SENSOR"));
    }

    @Test
    void returnsSingleEntityWithDotsInId() throws Exception {
        when(entityStateService.getByEntityId("sensor.zigbee_wohnzimmer_temperature"))
                .thenReturn(Optional.of(sensor()));

        mockMvc.perform(get("/v1/entities/sensor.zigbee_wohnzimmer_temperature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendlyName").value("Wohnzimmer Temperatur"));
    }

    @Test
    void returns404ForUnknownEntity() throws Exception {
        when(entityStateService.getByEntityId("sensor.unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/entities/sensor.unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesEntity() throws Exception {
        when(entityStateService.deleteByEntityId("sensor.zigbee_wohnzimmer_temperature")).thenReturn(true);

        mockMvc.perform(delete("/v1/entities/sensor.zigbee_wohnzimmer_temperature"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404ForUnknownEntity() throws Exception {
        when(entityStateService.deleteByEntityId("sensor.unknown")).thenReturn(false);

        mockMvc.perform(delete("/v1/entities/sensor.unknown"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityStateControllerTest`
Expected: Kompilierfehler „cannot find symbol: EntityStateController“

- [ ] **Step 3: DTO implementieren**

`backend/src/main/java/com/household/manager/dto/EntityStateResponse.java`:

```java
package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * API-Repräsentation einer Entität mit aktuellem Zustand.
 */
@Builder
public record EntityStateResponse(
        String entityId,
        String domain,
        String source,
        String sourceRef,
        String friendlyName,
        String state,
        Map<String, Object> attributes,
        LocalDateTime lastChanged,
        LocalDateTime lastUpdated
) {
}
```

- [ ] **Step 4: Controller implementieren**

`backend/src/main/java/com/household/manager/controller/EntityStateController.java`:

```java
package com.household.manager.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.EntityStateResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.EntityState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST-API für die generische Entity-/State-Schicht.
 */
@RestController
@RequestMapping("/v1/entities")
@Slf4j
public class EntityStateController {

    private final EntityStateService entityStateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EntityStateController(EntityStateService entityStateService) {
        this.entityStateService = entityStateService;
    }

    @GetMapping
    public List<EntityStateResponse> getEntities(
            @RequestParam(required = false) EntityDomain domain,
            @RequestParam(required = false) EntitySource source) {
        return entityStateService.find(domain, source).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<EntityStateResponse> getEntity(@PathVariable String entityId) {
        return entityStateService.getByEntityId(entityId)
                .map(entity -> ResponseEntity.ok(toResponse(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{entityId}")
    public ResponseEntity<Void> deleteEntity(@PathVariable String entityId) {
        boolean deleted = entityStateService.deleteByEntityId(entityId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private EntityStateResponse toResponse(EntityState entity) {
        return EntityStateResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .sourceRef(entity.getSourceRef())
                .friendlyName(entity.getFriendlyName())
                .state(entity.getState())
                .attributes(parseAttributes(entity.getAttributes()))
                .lastChanged(entity.getLastChanged())
                .lastUpdated(entity.getLastUpdated())
                .build();
    }

    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("Failed to parse entity attributes: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }
}
```

**Hinweis:** Spring Boot 3 nutzt den `PathPatternParser` — Punkte in `{entityId}` (z. B. `sensor.zigbee_x`) werden vollständig gematcht, kein Suffix-Stripping.

- [ ] **Step 5: Test ausführen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=EntityStateControllerTest`
Expected: Tests run: 6, Failures: 0

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/EntityStateResponse.java backend/src/main/java/com/household/manager/controller/EntityStateController.java backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java
git commit -m "feat(entitystate): add REST API for querying and deleting entities"
```

---

### Task 6: SmartDevice-Mapper + Hooks (Kasa/Tapo/Meross)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/SmartDeviceEntityMapper.java`
- Modify: `backend/src/main/java/com/household/manager/service/SmartDeviceService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/SmartDeviceEntityMapperTest.java`

- [ ] **Step 1: Failing Mapper-Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/mapper/SmartDeviceEntityMapperTest.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmartDeviceEntityMapperTest {

    private final SmartDeviceEntityMapper mapper = new SmartDeviceEntityMapper();

    private SmartDevice device(DeviceType type, boolean online, boolean poweredOn) {
        SmartDevice device = new SmartDevice();
        device.setDeviceType(type);
        device.setExternalDeviceId("8006A1B2");
        device.setDeviceName("Wohnzimmer Steckdose");
        device.setModel("HS100");
        device.setIpAddress("192.168.1.50");
        device.setOnline(online);
        device.setPoweredOn(poweredOn);
        return device;
    }

    @Test
    void mapsOnlinePoweredDeviceToSwitchOn() {
        EntityStateUpdate update = mapper.map(device(DeviceType.KASA, true, true));

        assertEquals("switch.kasa_8006a1b2", update.entityId());
        assertEquals(EntityDomain.SWITCH, update.domain());
        assertEquals(EntitySource.KASA, update.source());
        assertEquals("8006A1B2", update.sourceRef());
        assertEquals("Wohnzimmer Steckdose", update.friendlyName());
        assertEquals("on", update.state());
        assertEquals("HS100", update.attributes().get("model"));
        assertEquals("192.168.1.50", update.attributes().get("ipAddress"));
    }

    @Test
    void mapsOnlineUnpoweredDeviceToSwitchOff() {
        EntityStateUpdate update = mapper.map(device(DeviceType.TAPO, true, false));

        assertEquals("switch.tapo_8006a1b2", update.entityId());
        assertEquals("off", update.state());
    }

    @Test
    void mapsOfflineDeviceToUnavailable() {
        EntityStateUpdate update = mapper.map(device(DeviceType.MEROSS, false, true));

        assertEquals(EntitySource.MEROSS, update.source());
        assertEquals("unavailable", update.state());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SmartDeviceEntityMapperTest`
Expected: Kompilierfehler „cannot find symbol: SmartDeviceEntityMapper“

- [ ] **Step 3: Mapper implementieren**

`backend/src/main/java/com/household/manager/entitystate/mapper/SmartDeviceEntityMapper.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.*;
import com.household.manager.model.entity.SmartDevice;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Übersetzt ein {@link SmartDevice} (Kasa/Tapo/Meross) in eine Switch-Entität.
 */
@Component
public class SmartDeviceEntityMapper {

    public EntityStateUpdate map(SmartDevice device) {
        // DeviceType-Namen (KASA/TAPO/MEROSS) stimmen mit EntitySource überein
        EntitySource source = EntitySource.valueOf(device.getDeviceType().name());

        String state;
        if (!device.isOnline()) {
            state = "unavailable";
        } else {
            state = device.isPoweredOn() ? "on" : "off";
        }

        Map<String, Object> attributes = new HashMap<>();
        if (device.getModel() != null) {
            attributes.put("model", device.getModel());
        }
        if (device.getIpAddress() != null) {
            attributes.put("ipAddress", device.getIpAddress());
        }

        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SWITCH, source, device.getExternalDeviceId(), null))
                .domain(EntityDomain.SWITCH)
                .source(source)
                .sourceRef(device.getExternalDeviceId())
                .friendlyName(device.getDeviceName())
                .state(state)
                .attributes(attributes)
                .build();
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SmartDeviceEntityMapperTest`
Expected: Tests run: 3, Failures: 0

- [ ] **Step 5: Hooks in SmartDeviceService einbauen**

In `SmartDeviceService` zwei neue Abhängigkeiten ergänzen (Klasse nutzt `@RequiredArgsConstructor`, also nur Felder hinzufügen):

```java
    private final SmartDeviceEntityMapper smartDeviceEntityMapper;
    private final EntityStateService entityStateService;
```

Imports:

```java
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.SmartDeviceEntityMapper;
```

Private Hilfsmethode am Ende der Klasse (Abschnitt „Helper Methods“):

```java
    private void reportEntityState(SmartDevice device) {
        entityStateService.reportState(smartDeviceEntityMapper.map(device));
    }
```

Hook-Aufrufe an vier Stellen, jeweils NACH dem `smartDeviceRepository.save(...)`:

1. In `refreshDeviceState` (nach `SmartDevice updated = smartDeviceRepository.save(device);`):
```java
            reportEntityState(updated);
```
2. In `turnOn` (nach `smartDeviceRepository.save(device);` im try-Block):
```java
            reportEntityState(device);
```
3. In `turnOff` (analog):
```java
            reportEntityState(device);
```
4. In `scanAndPersistDevices` (nach dem `switch`-Block, vor dem `log.info`):
```java
        persistedDevices.forEach(this::reportEntityState);
```

- [ ] **Step 6: Kompilieren + alle bisherigen Tests**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="EntityState*Test,EntityIdsTest,SmartDeviceEntityMapperTest"`
Expected: alle grün

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/mapper/SmartDeviceEntityMapper.java backend/src/test/java/com/household/manager/entitystate/mapper/SmartDeviceEntityMapperTest.java backend/src/main/java/com/household/manager/service/SmartDeviceService.java
git commit -m "feat(entitystate): mirror smart devices (Kasa/Tapo/Meross) as switch entities"
```

---

### Task 7: Zigbee-Mapper + Hook (MQTT-push)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapper.java`
- Modify: `backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapperTest.java`

- [ ] **Step 1: Failing Mapper-Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapperTest.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZigbeeEntityMapperTest {

    private final ZigbeeEntityMapper mapper = new ZigbeeEntityMapper();

    @Test
    void mapsNumericMeasurementToSensorEntity() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Wohnzimmer Sensor", 87, 120,
                List.of(new ZigbeeMeasurementValue(MeasurementType.TEMPERATURE, new BigDecimal("21.5"), "°C")));

        List<EntityStateUpdate> updates = mapper.map(message);

        assertEquals(1, updates.size());
        EntityStateUpdate update = updates.get(0);
        assertEquals("sensor.zigbee_wohnzimmer_sensor_temperature", update.entityId());
        assertEquals(EntityDomain.SENSOR, update.domain());
        assertEquals("21.5", update.state());
        assertEquals("°C", update.attributes().get("unit"));
        assertEquals(87, update.attributes().get("batteryPercent"));
        assertEquals(120, update.attributes().get("linkQuality"));
        assertEquals("Wohnzimmer Sensor Temperatur", update.friendlyName());
    }

    @Test
    void mapsBinaryMeasurementToBinarySensorEntity() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Haustür", null, null,
                List.of(new ZigbeeMeasurementValue(MeasurementType.CONTACT, BigDecimal.ONE, "")));

        List<EntityStateUpdate> updates = mapper.map(message);

        EntityStateUpdate update = updates.get(0);
        assertEquals("binary_sensor.zigbee_haustuer_contact", update.entityId());
        assertEquals(EntityDomain.BINARY_SENSOR, update.domain());
        assertEquals("on", update.state());
    }

    @Test
    void mapsZeroBinaryValueToOff() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Haustür", null, null,
                List.of(new ZigbeeMeasurementValue(MeasurementType.OCCUPANCY, BigDecimal.ZERO, "")));

        assertEquals("off", mapper.map(message).get(0).state());
    }

    @Test
    void mapsMultipleMeasurementsToMultipleEntities() {
        ParsedZigbeeMessage message = new ParsedZigbeeMessage(
                "Bad", 50, 100,
                List.of(
                        new ZigbeeMeasurementValue(MeasurementType.TEMPERATURE, new BigDecimal("22.0"), "°C"),
                        new ZigbeeMeasurementValue(MeasurementType.HUMIDITY, new BigDecimal("55"), "%")));

        assertEquals(2, mapper.map(message).size());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ZigbeeEntityMapperTest`
Expected: Kompilierfehler „cannot find symbol: ZigbeeEntityMapper“

- [ ] **Step 3: Mapper implementieren**

`backend/src/main/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapper.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.*;
import com.household.manager.zigbee.model.MeasurementType;
import com.household.manager.zigbee.parser.ParsedZigbeeMessage;
import com.household.manager.zigbee.parser.ZigbeeMeasurementValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Übersetzt eine geparste zigbee2mqtt-Nachricht in Sensor-/Binärsensor-Entitäten
 * (eine Entität pro Messgröße).
 */
@Component
public class ZigbeeEntityMapper {

    private static final Set<MeasurementType> BINARY_TYPES =
            EnumSet.of(MeasurementType.CONTACT, MeasurementType.OCCUPANCY, MeasurementType.WATER_LEAK);

    private static final Map<MeasurementType, String> GERMAN_LABELS = Map.of(
            MeasurementType.TEMPERATURE, "Temperatur",
            MeasurementType.HUMIDITY, "Luftfeuchtigkeit",
            MeasurementType.PRESSURE, "Luftdruck",
            MeasurementType.CONTACT, "Kontakt",
            MeasurementType.OCCUPANCY, "Bewegung",
            MeasurementType.ILLUMINANCE, "Helligkeit",
            MeasurementType.WATER_LEAK, "Wasserleck"
    );

    public List<EntityStateUpdate> map(ParsedZigbeeMessage message) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        for (ZigbeeMeasurementValue value : message.measurements()) {
            boolean binary = BINARY_TYPES.contains(value.type());
            EntityDomain domain = binary ? EntityDomain.BINARY_SENSOR : EntityDomain.SENSOR;

            Map<String, Object> attributes = new HashMap<>();
            if (value.unit() != null && !value.unit().isBlank()) {
                attributes.put("unit", value.unit());
            }
            attributes.put("deviceClass", value.type().name().toLowerCase());
            if (message.batteryPercent() != null) {
                attributes.put("batteryPercent", message.batteryPercent());
            }
            if (message.linkQuality() != null) {
                attributes.put("linkQuality", message.linkQuality());
            }

            String state = binary ? toOnOff(value.value()) : value.value().toPlainString();
            String suffix = value.type().name().toLowerCase();

            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(domain, EntitySource.ZIGBEE, message.friendlyName(), suffix))
                    .domain(domain)
                    .source(EntitySource.ZIGBEE)
                    .sourceRef(message.friendlyName())
                    .friendlyName(message.friendlyName() + " " + GERMAN_LABELS.get(value.type()))
                    .state(state)
                    .attributes(attributes)
                    .build());
        }
        return updates;
    }

    private String toOnOff(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) != 0 ? "on" : "off";
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ZigbeeEntityMapperTest`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Hook in ZigbeeMqttConfig einbauen**

In `ZigbeeMqttConfig` (nutzt `@RequiredArgsConstructor`) zwei Felder ergänzen:

```java
    private final ZigbeeEntityMapper zigbeeEntityMapper;
    private final EntityStateService entityStateService;
```

Imports:

```java
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.ZigbeeEntityMapper;
```

In der Methode `handle(...)` den `parsed.ifPresent(...)`-Block erweitern:

```java
            parsed.ifPresent(msg -> {
                var events = readingService.record(msg);
                events.forEach(liveService::broadcast);
                zigbeeEntityMapper.map(msg).forEach(entityStateService::reportState);
            });
```

- [ ] **Step 6: Kompilieren + Commit**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ZigbeeEntityMapperTest`
Expected: grün

```bash
git add backend/src/main/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapper.java backend/src/test/java/com/household/manager/entitystate/mapper/ZigbeeEntityMapperTest.java backend/src/main/java/com/household/manager/zigbee/config/ZigbeeMqttConfig.java
git commit -m "feat(entitystate): mirror zigbee measurements as sensor entities"
```

---

### Task 8: Tasmota-Hook

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/TasmotaElectricityPollingService.java`

Tasmota liefert zwei Werte: Zählerstand (`posWirkenergieTariflos`, kWh) und Momentanleistung (`momentaneWirkleistung`, W). Die Werte werden direkt nach erfolgreichem Parsen gemeldet (unabhängig von der Duplikatsprüfung der Fachtabelle — der Spiegel zeigt immer den zuletzt gesehenen Wert). Kein eigener Mapper-Test nötig; die Logik ist eine private Methode ohne Verzweigungen, abgesichert durch die EntityStateService-Tests.

- [ ] **Step 1: Hook einbauen**

Felder ergänzen (Klasse nutzt `@RequiredArgsConstructor`):

```java
    private final EntityStateService entityStateService;
```

Imports:

```java
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import java.math.BigDecimal;
import java.util.Map;
```

Private Methode am Ende der Klasse:

```java
    private void reportEntityStates(BigDecimal energyKwh, BigDecimal powerW) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TASMOTA, "main", "energy"))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.TASMOTA)
                .sourceRef("main")
                .friendlyName("Stromzähler Wirkenergie")
                .state(energyKwh.toPlainString())
                .attributes(Map.of("unit", "kWh", "deviceClass", "energy"))
                .build());
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TASMOTA, "main", "power"))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.TASMOTA)
                .sourceRef("main")
                .friendlyName("Stromzähler Momentanleistung")
                .state(powerW.toPlainString())
                .attributes(Map.of("unit", "W", "deviceClass", "power"))
                .build());
    }
```

Zwei Aufrufstellen:

1. In `safePoll()`, direkt nach der Validierung der Pflichtfelder (nach dem `if (payload.getPosWirkenergieTariflos() == null || ...) { ...; return; }`-Block, VOR `if (repository.existsByReadingTime(...))`):
```java
            reportEntityStates(payload.getPosWirkenergieTariflos(), payload.getMomentaneWirkleistung());
```
2. In `tryParseWithJsonTree(...)`, direkt nach dem `if (posNode == null || powerNode == null) { return false; }`-Block:
```java
            reportEntityStates(posNode.decimalValue(), powerNode.decimalValue());
```

- [ ] **Step 2: Kompilieren + Commit**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/service/TasmotaElectricityPollingService.java
git commit -m "feat(entitystate): mirror tasmota energy readings as sensor entities"
```

---

### Task 9: Shelly-Hook

**Files:**
- Modify: `backend/src/main/java/com/household/manager/shelly/ShellyPollingService.java`

Pro Shelly-Gerät zwei Sensor-Entitäten (Leistung, Gesamtenergie). Nicht erreichbare Geräte melden `unavailable` — anders als die Fachtabelle, die unerreichbare Geräte überspringt, soll der Spiegel den Ausfall sichtbar machen.

- [ ] **Step 1: Hook einbauen**

Feld ergänzen (Klasse nutzt `@RequiredArgsConstructor`):

```java
    private final EntityStateService entityStateService;
```

Imports:

```java
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import java.util.Map;
```

Private Methoden am Ende der Klasse:

```java
    private void reportEntityStates(ShellyStatusDto status) {
        String powerState = status.reachable() && status.power() != null
                ? String.valueOf(status.power()) : "unavailable";
        String energyState = status.reachable() && status.totalEnergy() != null
                ? String.valueOf(status.totalEnergy()) : "unavailable";

        reportSensor(status.deviceName(), "power", "Leistung", powerState, Map.of("unit", "W", "deviceClass", "power"));
        reportSensor(status.deviceName(), "energy", "Gesamtenergie", energyState, Map.of("unit", "kWh", "deviceClass", "energy"));
    }

    private void reportSensor(String deviceName, String suffix, String label, String state, Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.SHELLY, deviceName, suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.SHELLY)
                .sourceRef(deviceName)
                .friendlyName(deviceName + " " + label)
                .state(state)
                .attributes(attributes)
                .build());
    }
```

In `pollAllDevices()` die Schleife so erweitern, dass der Report VOR dem `continue` bei unerreichbaren Geräten passiert:

```java
        for (ShellyStatusDto status : statuses) {
            reportEntityStates(status);
            if (!status.reachable()) {
                log.warn("Shelly '{}' is unreachable, skipping persist", status.deviceName());
                continue;
            }
            // ... bestehender Persist-Code unverändert ...
        }
```

- [ ] **Step 2: Kompilieren + Commit**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/shelly/ShellyPollingService.java
git commit -m "feat(entitystate): mirror shelly power readings as sensor entities"
```

---

### Task 10: Airrohr-Hook

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/AirrohrPollingService.java`

Zwei Sensor-Entitäten: PM10 (`sdsP1`) und PM2.5 (`sdsP2`). `sourceRef` ist fix `"airrohr"` (es gibt genau einen konfigurierten Sensor). Null-Werte werden nicht gemeldet.

- [ ] **Step 1: Hook einbauen**

Feld ergänzen:

```java
    private final EntityStateService entityStateService;
```

Imports:

```java
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import java.util.Map;
```

Private Methode am Ende der Klasse:

```java
    private void reportEntityStates(AirrohrReadingResponse response) {
        reportSensor("pm10", "Feinstaub PM10", response.getSdsP1());
        reportSensor("pm25", "Feinstaub PM2.5", response.getSdsP2());
    }

    private void reportSensor(String suffix, String label, Object value) {
        if (value == null) {
            return;
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.AIRROHR, "airrohr", suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.AIRROHR)
                .sourceRef("airrohr")
                .friendlyName("Airrohr " + label)
                .state(String.valueOf(value))
                .attributes(Map.of("unit", "µg/m³", "deviceClass", "pm"))
                .build());
    }
```

In `safePoll()` nach `airrohrReadingRepository.save(entity);` einfügen:

```java
            reportEntityStates(response);
```

- [ ] **Step 2: Kompilieren + Commit**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/service/AirrohrPollingService.java
git commit -m "feat(entitystate): mirror airrohr particulate readings as sensor entities"
```

---

### Task 11: Wetter-Hook (DWD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/WeatherPollingService.java`

Fünf Sensor-Entitäten: Temperatur, Luftfeuchtigkeit, Niederschlag, Windgeschwindigkeit, Luftdruck. `sourceRef` ist die konfigurierte `stationId`. Null-Werte werden nicht gemeldet.

- [ ] **Step 1: Hook einbauen**

Feld ergänzen:

```java
    private final EntityStateService entityStateService;
```

Imports:

```java
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import java.util.Map;
```

Private Methode am Ende der Klasse:

```java
    private void reportEntityStates(WeatherConditions current) {
        reportSensor("temperature", "Temperatur", current.getTemperature(), "°C", "temperature");
        reportSensor("humidity", "Luftfeuchtigkeit", current.getHumidity(), "%", "humidity");
        reportSensor("precipitation", "Niederschlag", current.getPrecipitation(), "mm", "precipitation");
        reportSensor("wind_speed", "Windgeschwindigkeit", current.getWindSpeed(), "km/h", "wind_speed");
        reportSensor("pressure", "Luftdruck", current.getPressure(), "hPa", "pressure");
    }

    private void reportSensor(String suffix, String label, Object value, String unit, String deviceClass) {
        if (value == null) {
            return;
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.WEATHER, "dwd", suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.WEATHER)
                .sourceRef(stationId)
                .friendlyName("Wetter " + label)
                .state(String.valueOf(value))
                .attributes(Map.of("unit", unit, "deviceClass", deviceClass, "stationId", stationId))
                .build());
    }
```

In `safePoll()` nach `repository.save(entity);` einfügen:

```java
            reportEntityStates(current);
```

**Hinweis:** Die Getter existieren so in `WeatherConditions` (`@Data`; `temperature`/`precipitation`/`windSpeed`/`pressure` sind `BigDecimal`, `humidity` ist `Integer`) — `String.valueOf(...)` deckt beide Typen ab.

- [ ] **Step 2: Kompilieren + Commit**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test-compile`
Expected: BUILD SUCCESS

```bash
git add backend/src/main/java/com/household/manager/service/WeatherPollingService.java
git commit -m "feat(entitystate): mirror DWD weather conditions as sensor entities"
```

---

### Task 12: AnkerSolix-Hook

**Files:**
- Modify: `backend/src/main/java/com/household/manager/ankersolix/AnkerSolixService.java`

Hook direkt in `getLiveData()` (zentrale Stelle — feuert bei jedem Live-Abruf, egal ob SSE-Stream oder Auto-Control). Fünf Sensor-Entitäten aus `AnkerSolixLiveDto`.

**Achtung:** `AnkerSolixService` hat aktuell KEINEN Konstruktor und keine `final`-Felder (alles `@Value`/`@PostConstruct`). Für die neue Abhängigkeit die Klassenannotation `@RequiredArgsConstructor` (Import `lombok.RequiredArgsConstructor`) ergänzen und das Feld als einziges `final`-Feld anlegen — Lombok erzeugt daraus den Ein-Argument-Konstruktor, Spring injiziert.

- [ ] **Step 1: Hook einbauen**

Feld ergänzen (plus `@RequiredArgsConstructor` an der Klasse):

```java
    private final EntityStateService entityStateService;
```

Imports:

```java
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import java.util.Map;
```

Private Methode am Ende der Klasse:

```java
    private void reportEntityStates(AnkerSolixLiveDto live) {
        reportSensor("pv_power", "Solarleistung", String.valueOf(live.getPvPowerW()), "W", "power");
        reportSensor("battery_percent", "Akkustand", String.valueOf(live.getBatteryPercent()), "%", "battery");
        reportSensor("battery_power", "Akkuleistung", String.valueOf(live.getBatteryPowerW()), "W", "power");
        reportSensor("grid_power", "Netzleistung", String.valueOf(live.getGridPowerW()), "W", "power");
        reportSensor("home_power", "Hausverbrauch", String.valueOf(live.getHomePowerW()), "W", "power");
    }

    private void reportSensor(String suffix, String label, String state, String unit, String deviceClass) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.ANKER_SOLIX, "solarbank", suffix))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ANKER_SOLIX)
                .sourceRef("solarbank")
                .friendlyName("Anker Solix " + label)
                .state(state)
                .attributes(Map.of("unit", unit, "deviceClass", deviceClass))
                .build());
    }
```

In `getLiveData()` das direkte `return AnkerSolixLiveDto.builder()...build();` umbauen zu:

```java
            AnkerSolixLiveDto live = AnkerSolixLiveDto.builder()
                    .pvPowerW(pvPowerW)
                    .batteryPercent(batteryPercent)
                    .batteryPowerW(batteryPowerW)
                    .gridPowerW(gridPowerW)
                    .homePowerW(homePowerW)
                    .timestamp(LocalDateTime.now())
                    .build();
            reportEntityStates(live);
            return live;
```

- [ ] **Step 2: Kompilieren + alle Backend-Unit-Tests**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest="EntityState*Test,EntityIdsTest,*EntityMapperTest"`
Expected: alle grün

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/ankersolix/AnkerSolixService.java
git commit -m "feat(entitystate): mirror anker solix live power flow as sensor entities"
```

---

### Task 13: Frontend — Model + Service

**Files:**
- Create: `frontend/src/app/models/entity-state.model.ts`
- Create: `frontend/src/app/services/entity-state.service.ts`
- Test: `frontend/src/app/services/entity-state.service.spec.ts`

- [ ] **Step 1: Model anlegen**

`frontend/src/app/models/entity-state.model.ts`:

```typescript
/**
 * Eine generische Entität mit aktuellem Zustand (Spiegel der Integrationen).
 */
export interface EntityState {
  entityId: string;
  domain: EntityDomain;
  source: string;
  sourceRef: string;
  friendlyName: string;
  state: string;
  attributes: Record<string, unknown>;
  lastChanged: string;
  lastUpdated: string;
}

export type EntityDomain = 'SWITCH' | 'SENSOR' | 'BINARY_SENSOR';
```

- [ ] **Step 2: Failing Service-Test schreiben**

`frontend/src/app/services/entity-state.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EntityStateService } from './entity-state.service';
import { EntityState } from '../models/entity-state.model';

describe('EntityStateService', () => {
  let service: EntityStateService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(EntityStateService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads all entities', () => {
    const entities: EntityState[] = [];
    service.getEntities().subscribe(result => expect(result).toEqual(entities));

    const req = httpMock.expectOne('/api/v1/entities');
    expect(req.request.method).toBe('GET');
    req.flush(entities);
  });

  it('passes domain and source as query params', () => {
    service.getEntities('SENSOR', 'ZIGBEE').subscribe();

    const req = httpMock.expectOne(r => r.url === '/api/v1/entities');
    expect(req.request.params.get('domain')).toBe('SENSOR');
    expect(req.request.params.get('source')).toBe('ZIGBEE');
    req.flush([]);
  });

  it('deletes an entity by id', () => {
    service.deleteEntity('sensor.zigbee_bad_temperature').subscribe();

    const req = httpMock.expectOne('/api/v1/entities/sensor.zigbee_bad_temperature');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
```

- [ ] **Step 3: Test ausführen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --include='**/entity-state.service.spec.ts'`
Expected: FAIL („Cannot find module './entity-state.service'“ o. ä.)

- [ ] **Step 4: Service implementieren**

`frontend/src/app/services/entity-state.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { EntityState, EntityDomain } from '../models/entity-state.model';

/**
 * REST-Service für die generische Entity-/State-Schicht.
 */
@Injectable({ providedIn: 'root' })
export class EntityStateService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/entities';

  getEntities(domain?: EntityDomain, source?: string): Observable<EntityState[]> {
    let params = new HttpParams();
    if (domain) { params = params.set('domain', domain); }
    if (source) { params = params.set('source', source); }
    return this.http.get<EntityState[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  deleteEntity(entityId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${entityId}`).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Entity-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Entitäten.'));
  }
}
```

- [ ] **Step 5: Test ausführen — muss grün sein**

Run: `cd frontend && npm test -- --watch=false --include='**/entity-state.service.spec.ts'`
Expected: 3 SUCCESS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/entity-state.model.ts frontend/src/app/services/entity-state.service.ts frontend/src/app/services/entity-state.service.spec.ts
git commit -m "feat(entitystate): add frontend model and REST service for entities"
```

---

### Task 14: Frontend — Entitäten-Übersichtsseite + Route + Navigation

**Files:**
- Create: `frontend/src/app/pages/entities/entities.component.ts`
- Create: `frontend/src/app/pages/entities/entities.component.html`
- Create: `frontend/src/app/pages/entities/entities.component.scss`
- Modify: `frontend/src/app/app.routes.ts` (Route nach `devices`)
- Modify: `frontend/src/app/components/header/header.component.ts` (Nav-Link nach `{ path: '/devices', label: 'Geraete' }`)

- [ ] **Step 1: Komponente (TS) anlegen**

`frontend/src/app/pages/entities/entities.component.ts`:

```typescript
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap } from 'rxjs';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState, EntityDomain } from '../../models/entity-state.model';

const REFRESH_INTERVAL_MS = 10000;

/**
 * Übersicht aller generischen Entitäten mit Live-Zustand (Polling).
 * Dient als Sichtbarmachung und Debug-Werkzeug der Entity-/State-Schicht.
 */
@Component({
  selector: 'app-entities',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './entities.component.html',
  styleUrl: './entities.component.scss'
})
export class EntitiesComponent implements OnInit {
  private readonly entityStateService = inject(EntityStateService);
  private readonly destroyRef = inject(DestroyRef);

  readonly entities = signal<EntityState[]>([]);
  readonly error = signal<string | null>(null);
  readonly expandedEntityId = signal<string | null>(null);

  domainFilter = signal<EntityDomain | ''>('');
  sourceFilter = signal<string>('');
  searchText = signal<string>('');

  readonly sources = computed(() =>
    [...new Set(this.entities().map(e => e.source))].sort()
  );

  readonly filteredEntities = computed(() => {
    const search = this.searchText().toLowerCase();
    return this.entities().filter(e =>
      (!this.domainFilter() || e.domain === this.domainFilter()) &&
      (!this.sourceFilter() || e.source === this.sourceFilter()) &&
      (!search ||
        e.friendlyName.toLowerCase().includes(search) ||
        e.entityId.toLowerCase().includes(search))
    );
  });

  ngOnInit(): void {
    interval(REFRESH_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.entityStateService.getEntities()),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: entities => {
        this.entities.set(entities);
        this.error.set(null);
      },
      error: err => this.error.set(err.message)
    });
  }

  toggleExpanded(entityId: string): void {
    this.expandedEntityId.set(this.expandedEntityId() === entityId ? null : entityId);
  }

  attributeEntries(entity: EntityState): { key: string; value: unknown }[] {
    return Object.entries(entity.attributes ?? {}).map(([key, value]) => ({ key, value }));
  }

  stateWithUnit(entity: EntityState): string {
    const unit = entity.attributes?.['unit'];
    if (entity.state === 'unavailable' || entity.state === 'unknown' || !unit) {
      return entity.state;
    }
    return `${entity.state} ${unit}`;
  }

  stateClass(entity: EntityState): string {
    if (entity.state === 'unavailable' || entity.state === 'unknown') {
      return 'state-badge--unavailable';
    }
    if (entity.state === 'on') {
      return 'state-badge--on';
    }
    if (entity.state === 'off') {
      return 'state-badge--off';
    }
    return 'state-badge--value';
  }

  relativeTime(isoDate: string): string {
    const diffMs = Date.now() - new Date(isoDate).getTime();
    const minutes = Math.floor(diffMs / 60000);
    if (minutes < 1) { return 'gerade eben'; }
    if (minutes < 60) { return `vor ${minutes} Min.`; }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) { return `vor ${hours} Std.`; }
    return `vor ${Math.floor(hours / 24)} Tagen`;
  }
}
```

- [ ] **Step 2: Template anlegen**

`frontend/src/app/pages/entities/entities.component.html`:

```html
<div class="entities-page">
  <h1>Entitäten</h1>
  <p class="entities-page__subtitle">
    Alle Geräte und Messwerte als generische Entitäten mit aktuellem Zustand.
  </p>

  @if (error()) {
    <div class="entities-page__error">{{ error() }}</div>
  }

  <div class="entities-page__filters">
    <select [ngModel]="domainFilter()" (ngModelChange)="domainFilter.set($event)">
      <option value="">Alle Domains</option>
      <option value="SWITCH">Switch</option>
      <option value="SENSOR">Sensor</option>
      <option value="BINARY_SENSOR">Binary Sensor</option>
    </select>

    <select [ngModel]="sourceFilter()" (ngModelChange)="sourceFilter.set($event)">
      <option value="">Alle Quellen</option>
      @for (source of sources(); track source) {
        <option [value]="source">{{ source }}</option>
      }
    </select>

    <input
      type="text"
      placeholder="Suche nach Name oder Entity-ID..."
      [ngModel]="searchText()"
      (ngModelChange)="searchText.set($event)" />
  </div>

  <table class="entities-table">
    <thead>
      <tr>
        <th>Name</th>
        <th>Entity-ID</th>
        <th>Domain</th>
        <th>Quelle</th>
        <th>Zustand</th>
        <th>Geändert</th>
      </tr>
    </thead>
    <tbody>
      @for (entity of filteredEntities(); track entity.entityId) {
        <tr class="entities-table__row" (click)="toggleExpanded(entity.entityId)">
          <td>{{ entity.friendlyName }}</td>
          <td class="entities-table__id">{{ entity.entityId }}</td>
          <td>{{ entity.domain }}</td>
          <td>{{ entity.source }}</td>
          <td>
            <span class="state-badge" [class]="'state-badge ' + stateClass(entity)">
              {{ stateWithUnit(entity) }}
            </span>
          </td>
          <td>{{ relativeTime(entity.lastChanged) }}</td>
        </tr>
        @if (expandedEntityId() === entity.entityId) {
          <tr class="entities-table__details">
            <td colspan="6">
              <dl class="entities-table__attributes">
                @for (attr of attributeEntries(entity); track attr.key) {
                  <dt>{{ attr.key }}</dt>
                  <dd>{{ attr.value }}</dd>
                }
                <dt>lastUpdated</dt>
                <dd>{{ entity.lastUpdated }}</dd>
                <dt>sourceRef</dt>
                <dd>{{ entity.sourceRef }}</dd>
              </dl>
            </td>
          </tr>
        }
      } @empty {
        <tr>
          <td colspan="6" class="entities-table__empty">Keine Entitäten gefunden.</td>
        </tr>
      }
    </tbody>
  </table>
</div>
```

- [ ] **Step 3: Styles anlegen**

`frontend/src/app/pages/entities/entities.component.scss`:

```scss
.entities-page {
  padding: 1.5rem;

  &__subtitle {
    color: #666;
    margin-bottom: 1rem;
  }

  &__error {
    background: #fdecea;
    color: #b71c1c;
    padding: 0.75rem 1rem;
    border-radius: 4px;
    margin-bottom: 1rem;
  }

  &__filters {
    display: flex;
    gap: 0.75rem;
    margin-bottom: 1rem;
    flex-wrap: wrap;

    select,
    input {
      padding: 0.5rem;
      border: 1px solid #ccc;
      border-radius: 4px;
      font-size: 0.9rem;
    }

    input {
      flex: 1;
      min-width: 220px;
    }
  }
}

.entities-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;

  th,
  td {
    text-align: left;
    padding: 0.6rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  th {
    background: #f5f5f5;
    font-weight: 600;
  }

  &__row {
    cursor: pointer;

    &:hover {
      background: #fafafa;
    }
  }

  &__id {
    font-family: monospace;
    font-size: 0.8rem;
    color: #555;
  }

  &__details td {
    background: #fafafa;
  }

  &__attributes {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 0.25rem 1rem;
    margin: 0;

    dt {
      font-weight: 600;
      color: #555;
    }

    dd {
      margin: 0;
      font-family: monospace;
      font-size: 0.85rem;
    }
  }

  &__empty {
    text-align: center;
    color: #888;
    padding: 2rem;
  }
}

.state-badge {
  display: inline-block;
  padding: 0.2rem 0.6rem;
  border-radius: 12px;
  font-size: 0.8rem;
  font-weight: 600;

  &--on {
    background: #e8f5e9;
    color: #2e7d32;
  }

  &--off {
    background: #eceff1;
    color: #546e7a;
  }

  &--unavailable {
    background: #f5f5f5;
    color: #9e9e9e;
    font-style: italic;
  }

  &--value {
    background: #e3f2fd;
    color: #1565c0;
  }
}
```

- [ ] **Step 4: Route registrieren**

In `frontend/src/app/app.routes.ts` nach dem `devices`-Eintrag einfügen:

```typescript
  {
    path: 'entities',
    loadComponent: () => import('./pages/entities/entities.component').then(m => m.EntitiesComponent),
    title: 'Entitaeten - Household Manager'
  },
```

- [ ] **Step 5: Nav-Link ergänzen**

In `frontend/src/app/components/header/header.component.ts` im `navLinks`-Array nach `{ path: '/devices', label: 'Geraete' }` einfügen:

```typescript
    { path: '/entities', label: 'Entitaeten' },
```

- [ ] **Step 6: Build prüfen**

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -5`
Expected: Build erfolgreich, keine Fehler

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/entities frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(entitystate): add entities overview page with filters and live polling"
```

---

### Task 15: Gesamtverifikation

- [ ] **Step 1: Alle Backend-Tests**

Run: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test`
Expected: Nur die zwei bekannten, vorbestehenden DB-Fehlschläge (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest`) — KEINE neuen Fehlschläge. Alle `EntityState*`-, `EntityIds`- und `*EntityMapper`-Tests grün.

- [ ] **Step 2: Alle Frontend-Tests**

Run: `cd frontend && npm test -- --watch=false`
Expected: keine neuen Fehlschläge gegenüber dem Stand vor diesem Plan

- [ ] **Step 3: Frontend-Produktionsbuild**

Run: `cd frontend && npx ng build --configuration production 2>&1 | tail -5`
Expected: Build erfolgreich

- [ ] **Step 4: Spec-Abgleich**

Prüfe gegen `docs/superpowers/specs/2026-07-08-entity-state-layer-design.md`:
- [ ] Alle 9 Quellen (KASA, TAPO, MEROSS über SmartDevice; ZIGBEE; SHELLY; TASMOTA; AIRROHR; WEATHER; ANKER_SOLIX) melden Entitäten
- [ ] Event nur bei Wertänderung; `last_changed` vs. `last_updated` korrekt
- [ ] `reportState` bricht nie den Aufrufer
- [ ] REST: GET (mit Filtern), GET einzeln, DELETE
- [ ] Frontend: Tabelle, Filter, Suche, Badges, Attribut-Aufklappen, 10-s-Polling

- [ ] **Step 5: Abschluss-Commit (falls noch offene Änderungen)**

```bash
git status --short
git add <offene Dateien> && git commit -m "chore(entitystate): finalize entity state layer"
```
