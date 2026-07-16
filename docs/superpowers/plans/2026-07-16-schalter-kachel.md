# Schalter-Kachel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die statische „Küche"-Platzhalterkachel im Dashboard wird zu einer Schalter-Kachel, die die meistgenutzten Schalter direkt schaltbar zeigt und per Button einen Dialog mit allen Schaltern öffnet.

**Architecture:** Backend bekommt eine einheitliche Schalter-API (`/api/v1/switches`), die SmartDevices (Kasa/Tapo/Meross) und manuelle Boolean-Helfer über ihre Entity-ID gemeinsam ansprechbar macht und jeden erfolgreichen Schaltvorgang in `entity_usage` zählt; die Liste wird nach Nutzungshäufigkeit sortiert. Frontend rendert die Zeilen über eine präsentationale `app-switch-list`, die Kachel und Dialog gemeinsam nutzen; Zustand und Dialog liegen im `DashboardComponent` (wie beim Energiefluss-Dialog).

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase / Lombok / JUnit 5 + Mockito + AssertJ + MockMvc; Angular 19 standalone / SCSS / Karma + Jasmine.

**Spec:** `docs/superpowers/specs/2026-07-16-schalter-kachel-dashboard-design.md`

---

## Build- und Testkommandos

**Backend** (JDK 21 nötig, Default ist JDK 17):

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=<TestKlasse>
```

Die Tests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen auf dieser Maschine mit „Access denied for user 'root'@'localhost'" fehl — die Test-DB ist lokal nicht erreichbar. Diese Fehler sind vorbestehend und umgebungsbedingt; sie sind **kein** Signal für diese Aufgabe. Alle hier neu geschriebenen Backend-Tests sind reine Mockito-/MockMvc-Unit-Tests ohne DB.

**Frontend:**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/<datei>.spec.ts'
```

## Dateiübersicht

**Backend — neu:**
- `src/main/resources/db/changelog/changes/20260716-0033-create-entity-usage-table.xml` — Tabelle `entity_usage`
- `src/main/java/com/household/manager/model/entity/EntityUsage.java` — Zähler-Entity
- `src/main/java/com/household/manager/repository/EntityUsageRepository.java` — Zugriff auf Zähler
- `src/main/java/com/household/manager/entitystate/EntityUsageService.java` — Zählen + Abfragen
- `src/main/java/com/household/manager/entitystate/SwitchableEntities.java` — die *eine* Regel, was schaltbar ist
- `src/main/java/com/household/manager/dto/SwitchResponse.java` — API-Repräsentation eines Schalters
- `src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java` — Entity + Usage → DTO
- `src/main/java/com/household/manager/entitystate/SwitchCommandService.java` — einheitliches Toggle
- `src/main/java/com/household/manager/entitystate/SwitchQueryService.java` — Liste, nutzungssortiert
- `src/main/java/com/household/manager/controller/SwitchController.java` — REST-Endpoints

**Backend — geändert:**
- `src/main/resources/db/changelog/db.changelog-master.xml` — Changeset einbinden
- `src/main/java/com/household/manager/repository/EntityStateRepository.java` — `findByDomainInOrderByEntityIdAsc`
- `src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java` — `displayName` wird public (Wiederverwendung)

**Frontend — neu:**
- `src/app/models/switch.model.ts`
- `src/app/services/switch.service.ts`
- `src/app/components/switch-list/switch-list.component.{ts,html,scss}`

**Frontend — geändert:**
- `src/app/pages/dashboard/dashboard.component.{ts,html,scss}` — Kachel, Dialog, Zustand

---

## Task 1: Tabelle `entity_usage` + Entity + Repository

Reine Deklaration (Schema, JPA-Entity, Repository-Interface). Es gibt hier nichts sinnvoll zu unit-testen, und DB-Tests laufen auf dieser Maschine nicht — die Logik darauf wird ab Task 2 getestet.

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260716-0033-create-entity-usage-table.xml`
- Create: `backend/src/main/java/com/household/manager/model/entity/EntityUsage.java`
- Create: `backend/src/main/java/com/household/manager/repository/EntityUsageRepository.java`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Changeset anlegen**

`backend/src/main/resources/db/changelog/changes/20260716-0033-create-entity-usage-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260716-0033-create-entity-usage-table" author="household-manager">
        <preConditions onFail="MARK_RAN">
            <not>
                <tableExists tableName="entity_usage"/>
            </not>
        </preConditions>
        <createTable tableName="entity_usage">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="entity_id" type="VARCHAR(150)">
                <constraints nullable="false" unique="true" uniqueConstraintName="uk_entity_usage_entity_id"/>
            </column>
            <column name="toggle_count" type="BIGINT" defaultValueNumeric="0">
                <constraints nullable="false"/>
            </column>
            <column name="last_toggled_at" type="TIMESTAMP"/>
        </createTable>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Changeset im Master einbinden**

In `backend/src/main/resources/db/changelog/db.changelog-master.xml` **vor** dem schließenden `</databaseChangeLog>` einfügen:

```xml
    <!-- Nutzungszaehler fuer die Schalter-Kachel -->
    <include file="db/changelog/changes/20260716-0033-create-entity-usage-table.xml"/>
```

- [ ] **Step 3: Entity anlegen**

`backend/src/main/java/com/household/manager/model/entity/EntityUsage.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Nutzungszähler einer schaltbaren Entität (eine Zeile je Entity-ID).
 * Grundlage für die nutzungsbasierte Sortierung der Schalter-Kachel.
 */
@Entity
@Table(name = "entity_usage")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Entity-ID der geschalteten Entität (Fremdschlüssel-Semantik, bewusst ohne FK-Constraint). */
    @Column(name = "entity_id", nullable = false, unique = true, length = 150)
    private String entityId;

    /** Anzahl erfolgreicher Schaltvorgänge. */
    @Column(name = "toggle_count", nullable = false)
    private long toggleCount;

    /** Zeitpunkt des letzten erfolgreichen Schaltvorgangs. */
    @Column(name = "last_toggled_at")
    private LocalDateTime lastToggledAt;
}
```

- [ ] **Step 4: Repository anlegen**

`backend/src/main/java/com/household/manager/repository/EntityUsageRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.EntityUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EntityUsageRepository extends JpaRepository<EntityUsage, Long> {

    Optional<EntityUsage> findByEntityId(String entityId);

    List<EntityUsage> findByEntityIdIn(Collection<String> entityIds);
}
```

- [ ] **Step 5: Kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn -q compile
```

Erwartung: BUILD SUCCESS (keine Ausgabe bei `-q` außer Fehlern).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/EntityUsage.java backend/src/main/java/com/household/manager/repository/EntityUsageRepository.java
git commit -m "feat(switches): Tabelle entity_usage fuer Nutzungszaehler"
```

---

## Task 2: EntityUsageService

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityUsageService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityUsageServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/EntityUsageServiceTest.java`:

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityUsageServiceTest {

    @Mock
    private EntityUsageRepository repository;

    private EntityUsageService service;

    @BeforeEach
    void setUp() {
        service = new EntityUsageService(repository);
    }

    private EntityUsage saved() {
        ArgumentCaptor<EntityUsage> captor = ArgumentCaptor.forClass(EntityUsage.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void legt_den_zaehler_beim_ersten_schalten_an() {
        when(repository.findByEntityId("switch.kasa_abc")).thenReturn(Optional.empty());
        when(repository.save(any(EntityUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordToggle("switch.kasa_abc");

        EntityUsage usage = saved();
        assertThat(usage.getEntityId()).isEqualTo("switch.kasa_abc");
        assertThat(usage.getToggleCount()).isEqualTo(1);
        assertThat(usage.getLastToggledAt()).isNotNull();
    }

    @Test
    void erhoeht_einen_bestehenden_zaehler() {
        EntityUsage existing = EntityUsage.builder()
                .entityId("switch.kasa_abc")
                .toggleCount(4)
                .lastToggledAt(LocalDateTime.of(2026, 7, 1, 8, 0))
                .build();
        when(repository.findByEntityId("switch.kasa_abc")).thenReturn(Optional.of(existing));
        when(repository.save(any(EntityUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordToggle("switch.kasa_abc");

        EntityUsage usage = saved();
        assertThat(usage.getToggleCount()).isEqualTo(5);
        assertThat(usage.getLastToggledAt()).isAfter(LocalDateTime.of(2026, 7, 1, 8, 0));
    }

    @Test
    void gibt_die_aktualisierte_nutzung_zurueck() {
        when(repository.findByEntityId("input_boolean.manual_nachtmodus")).thenReturn(Optional.empty());
        when(repository.save(any(EntityUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        EntityUsage result = service.recordToggle("input_boolean.manual_nachtmodus");

        assertThat(result.getToggleCount()).isEqualTo(1);
    }

    @Test
    void indiziert_die_nutzung_nach_entity_id() {
        EntityUsage usage = EntityUsage.builder().entityId("switch.kasa_abc").toggleCount(2).build();
        when(repository.findByEntityIdIn(List.of("switch.kasa_abc"))).thenReturn(List.of(usage));

        Map<String, EntityUsage> result = service.usageFor(List.of("switch.kasa_abc"));

        assertThat(result).containsOnlyKeys("switch.kasa_abc");
        assertThat(result.get("switch.kasa_abc").getToggleCount()).isEqualTo(2);
    }

    @Test
    void fragt_bei_leerer_id_liste_nicht_die_datenbank() {
        Map<String, EntityUsage> result = service.usageFor(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=EntityUsageServiceTest
```

Erwartung: Compile-Fehler „cannot find symbol: class EntityUsageService".

- [ ] **Step 3: Service implementieren**

`backend/src/main/java/com/household/manager/entitystate/EntityUsageService.java`:

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Zählt, wie oft und wann eine Entität zuletzt geschaltet wurde.
 * Grundlage für die nutzungsbasierte Sortierung der Schalter-Kachel.
 */
@Service
@RequiredArgsConstructor
public class EntityUsageService {

    private final EntityUsageRepository repository;

    /** Zählt einen erfolgreichen Schaltvorgang und legt den Zähler bei Bedarf an. */
    @Transactional
    public EntityUsage recordToggle(String entityId) {
        EntityUsage usage = repository.findByEntityId(entityId)
                .orElseGet(() -> EntityUsage.builder().entityId(entityId).toggleCount(0).build());
        usage.setToggleCount(usage.getToggleCount() + 1);
        usage.setLastToggledAt(LocalDateTime.now());
        return repository.save(usage);
    }

    /** Nutzungsdaten der angefragten Entitäten, nach Entity-ID indiziert. */
    @Transactional(readOnly = true)
    public Map<String, EntityUsage> usageFor(Collection<String> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByEntityIdIn(entityIds).stream()
                .collect(Collectors.toMap(EntityUsage::getEntityId, Function.identity()));
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=EntityUsageServiceTest
```

Erwartung: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntityUsageService.java backend/src/test/java/com/household/manager/entitystate/EntityUsageServiceTest.java
git commit -m "feat(switches): EntityUsageService zaehlt Schaltvorgaenge"
```

---

## Task 3: SwitchableEntities

Die *eine* Regel, welche Entitäten schaltbar sind. Liste und Toggle nutzen sie gemeinsam, damit nie ein Schalter angezeigt wird, den der Toggle anschließend ablehnt.

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/SwitchableEntities.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/SwitchableEntitiesTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/SwitchableEntitiesTest.java`:

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchableEntitiesTest {

    private EntityState entity(EntityDomain domain, EntitySource source) {
        return EntityState.builder()
                .entityId("x.y")
                .domain(domain)
                .source(source)
                .sourceRef("ref")
                .friendlyName("Name")
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    @Test
    void smart_device_schalter_sind_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.KASA))).isTrue();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.TAPO))).isTrue();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.MEROSS))).isTrue();
    }

    @Test
    void manuelle_boolean_helfer_sind_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL))).isTrue();
    }

    @Test
    void schalter_ohne_geraete_quelle_sind_nicht_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SWITCH, EntitySource.ZIGBEE))).isFalse();
    }

    @Test
    void sensoren_und_andere_helfer_sind_nicht_schaltbar() {
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.SENSOR, EntitySource.ZIGBEE))).isFalse();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.INPUT_NUMBER, EntitySource.MANUAL))).isFalse();
        assertThat(SwitchableEntities.isSwitchable(entity(EntityDomain.INPUT_TEXT, EntitySource.MANUAL))).isFalse();
    }

    @Test
    void die_vorfilter_domains_decken_beide_schaltbaren_faelle_ab() {
        assertThat(SwitchableEntities.SWITCHABLE_DOMAINS)
                .containsExactlyInAnyOrder(EntityDomain.SWITCH, EntityDomain.INPUT_BOOLEAN);
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchableEntitiesTest
```

Erwartung: Compile-Fehler „cannot find symbol: class SwitchableEntities".

- [ ] **Step 3: Implementieren**

`backend/src/main/java/com/household/manager/entitystate/SwitchableEntities.java`:

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityState;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Legt fest, welche Entitäten über die Schalter-API schaltbar sind.
 * <p>
 * Liste und Toggle nutzen dieselbe Regel, damit nie ein Schalter angeboten wird,
 * den der Toggle anschließend ablehnen würde.
 */
public final class SwitchableEntities {

    /** Quellen, deren SWITCH-Entitäten auf ein {@code SmartDevice} abbilden. */
    static final Set<EntitySource> DEVICE_SOURCES =
            EnumSet.of(EntitySource.KASA, EntitySource.TAPO, EntitySource.MEROSS);

    /** Domains, die überhaupt schaltbar sein können — Vorfilter für die Abfrage. */
    public static final List<EntityDomain> SWITCHABLE_DOMAINS =
            List.of(EntityDomain.SWITCH, EntityDomain.INPUT_BOOLEAN);

    private SwitchableEntities() {
    }

    public static boolean isSwitchable(EntityState entity) {
        return switch (entity.getDomain()) {
            case SWITCH -> DEVICE_SOURCES.contains(entity.getSource());
            case INPUT_BOOLEAN -> entity.getSource() == EntitySource.MANUAL;
            default -> false;
        };
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchableEntitiesTest
```

Erwartung: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/SwitchableEntities.java backend/src/test/java/com/household/manager/entitystate/SwitchableEntitiesTest.java
git commit -m "feat(switches): SwitchableEntities definiert schaltbare Entitaeten"
```

---

## Task 4: SwitchResponse + SwitchResponseMapper

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/SwitchResponse.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java:42` (`displayName` von private auf public)
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/SwitchResponseMapperTest.java`

- [ ] **Step 1: DTO anlegen**

`backend/src/main/java/com/household/manager/dto/SwitchResponse.java`:

```java
package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * API-Repräsentation eines schaltbaren Eintrags für Schalter-Kachel und -Dialog.
 */
@Builder
public record SwitchResponse(
        String entityId,
        String domain,
        String source,
        String displayName,
        String state,
        boolean available,
        String icon,
        long toggleCount,
        LocalDateTime lastToggledAt
) {
}
```

- [ ] **Step 2: Failing Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/mapper/SwitchResponseMapperTest.java`:

```java
package com.household.manager.entitystate.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SwitchResponseMapperTest {

    private SwitchResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SwitchResponseMapper(new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState.EntityStateBuilder entity() {
        return EntityState.builder()
                .entityId("switch.kasa_abc")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Stehlampe")
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now());
    }

    @Test
    void bildet_einen_schalter_mit_nutzung_ab() {
        EntityUsage usage = EntityUsage.builder()
                .entityId("switch.kasa_abc")
                .toggleCount(7)
                .lastToggledAt(LocalDateTime.of(2026, 7, 15, 20, 0))
                .build();

        SwitchResponse response = mapper.toResponse(entity().build(), usage);

        assertThat(response.entityId()).isEqualTo("switch.kasa_abc");
        assertThat(response.domain()).isEqualTo("SWITCH");
        assertThat(response.source()).isEqualTo("KASA");
        assertThat(response.displayName()).isEqualTo("Stehlampe");
        assertThat(response.state()).isEqualTo("on");
        assertThat(response.available()).isTrue();
        assertThat(response.toggleCount()).isEqualTo(7);
        assertThat(response.lastToggledAt()).isEqualTo(LocalDateTime.of(2026, 7, 15, 20, 0));
    }

    @Test
    void ohne_nutzung_ist_der_zaehler_null() {
        SwitchResponse response = mapper.toResponse(entity().build(), null);

        assertThat(response.toggleCount()).isZero();
        assertThat(response.lastToggledAt()).isNull();
    }

    @Test
    void der_kurzname_gewinnt_gegen_den_integrationsnamen() {
        SwitchResponse response = mapper.toResponse(entity().customName("Leselampe").build(), null);

        assertThat(response.displayName()).isEqualTo("Leselampe");
    }

    @Test
    void offline_geraete_sind_nicht_verfuegbar() {
        SwitchResponse response = mapper.toResponse(entity().state("unavailable").build(), null);

        assertThat(response.available()).isFalse();
    }

    @Test
    void nutzt_das_icon_aus_den_attributen() {
        EntityState manual = EntityState.builder()
                .entityId("input_boolean.manual_nachtmodus")
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state("off")
                .attributes("{\"icon\":\"bedtime\"}")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();

        SwitchResponse response = mapper.toResponse(manual, null);

        assertThat(response.icon()).isEqualTo("bedtime");
    }

    @Test
    void faellt_ohne_icon_attribut_auf_den_standard_zurueck() {
        SwitchResponse response = mapper.toResponse(entity().build(), null);

        assertThat(response.icon()).isEqualTo("toggle_on");
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchResponseMapperTest
```

Erwartung: Compile-Fehler „cannot find symbol: class SwitchResponseMapper".

- [ ] **Step 4: `displayName` im EntityStateResponseMapper wiederverwendbar machen**

In `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java` die Methode von `private` auf `public` ändern (Zeile 42), damit der neue Mapper sie nutzt statt sie zu duplizieren:

```java
    /** Effektiver Anzeigename: Kurzname, falls gesetzt, sonst der Integrationsname. */
    public String displayName(EntityState entity) {
        String custom = entity.getCustomName();
        return custom != null && !custom.isBlank() ? custom : entity.getFriendlyName();
    }
```

- [ ] **Step 5: Mapper implementieren**

`backend/src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bildet eine schaltbare {@link EntityState} zusammen mit ihrem
 * {@link EntityUsage}-Zähler auf die API-{@link SwitchResponse} ab.
 */
@Component
@RequiredArgsConstructor
public class SwitchResponseMapper {

    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String DEFAULT_ICON = "toggle_on";
    private static final String ATTR_ICON = "icon";

    private final EntityStateResponseMapper entityStateResponseMapper;

    /** @param usage darf null sein (Entität wurde noch nie geschaltet) */
    public SwitchResponse toResponse(EntityState entity, EntityUsage usage) {
        return SwitchResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .displayName(entityStateResponseMapper.displayName(entity))
                .state(entity.getState())
                .available(!STATE_UNAVAILABLE.equals(entity.getState()))
                .icon(icon(entity))
                .toggleCount(usage != null ? usage.getToggleCount() : 0L)
                .lastToggledAt(usage != null ? usage.getLastToggledAt() : null)
                .build();
    }

    private String icon(EntityState entity) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON;
    }
}
```

- [ ] **Step 6: Tests laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchResponseMapperTest
```

Erwartung: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 7: Bestehende Mapper-Tests gegenprüfen (Sichtbarkeitsänderung)**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest='EntityStateControllerTest,ManualEntityControllerTest,ManualEntityServiceTest'
```

Erwartung: alle grün.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/SwitchResponse.java backend/src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java backend/src/test/java/com/household/manager/entitystate/mapper/SwitchResponseMapperTest.java
git commit -m "feat(switches): SwitchResponse + Mapper mit Nutzungsdaten"
```

---

## Task 5: SwitchCommandService (einheitliches Toggle)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/SwitchCommandService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/SwitchCommandServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/SwitchCommandServiceTest.java`:

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.service.SmartDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwitchCommandServiceTest {

    @Mock
    private EntityStateService entityStateService;
    @Mock
    private ManualEntityService manualEntityService;
    @Mock
    private SmartDeviceService smartDeviceService;
    @Mock
    private SmartDeviceRepository smartDeviceRepository;
    @Mock
    private EntityUsageService entityUsageService;

    private SwitchCommandService service;

    @BeforeEach
    void setUp() {
        service = new SwitchCommandService(
                entityStateService, manualEntityService, smartDeviceService,
                smartDeviceRepository, entityUsageService,
                new SwitchResponseMapper(new EntityStateResponseMapper(new ObjectMapper())));
    }

    private EntityState switchEntity(String state) {
        return EntityState.builder()
                .entityId("switch.kasa_abc")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Stehlampe")
                .state(state)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private EntityState manualEntity(String state) {
        return EntityState.builder()
                .entityId("input_boolean.manual_nachtmodus")
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state(state)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private SmartDevice device() {
        return SmartDevice.builder()
                .id(42L)
                .deviceType(DeviceType.KASA)
                .externalDeviceId("abc")
                .build();
    }

    private void stubUsage() {
        when(entityUsageService.recordToggle(anyString()))
                .thenReturn(EntityUsage.builder().entityId("x").toggleCount(1).build());
    }

    @Test
    void schaltet_ein_eingeschaltetes_geraet_aus() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("on")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        stubUsage();

        service.toggle("switch.kasa_abc");

        verify(smartDeviceService).turnOff(42L);
        verify(smartDeviceService, never()).turnOn(anyLong());
    }

    @Test
    void schaltet_ein_ausgeschaltetes_geraet_ein() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        stubUsage();

        service.toggle("switch.kasa_abc");

        verify(smartDeviceService).turnOn(42L);
    }

    @Test
    void behandelt_ein_nicht_erreichbares_geraet_wie_ausgeschaltet() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("unavailable")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        stubUsage();

        service.toggle("switch.kasa_abc");

        verify(smartDeviceService).turnOn(42L);
    }

    @Test
    void delegiert_manuelle_helfer_an_den_manual_service() {
        when(entityStateService.getByEntityId("input_boolean.manual_nachtmodus"))
                .thenReturn(Optional.of(manualEntity("off")));
        stubUsage();

        service.toggle("input_boolean.manual_nachtmodus");

        verify(manualEntityService).toggle("input_boolean.manual_nachtmodus");
        verifyNoInteractions(smartDeviceService);
    }

    @Test
    void liefert_den_zustand_nach_dem_schalten() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")), Optional.of(switchEntity("on")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        when(entityUsageService.recordToggle("switch.kasa_abc"))
                .thenReturn(EntityUsage.builder().entityId("switch.kasa_abc").toggleCount(3).build());

        SwitchResponse response = service.toggle("switch.kasa_abc");

        assertThat(response.state()).isEqualTo("on");
        assertThat(response.toggleCount()).isEqualTo(3);
    }

    @Test
    void zaehlt_den_vorgang_nur_bei_erfolg() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.of(device()));
        doThrow(new RuntimeException("Geraet nicht erreichbar")).when(smartDeviceService).turnOn(42L);

        assertThatThrownBy(() -> service.toggle("switch.kasa_abc"))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(entityUsageService);
    }

    @Test
    void unbekannte_entitaet_wirft_not_found() {
        when(entityStateService.getByEntityId("switch.kasa_weg")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggle("switch.kasa_weg"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void entitaet_ohne_geraet_wirft_not_found() {
        when(entityStateService.getByEntityId("switch.kasa_abc"))
                .thenReturn(Optional.of(switchEntity("off")));
        when(smartDeviceRepository.findByDeviceTypeAndExternalDeviceId(DeviceType.KASA, "abc"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggle("switch.kasa_abc"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nicht_schaltbare_entitaet_wirft_illegal_argument() {
        EntityState sensor = EntityState.builder()
                .entityId("sensor.zigbee_bad_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("bad")
                .friendlyName("Bad Temperatur")
                .state("21.5")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(entityStateService.getByEntityId("sensor.zigbee_bad_temperature"))
                .thenReturn(Optional.of(sensor));

        assertThatThrownBy(() -> service.toggle("sensor.zigbee_bad_temperature"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchCommandServiceTest
```

Erwartung: Compile-Fehler „cannot find symbol: class SwitchCommandService".

- [ ] **Step 3: Service implementieren**

`backend/src/main/java/com/household/manager/entitystate/SwitchCommandService.java`:

```java
package com.household.manager.entitystate;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import com.household.manager.service.SmartDeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Schaltet Entitäten quellenübergreifend über ihre Entity-ID: manuelle
 * Boolean-Helfer über den {@link ManualEntityService}, SmartDevice-Steckdosen
 * über den {@link SmartDeviceService}. Erfolgreiche Vorgänge werden gezählt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwitchCommandService {

    private static final String STATE_ON = "on";

    private final EntityStateService entityStateService;
    private final ManualEntityService manualEntityService;
    private final SmartDeviceService smartDeviceService;
    private final SmartDeviceRepository smartDeviceRepository;
    private final EntityUsageService entityUsageService;
    private final SwitchResponseMapper switchResponseMapper;

    /**
     * Schaltet die Entität um und zählt den Vorgang.
     *
     * @throws ResourceNotFoundException wenn die Entity-ID unbekannt ist oder kein Gerät dazu existiert
     * @throws IllegalArgumentException  wenn die Entität nicht schaltbar ist
     */
    public SwitchResponse toggle(String entityId) {
        EntityState entity = entityStateService.getByEntityId(entityId)
                .orElseThrow(() -> new ResourceNotFoundException("Entity not found: " + entityId));
        if (!SwitchableEntities.isSwitchable(entity)) {
            throw new IllegalArgumentException("Entity is not switchable: " + entityId);
        }

        if (entity.getDomain() == EntityDomain.INPUT_BOOLEAN) {
            manualEntityService.toggle(entityId);
        } else {
            toggleDevice(entity);
        }

        EntityUsage usage = entityUsageService.recordToggle(entityId);
        return switchResponseMapper.toResponse(reload(entityId), usage);
    }

    /**
     * Schaltet ein SmartDevice anhand des zuletzt bekannten Zustands; alles außer
     * "on" (auch "unavailable") führt zum Einschalten.
     */
    private void toggleDevice(EntityState entity) {
        DeviceType deviceType = DeviceType.valueOf(entity.getSource().name());
        SmartDevice device = smartDeviceRepository
                .findByDeviceTypeAndExternalDeviceId(deviceType, entity.getSourceRef())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No device for entity: " + entity.getEntityId()));

        if (STATE_ON.equals(entity.getState())) {
            smartDeviceService.turnOff(device.getId());
        } else {
            smartDeviceService.turnOn(device.getId());
        }
    }

    /**
     * Lädt den Zustand nach dem Schaltbefehl neu. Beide Schaltwege melden ihren
     * neuen Zustand selbst an die Entity-Schicht, daher ist er hier bereits aktuell.
     */
    private EntityState reload(String entityId) {
        return entityStateService.getByEntityId(entityId)
                .orElseThrow(() -> new IllegalStateException("Entity disappeared while toggling: " + entityId));
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchCommandServiceTest
```

Erwartung: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/SwitchCommandService.java backend/src/test/java/com/household/manager/entitystate/SwitchCommandServiceTest.java
git commit -m "feat(switches): einheitliches Toggle ueber Geraete und Helfer"
```

---

## Task 6: SwitchQueryService (nutzungssortierte Liste)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java`
- Modify: `backend/src/main/java/com/household/manager/repository/EntityStateRepository.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java`

- [ ] **Step 1: Repository-Methode ergänzen**

In `backend/src/main/java/com/household/manager/repository/EntityStateRepository.java` den Import ergänzen und die Methode hinzufügen:

```java
import java.util.Collection;
```

```java
    List<EntityState> findByDomainInOrderByEntityIdAsc(Collection<EntityDomain> domains);
```

- [ ] **Step 2: Failing Test schreiben**

`backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java`:

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SwitchQueryServiceTest {

    @Mock
    private EntityStateRepository entityStateRepository;
    @Mock
    private EntityUsageService entityUsageService;

    private SwitchQueryService service;

    @BeforeEach
    void setUp() {
        service = new SwitchQueryService(entityStateRepository, entityUsageService,
                new SwitchResponseMapper(new EntityStateResponseMapper(new ObjectMapper())));
    }

    private EntityState device(String ref, String name) {
        return EntityState.builder()
                .entityId("switch.kasa_" + ref)
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef(ref)
                .friendlyName(name)
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private EntityUsage usage(String entityId, long count, LocalDateTime last) {
        return EntityUsage.builder().entityId(entityId).toggleCount(count).lastToggledAt(last).build();
    }

    private List<String> namesOf(List<SwitchResponse> switches) {
        return switches.stream().map(SwitchResponse::displayName).toList();
    }

    @Test
    void sortiert_meistgenutzte_zuerst() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Selten"), device("b", "Oft")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_a", usage("switch.kasa_a", 1, LocalDateTime.of(2026, 7, 15, 10, 0)),
                "switch.kasa_b", usage("switch.kasa_b", 9, LocalDateTime.of(2026, 7, 15, 10, 0))
        ));

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Oft", "Selten");
    }

    @Test
    void trennt_gleichstand_ueber_den_letzten_schaltzeitpunkt() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Aelter"), device("b", "Neuer")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_a", usage("switch.kasa_a", 3, LocalDateTime.of(2026, 7, 10, 10, 0)),
                "switch.kasa_b", usage("switch.kasa_b", 3, LocalDateTime.of(2026, 7, 15, 10, 0))
        ));

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Neuer", "Aelter");
    }

    @Test
    void sortiert_nie_genutzte_alphabetisch_ans_ende() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Zebra"), device("b", "Ampel"), device("c", "Genutzt")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_c", usage("switch.kasa_c", 2, LocalDateTime.of(2026, 7, 15, 10, 0))
        ));

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Genutzt", "Ampel", "Zebra");
    }

    @Test
    void begrenzt_die_liste_auf_das_limit() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Eins"), device("b", "Zwei"), device("c", "Drei")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(service.listSwitches(2)).hasSize(2);
    }

    @Test
    void ein_limit_groesser_als_die_liste_schneidet_nichts_ab() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Eins")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(service.listSwitches(10)).hasSize(1);
    }

    @Test
    void filtert_nicht_schaltbare_entitaeten_heraus() {
        EntityState zigbeeSwitch = EntityState.builder()
                .entityId("switch.zigbee_x")
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.ZIGBEE)
                .sourceRef("x")
                .friendlyName("Zigbee-Schalter")
                .state("on")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Steckdose"), zigbeeSwitch));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(namesOf(service.listSwitches(null))).containsExactly("Steckdose");
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchQueryServiceTest
```

Erwartung: Compile-Fehler „cannot find symbol: class SwitchQueryService".

- [ ] **Step 4: Service implementieren**

`backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java`:

```java
package com.household.manager.entitystate;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.SwitchResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Liefert die schaltbaren Entitäten, meistgenutzte zuerst.
 */
@Service
@RequiredArgsConstructor
public class SwitchQueryService {

    private final EntityStateRepository entityStateRepository;
    private final EntityUsageService entityUsageService;
    private final SwitchResponseMapper switchResponseMapper;

    /**
     * @param limit maximale Anzahl Einträge; null oder <= 0 liefert alle
     */
    @Transactional(readOnly = true)
    public List<SwitchResponse> listSwitches(Integer limit) {
        List<EntityState> switchable = entityStateRepository
                .findByDomainInOrderByEntityIdAsc(SwitchableEntities.SWITCHABLE_DOMAINS).stream()
                .filter(SwitchableEntities::isSwitchable)
                .toList();

        Map<String, EntityUsage> usage = entityUsageService.usageFor(
                switchable.stream().map(EntityState::getEntityId).toList());

        List<SwitchResponse> switches = switchable.stream()
                .map(entity -> switchResponseMapper.toResponse(entity, usage.get(entity.getEntityId())))
                .sorted(byUsage())
                .toList();

        if (limit != null && limit > 0 && limit < switches.size()) {
            return List.copyOf(switches.subList(0, limit));
        }
        return switches;
    }

    /** Meistgenutzt zuerst; bei Gleichstand zuletzt geschaltet, dann alphabetisch. */
    private Comparator<SwitchResponse> byUsage() {
        Comparator<SwitchResponse> byCount = Comparator.comparingLong(SwitchResponse::toggleCount);
        return byCount.reversed()
                .thenComparing(SwitchResponse::lastToggledAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(response -> response.displayName().toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchQueryServiceTest
```

Erwartung: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java backend/src/main/java/com/household/manager/repository/EntityStateRepository.java backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java
git commit -m "feat(switches): nutzungssortierte Schalterliste"
```

---

## Task 7: SwitchController

**Files:**
- Create: `backend/src/main/java/com/household/manager/controller/SwitchController.java`
- Test: `backend/src/test/java/com/household/manager/controller/SwitchControllerTest.java`

- [ ] **Step 1: Failing Test schreiben**

`backend/src/test/java/com/household/manager/controller/SwitchControllerTest.java`:

```java
package com.household.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SwitchControllerTest {

    @Mock
    private SwitchQueryService switchQueryService;
    @Mock
    private SwitchCommandService switchCommandService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SwitchController(switchQueryService, switchCommandService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private SwitchResponse response(String entityId, String name, String state) {
        return SwitchResponse.builder()
                .entityId(entityId)
                .domain("SWITCH")
                .source("KASA")
                .displayName(name)
                .state(state)
                .available(true)
                .icon("toggle_on")
                .toggleCount(3)
                .build();
    }

    @Test
    void liefert_die_schalterliste() throws Exception {
        when(switchQueryService.listSwitches(isNull()))
                .thenReturn(List.of(response("switch.kasa_abc", "Stehlampe", "on")));

        mockMvc.perform(get("/v1/switches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value("switch.kasa_abc"))
                .andExpect(jsonPath("$[0].displayName").value("Stehlampe"))
                .andExpect(jsonPath("$[0].state").value("on"))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].toggleCount").value(3));
    }

    @Test
    void reicht_das_limit_an_den_service_durch() throws Exception {
        when(switchQueryService.listSwitches(4)).thenReturn(List.of());

        mockMvc.perform(get("/v1/switches").param("limit", "4"))
                .andExpect(status().isOk());
    }

    @Test
    void schaltet_einen_schalter_um() throws Exception {
        when(switchCommandService.toggle("switch.kasa_abc"))
                .thenReturn(response("switch.kasa_abc", "Stehlampe", "off"));

        mockMvc.perform(post("/v1/switches/switch.kasa_abc/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("off"));
    }

    @Test
    void unbekannter_schalter_liefert_404() throws Exception {
        when(switchCommandService.toggle("switch.kasa_weg"))
                .thenThrow(new ResourceNotFoundException("Entity not found: switch.kasa_weg"));

        mockMvc.perform(post("/v1/switches/switch.kasa_weg/toggle"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nicht_schaltbare_entitaet_liefert_400() throws Exception {
        when(switchCommandService.toggle("sensor.zigbee_bad_temperature"))
                .thenThrow(new IllegalArgumentException("Entity is not switchable"));

        mockMvc.perform(post("/v1/switches/sensor.zigbee_bad_temperature/toggle"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchControllerTest
```

Erwartung: Compile-Fehler „cannot find symbol: class SwitchController".

- [ ] **Step 3: Controller implementieren**

`backend/src/main/java/com/household/manager/controller/SwitchController.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST-API für die schaltbaren Entitäten der Schalter-Kachel.
 */
@RestController
@RequestMapping("/v1/switches")
@RequiredArgsConstructor
@Slf4j
public class SwitchController {

    private final SwitchQueryService switchQueryService;
    private final SwitchCommandService switchCommandService;

    /**
     * @param limit optionale Obergrenze; ohne Angabe werden alle Schalter geliefert
     */
    @GetMapping
    public List<SwitchResponse> getSwitches(@RequestParam(required = false) Integer limit) {
        return switchQueryService.listSwitches(limit);
    }

    @PostMapping("/{entityId}/toggle")
    public SwitchResponse toggle(@PathVariable String entityId) {
        log.info("POST /api/v1/switches/{}/toggle", entityId);
        return switchCommandService.toggle(entityId);
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=SwitchControllerTest
```

Erwartung: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Alle neuen Backend-Tests gemeinsam laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest='EntityUsageServiceTest,SwitchableEntitiesTest,SwitchResponseMapperTest,SwitchCommandServiceTest,SwitchQueryServiceTest,SwitchControllerTest'
```

Erwartung: `Tests run: 36, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/SwitchController.java backend/src/test/java/com/household/manager/controller/SwitchControllerTest.java
git commit -m "feat(switches): REST-API /api/v1/switches"
```

---

## Task 8: Frontend-Model + SwitchService

**Files:**
- Create: `frontend/src/app/models/switch.model.ts`
- Create: `frontend/src/app/services/switch.service.ts`
- Test: `frontend/src/app/services/switch.service.spec.ts`

- [ ] **Step 1: Model anlegen**

`frontend/src/app/models/switch.model.ts`:

```typescript
/**
 * Ein schaltbarer Eintrag: SmartDevice-Steckdose (Kasa/Tapo/Meross) oder
 * manueller Boolean-Helfer.
 */
export interface SwitchEntity {
  entityId: string;
  domain: 'SWITCH' | 'INPUT_BOOLEAN';
  source: string;
  displayName: string;
  /** "on", "off" oder "unavailable". */
  state: string;
  available: boolean;
  /** Material-Symbols-Name. */
  icon: string;
  toggleCount: number;
  lastToggledAt: string | null;
}
```

- [ ] **Step 2: Failing Test schreiben**

`frontend/src/app/services/switch.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SwitchService } from './switch.service';
import { SwitchEntity } from '../models/switch.model';

describe('SwitchService', () => {
  let service: SwitchService;
  let httpMock: HttpTestingController;

  const entity: SwitchEntity = {
    entityId: 'switch.kasa_abc',
    domain: 'SWITCH',
    source: 'KASA',
    displayName: 'Stehlampe',
    state: 'on',
    available: true,
    icon: 'toggle_on',
    toggleCount: 3,
    lastToggledAt: '2026-07-15T20:00:00'
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(SwitchService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('laedt alle Schalter ohne Limit', () => {
    service.getSwitches().subscribe(result => expect(result).toEqual([entity]));

    const req = httpMock.expectOne('/api/v1/switches');
    expect(req.request.method).toBe('GET');
    req.flush([entity]);
  });

  it('reicht das Limit als Query-Parameter durch', () => {
    service.getSwitches(4).subscribe();

    const req = httpMock.expectOne(r => r.url === '/api/v1/switches');
    expect(req.request.params.get('limit')).toBe('4');
    req.flush([]);
  });

  it('schaltet einen Schalter um', () => {
    service.toggle('switch.kasa_abc').subscribe(result => expect(result).toEqual(entity));

    const req = httpMock.expectOne('/api/v1/switches/switch.kasa_abc/toggle');
    expect(req.request.method).toBe('POST');
    req.flush(entity);
  });

  it('meldet einen Fehler als Error weiter', () => {
    let failed = false;
    service.toggle('switch.kasa_abc').subscribe({ error: () => (failed = true) });

    httpMock.expectOne('/api/v1/switches/switch.kasa_abc/toggle')
      .flush('Geraet nicht erreichbar', { status: 502, statusText: 'Bad Gateway' });

    expect(failed).toBeTrue();
  });
});
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/switch.service.spec.ts'
```

Erwartung: Compile-Fehler „Cannot find module './switch.service'".

- [ ] **Step 4: Service implementieren**

`frontend/src/app/services/switch.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { SwitchEntity } from '../models/switch.model';

/**
 * REST-Service für die schaltbaren Entitäten (Schalter-Kachel und -Dialog).
 */
@Injectable({ providedIn: 'root' })
export class SwitchService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/switches';

  /** Schaltbare Entitäten, meistgenutzte zuerst. */
  getSwitches(limit?: number): Observable<SwitchEntity[]> {
    let params = new HttpParams();
    if (limit != null) {
      params = params.set('limit', limit);
    }
    return this.http.get<SwitchEntity[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  /** Schaltet eine Entität um und liefert ihren aktualisierten Zustand. */
  toggle(entityId: string): Observable<SwitchEntity> {
    return this.http.post<SwitchEntity>(`${this.baseUrl}/${entityId}/toggle`, {}).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Schalter-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Schalter-Anfrage.'));
  }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/switch.service.spec.ts'
```

Erwartung: `TOTAL: 4 SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/switch.model.ts frontend/src/app/services/switch.service.ts frontend/src/app/services/switch.service.spec.ts
git commit -m "feat(switches): Frontend-Service fuer die Schalter-API"
```

---

## Task 9: SwitchListComponent

Präsentationale Zeilenliste ohne eigenen Zustand und ohne Service-Aufrufe. Kachel und Dialog nutzen sie gemeinsam; `variant` steuert nur die Tonalität (Kachel = dunkles Glas, Dialog = heller Hintergrund).

**Files:**
- Create: `frontend/src/app/components/switch-list/switch-list.component.ts`
- Create: `frontend/src/app/components/switch-list/switch-list.component.html`
- Create: `frontend/src/app/components/switch-list/switch-list.component.scss`
- Test: `frontend/src/app/components/switch-list/switch-list.component.spec.ts`

- [ ] **Step 1: Failing Test schreiben**

`frontend/src/app/components/switch-list/switch-list.component.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { SwitchListComponent } from './switch-list.component';
import { SwitchEntity } from '../../models/switch.model';

describe('SwitchListComponent', () => {
  const entity = (overrides: Partial<SwitchEntity> = {}): SwitchEntity => ({
    entityId: 'switch.kasa_abc',
    domain: 'SWITCH',
    source: 'KASA',
    displayName: 'Stehlampe',
    state: 'on',
    available: true,
    icon: 'toggle_on',
    toggleCount: 3,
    lastToggledAt: null,
    ...overrides
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SwitchListComponent]
    }).compileComponents();
  });

  function render(switches: SwitchEntity[], pendingIds = new Set<string>()) {
    const fixture = TestBed.createComponent(SwitchListComponent);
    fixture.componentRef.setInput('switches', switches);
    fixture.componentRef.setInput('pendingIds', pendingIds);
    fixture.detectChanges();
    return fixture;
  }

  it('zeigt jeden Schalter mit Namen an', () => {
    const fixture = render([entity(), entity({ entityId: 'switch.kasa_x', displayName: 'Ventilator' })]);

    const text = (fixture.nativeElement as HTMLElement).textContent;
    expect(text).toContain('Stehlampe');
    expect(text).toContain('Ventilator');
  });

  it('beschriftet den Zustand', () => {
    const fixture = render([entity({ state: 'on' })]);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('An');
  });

  it('zeigt nicht verfuegbare Schalter als solche', () => {
    const fixture = render([entity({ state: 'unavailable', available: false })]);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nicht verfügbar');
  });

  it('emittiert den Schalter beim Klick', () => {
    const fixture = render([entity()]);
    const emitted: SwitchEntity[] = [];
    fixture.componentInstance.toggled.subscribe(item => emitted.push(item));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button')!;
    button.click();

    expect(emitted.length).toBe(1);
    expect(emitted[0].entityId).toBe('switch.kasa_abc');
  });

  it('deaktiviert Zeilen mit laufendem Schaltbefehl', () => {
    const fixture = render([entity()], new Set(['switch.kasa_abc']));

    const button = (fixture.nativeElement as HTMLElement).querySelector('button')!;
    expect(button.disabled).toBeTrue();
  });

  it('zeigt bei leerer Liste nichts an', () => {
    const fixture = render([]);

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('button').length).toBe(0);
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/switch-list.component.spec.ts'
```

Erwartung: Compile-Fehler „Cannot find module './switch-list.component'".

- [ ] **Step 3: Komponente implementieren**

`frontend/src/app/components/switch-list/switch-list.component.ts`:

```typescript
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SwitchEntity } from '../../models/switch.model';

/**
 * Praesentationale Liste von Schalter-Zeilen (Icon, Name, Zustand, Umschalter).
 * Haelt keinen Zustand und ruft keine Services auf: Kachel und Dialog reichen
 * die Daten herein und behandeln das `toggled`-Ereignis.
 */
@Component({
  selector: 'app-switch-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './switch-list.component.html',
  styleUrl: './switch-list.component.scss'
})
export class SwitchListComponent {
  @Input({ required: true }) switches: SwitchEntity[] = [];

  /** Entity-IDs mit laufendem Schaltbefehl; deren Zeilen sind gesperrt. */
  @Input() pendingIds: ReadonlySet<string> = new Set<string>();

  /** Tonalitaet: dunkle Kachel oder heller Dialog. */
  @Input() variant: 'tile' | 'dialog' = 'tile';

  @Output() toggled = new EventEmitter<SwitchEntity>();

  isOn(entity: SwitchEntity): boolean {
    return entity.state === 'on';
  }

  isPending(entity: SwitchEntity): boolean {
    return this.pendingIds.has(entity.entityId);
  }

  /** Beschriftung des Schalterzustands. */
  stateLabel(entity: SwitchEntity): string {
    if (!entity.available) {
      return 'Nicht verfügbar';
    }
    return this.isOn(entity) ? 'An' : 'Aus';
  }
}
```

`frontend/src/app/components/switch-list/switch-list.component.html`:

```html
<div class="switch-list" [ngClass]="'switch-list--' + variant">
  <button
    *ngFor="let item of switches"
    type="button"
    class="switch-list__row"
    [class.switch-list__row--on]="isOn(item)"
    [class.switch-list__row--unavailable]="!item.available"
    [disabled]="isPending(item)"
    [attr.aria-pressed]="isOn(item)"
    (click)="toggled.emit(item)"
  >
    <span class="material-symbols-outlined switch-list__icon">{{ item.icon }}</span>
    <span class="switch-list__name">{{ item.displayName }}</span>
    <span class="switch-list__state">{{ stateLabel(item) }}</span>
    <span class="switch-list__toggle" aria-hidden="true">
      <span class="switch-list__knob"></span>
    </span>
  </button>
</div>
```

`frontend/src/app/components/switch-list/switch-list.component.scss`:

```scss
.switch-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.switch-list__row {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 0.75rem;
  background: rgba(255, 255, 255, 0.04);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s ease, border-color 0.2s ease;

  &:hover:not(:disabled) {
    background: rgba(255, 255, 255, 0.08);
  }

  &:disabled {
    opacity: 0.5;
    cursor: progress;
  }

  &--unavailable {
    opacity: 0.5;
  }
}

.switch-list__icon {
  font-size: 20px;
  flex: none;
  opacity: 0.7;
}

.switch-list__name {
  flex: 1;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.switch-list__state {
  flex: none;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  opacity: 0.6;
}

// Umschalter (rein dekorativ; die ganze Zeile ist das Klickziel)
.switch-list__toggle {
  flex: none;
  position: relative;
  width: 38px;
  height: 22px;
  border-radius: var(--radius-full, 999px);
  background: rgba(255, 255, 255, 0.15);
  transition: background 0.2s ease;
}

.switch-list__knob {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.85);
  transition: transform 0.2s ease;
}

.switch-list__row--on {
  .switch-list__icon {
    opacity: 1;
    color: var(--secondary, #53e16f);
  }

  .switch-list__toggle {
    background: var(--secondary, #53e16f);
  }

  .switch-list__knob {
    transform: translateX(16px);
  }
}

// ---- Heller Dialog-Hintergrund -------------------------------------------
.switch-list--dialog {
  .switch-list__row {
    background: rgba(15, 23, 42, 0.04);
    border-color: rgba(15, 23, 42, 0.06);

    &:hover:not(:disabled) {
      background: rgba(15, 23, 42, 0.08);
    }
  }

  .switch-list__toggle {
    background: rgba(15, 23, 42, 0.2);
  }

  .switch-list__knob {
    background: #ffffff;
  }

  .switch-list__row--on {
    .switch-list__icon {
      color: #15803d;
    }

    .switch-list__toggle {
      background: #22c55e;
    }
  }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/switch-list.component.spec.ts'
```

Erwartung: `TOTAL: 6 SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/components/switch-list
git commit -m "feat(switches): SwitchListComponent fuer Kachel und Dialog"
```

---

## Task 10: Dashboard — Kachel, Dialog und Zustand

Die Kachel ersetzt den `rooms[0]`-Eintrag „Küche". Zustand und Dialog liegen im Dashboard, genau wie beim Energiefluss-Dialog (`.lumina-card` setzt `backdrop-filter`, `.lumina__fade` endet auf `transform` — beides würde ein `position: fixed`-Backdrop innerhalb der Kachel einsperren).

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (neu)

- [ ] **Step 1: Failing Test schreiben**

`frontend/src/app/pages/dashboard/dashboard.component.spec.ts`:

```typescript
import { TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { SwitchService } from '../../services/switch.service';
import { WeatherService } from '../../services/weather.service';
import { EnergyLiveService } from '../../services/energy-live.service';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { TemperatureService } from '../../services/temperature.service';
import { SwitchEntity } from '../../models/switch.model';

describe('DashboardComponent (Schalter)', () => {
  let switchServiceSpy: jasmine.SpyObj<SwitchService>;

  const entity = (overrides: Partial<SwitchEntity> = {}): SwitchEntity => ({
    entityId: 'switch.kasa_abc',
    domain: 'SWITCH',
    source: 'KASA',
    displayName: 'Stehlampe',
    state: 'off',
    available: true,
    icon: 'toggle_on',
    toggleCount: 3,
    lastToggledAt: null,
    ...overrides
  });

  beforeEach(async () => {
    switchServiceSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchServiceSpy.getSwitches.and.returnValue(of([entity()]));
    switchServiceSpy.toggle.and.returnValue(of(entity({ state: 'on' })));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        // Das Dashboard nutzt routerLink (Klima-Kachel) und braucht daher einen Router.
        provideRouter([]),
        { provide: SwitchService, useValue: switchServiceSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('laedt die meistgenutzten Schalter fuer die Kachel', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(4);
    expect(fixture.componentInstance.topSwitches.length).toBe(1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Stehlampe');

    discardPeriodicTasks();
  }));

  it('schaltet optimistisch und uebernimmt den Zustand aus der Antwort', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.topSwitches[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('setzt den Zustand bei einem Schaltfehler zurueck', fakeAsync(() => {
    switchServiceSpy.toggle.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();

    expect(fixture.componentInstance.topSwitches[0].state).toBe('off');
    expect(fixture.componentInstance.switchError).toContain('Stehlampe');

    discardPeriodicTasks();
  }));

  it('oeffnet den Dialog und laedt dafuer alle Schalter', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    switchServiceSpy.getSwitches.calls.reset();

    fixture.componentInstance.openSwitchDialog();
    tick();

    expect(fixture.componentInstance.switchDialogOpen).toBeTrue();
    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith();

    discardPeriodicTasks();
  }));

  it('laedt beim Schliessen des Dialogs die Kachel neu', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openSwitchDialog();
    tick();
    switchServiceSpy.getSwitches.calls.reset();

    fixture.componentInstance.closeSwitchDialog();
    tick();

    expect(fixture.componentInstance.switchDialogOpen).toBeFalse();
    expect(switchServiceSpy.getSwitches).toHaveBeenCalledWith(4);

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```

Erwartung: Fehler „'topSwitches' does not exist on type 'DashboardComponent'".

- [ ] **Step 3: Dashboard-TypeScript erweitern**

In `frontend/src/app/pages/dashboard/dashboard.component.ts`:

Imports ergänzen:

```typescript
import { SwitchService } from '../../services/switch.service';
import { SwitchEntity } from '../../models/switch.model';
import { SwitchListComponent } from '../../components/switch-list/switch-list.component';
```

`imports`-Array der `@Component`-Annotation um `SwitchListComponent` erweitern:

```typescript
  imports: [CommonModule, RouterLink, EnergyFlowComponent, SwitchListComponent],
```

Service injizieren (zu den bestehenden `inject`-Zeilen):

```typescript
  private readonly switchService = inject(SwitchService);
```

Subscription-Feld ergänzen (zu den bestehenden):

```typescript
  private switchSubscription?: Subscription;
```

Konstanten ergänzen (zu den bestehenden `private static readonly`-Feldern):

```typescript
  /** Anzahl der Schalter auf der Kachel; alle weiteren stehen im Dialog. */
  private static readonly SWITCH_TILE_LIMIT = 4;
  /** Aktualisierungsintervall der Schalter-Kachel (30 s). */
  private static readonly SWITCH_REFRESH_MS = 30000;
```

Zustandsfelder ergänzen (bei den bestehenden öffentlichen Feldern):

```typescript
  /** Meistgenutzte Schalter fuer die Kachel. */
  topSwitches: SwitchEntity[] = [];
  /** Alle Schalter; nur geladen, solange der Schalter-Dialog offen ist. */
  allSwitches: SwitchEntity[] = [];
  /** True, wenn der Schalter-Dialog geoeffnet ist. */
  switchDialogOpen = false;
  /** Entity-IDs mit laufendem Schaltbefehl (verhindert Doppelklicks). */
  readonly pendingSwitchIds = new Set<string>();
  switchError: string | null = null;
```

`rooms` auf den verbleibenden Platzhalter reduzieren (der „Küche"-Eintrag entfällt, die Kachel ersetzt ihn):

```typescript
  /** Raum-Kacheln (Platzhalter, spaeter aus Entitaeten befuellbar). */
  readonly rooms: RoomTile[] = [
    { name: 'Schlafzimmer', icon: 'bed', status: 'Ruhe', tone: 'idle', detail: 'Luftreiniger: An • Rollo: Zu' }
  ];
```

`ngOnInit` um den Start des Schalter-Refresh erweitern:

```typescript
  ngOnInit(): void {
    this.startClock();
    this.loadWeather();
    this.startLiveStream();
    this.startClimateRefresh();
    this.startSwitchRefresh();
  }
```

`ngOnDestroy` erweitern (vor `this.closeFlowDialog();`):

```typescript
    this.switchSubscription?.unsubscribe();
```

Neue Methoden hinzufügen (z. B. direkt nach `closeFlowDialog`):

```typescript
  /**
   * Schaltet einen Schalter direkt. Der Zustand wird optimistisch umgeschaltet und
   * bei einem Fehler zurueckgesetzt, damit die Kachel sofort reagiert.
   */
  toggleSwitch(entity: SwitchEntity): void {
    if (this.pendingSwitchIds.has(entity.entityId)) {
      return;
    }
    const previousState = entity.state;
    this.pendingSwitchIds.add(entity.entityId);
    this.switchError = null;
    this.applySwitchState(entity.entityId, entity.state === 'on' ? 'off' : 'on');

    this.switchService.toggle(entity.entityId).subscribe({
      next: updated => {
        this.pendingSwitchIds.delete(entity.entityId);
        this.applySwitchState(updated.entityId, updated.state);
      },
      error: () => {
        this.pendingSwitchIds.delete(entity.entityId);
        this.applySwitchState(entity.entityId, previousState);
        this.switchError = `${entity.displayName} konnte nicht geschaltet werden.`;
      }
    });
  }

  /**
   * Setzt den Zustand in Kachel- und Dialogliste. Die Zuordnung laeuft ueber die
   * entityId, damit sie auch nach einem zwischenzeitlichen Neuladen greift.
   */
  private applySwitchState(entityId: string, state: string): void {
    for (const list of [this.topSwitches, this.allSwitches]) {
      const match = list.find(item => item.entityId === entityId);
      if (match) {
        match.state = state;
      }
    }
  }

  /** Oeffnet den Schalter-Dialog und laedt dafuer die vollstaendige Liste. */
  openSwitchDialog(): void {
    if (this.switchDialogOpen) {
      return;
    }
    this.switchDialogOpen = true;
    this.switchService.getSwitches().subscribe({
      next: switches => (this.allSwitches = switches),
      error: () => (this.switchError = 'Schalter konnten nicht geladen werden.')
    });
  }

  /** Schliesst den Dialog und laedt die Kachel neu (die Reihenfolge kann sich geaendert haben). */
  closeSwitchDialog(): void {
    if (!this.switchDialogOpen) {
      return;
    }
    this.switchDialogOpen = false;
    this.allSwitches = [];
    this.loadTopSwitches();
  }

  private startSwitchRefresh(): void {
    this.switchSubscription = interval(DashboardComponent.SWITCH_REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.topSwitchRequest())
      )
      .subscribe(switches => (this.topSwitches = switches));
  }

  private loadTopSwitches(): void {
    this.topSwitchRequest().subscribe(switches => (this.topSwitches = switches));
  }

  private topSwitchRequest() {
    return this.switchService.getSwitches(DashboardComponent.SWITCH_TILE_LIMIT).pipe(
      catchError(() => of<SwitchEntity[]>([]))
    );
  }
```

Die bestehende `onEscape`-Methode schließt beide Dialoge:

```typescript
  /** Schliesst die geoeffneten Dialoge per Escape-Taste. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeFlowDialog();
    this.closeSwitchDialog();
  }
```

- [ ] **Step 4: Kachel und Dialog ins Template einsetzen**

In `frontend/src/app/pages/dashboard/dashboard.component.html` den Block der Raum-Platzhalter-Kacheln (`<button *ngFor="let room of rooms; ...">`, aktuell Zeilen 89–107) **unverändert lassen** und **direkt davor** die Schalter-Kachel einfügen:

```html
        <!-- Schalter-Kachel: meistgenutzte Schalter, direkt schaltbar -->
        <div
          class="lumina-card lumina__room lumina__switch-tile lumina__fade"
          style="--delay: 0.2s"
        >
          <div class="lumina__room-top">
            <div class="lumina__room-icon">
              <span class="material-symbols-outlined">toggle_on</span>
            </div>
            <button
              type="button"
              class="lumina__switch-all"
              (click)="openSwitchDialog()"
              title="Alle Schalter anzeigen"
              aria-label="Alle Schalter anzeigen"
            >
              <span class="material-symbols-outlined">expand_content</span>
            </button>
          </div>
          <div class="lumina__room-body">
            <h3 class="lumina__room-name">Schalter</h3>
            <p *ngIf="topSwitches.length === 0" class="lumina__switch-empty">
              Keine Schalter
            </p>
            <app-switch-list
              [switches]="topSwitches"
              [pendingIds]="pendingSwitchIds"
              variant="tile"
              (toggled)="toggleSwitch($event)"
            ></app-switch-list>
            <p *ngIf="switchError" class="lumina__switch-error">{{ switchError }}</p>
          </div>
        </div>
```

Am Ende der Datei, **nach** dem Energiefluss-Dialog und **vor** dem schließenden `</div>` des `.lumina`-Containers, den Schalter-Dialog einfügen:

```html
  <!-- Schalter-Dialog (oeffnet sich ueber den Button in der Schalter-Kachel) -->
  <div
    *ngIf="switchDialogOpen"
    class="lumina__dialog-backdrop"
    (click)="closeSwitchDialog()"
  >
    <div
      class="lumina__dialog"
      role="dialog"
      aria-modal="true"
      aria-label="Alle Schalter"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <h2 class="lumina__dialog-title">Alle Schalter</h2>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="closeSwitchDialog()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>
      <div class="lumina__dialog-body">
        <p *ngIf="allSwitches.length === 0" class="lumina__switch-empty">
          Keine Schalter
        </p>
        <app-switch-list
          [switches]="allSwitches"
          [pendingIds]="pendingSwitchIds"
          variant="dialog"
          (toggled)="toggleSwitch($event)"
        ></app-switch-list>
      </div>
    </div>
  </div>
```

- [ ] **Step 5: Styles ergänzen**

In `frontend/src/app/pages/dashboard/dashboard.component.scss` direkt nach dem `.lumina__climate-tile`-Block (endet aktuell Zeile 260) einfügen:

```scss
// ---- Schalter-Kachel -----------------------------------------------------
.lumina__switch-tile {
  // Die Kachel ist kein einzelnes Klickziel mehr: die Zeilen darin sind es.
  cursor: default;

  &:hover,
  &:active {
    transform: none;
  }

  .lumina__room-body {
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-height: 0;
    overflow: hidden;
  }
}

.lumina__switch-all {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: var(--radius-full);
  background: rgba(255, 255, 255, 0.05);
  color: rgba(192, 198, 214, 0.7);
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;

  &:hover {
    background: var(--primary);
    color: var(--on-primary);
  }

  .material-symbols-outlined {
    font-size: 20px;
  }
}

.lumina__switch-empty {
  margin: 0;
  font-size: 14px;
  color: rgba(192, 198, 214, 0.7);
}

.lumina__switch-error {
  margin: 0;
  font-size: 12px;
  color: #fca5a5;
}
```

- [ ] **Step 6: Tests laufen lassen — muss grün sein**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```

Erwartung: `TOTAL: 5 SUCCESS`.

- [ ] **Step 7: Gesamte Frontend-Suite laufen lassen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartung: alle Specs grün (keine Regression durch das geänderte `rooms`-Array).

- [ ] **Step 8: Produktionsbuild prüfen**

```bash
cd frontend && npm run build
```

Erwartung: erfolgreicher Build ohne Template-Fehler.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(dashboard): Schalter-Kachel mit Dialog statt Kueche-Platzhalter"
```

---

## Abschluss

- [ ] **Backend-Gesamtlauf**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test
```

Erwartung: alle Tests grün **außer** `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` — diese scheitern vorbestehend an der lokal nicht erreichbaren Test-DB („Access denied for user 'root'@'localhost'") und sind kein Ergebnis dieser Änderung.

- [ ] **Manuelle Prüfung im laufenden System**

Backend (`mvn spring-boot:run`) und Frontend (`npm start`) starten, Dashboard öffnen und prüfen:
1. Die Schalter-Kachel steht rechts neben der Temperaturen-Kachel und zeigt bis zu 4 Schalter.
2. Ein Klick auf eine Zeile schaltet das Gerät und der Umschalter springt sofort um.
3. Der Button oben rechts in der Kachel öffnet den Dialog mit allen Schaltern; Backdrop-Klick und Escape schließen ihn.
4. Der Dialog liegt bildschirmfüllend über dem Dashboard (nicht in die Kachel eingesperrt).
5. Nach mehrfachem Schalten eines Schalters rutscht dieser in der Kachel nach oben (Neuladen per Dialog schließen oder 30-s-Intervall abwarten).
