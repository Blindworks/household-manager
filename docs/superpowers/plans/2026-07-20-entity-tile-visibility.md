# Kachel-Sichtbarkeit für Entitäten — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entitäten bekommen eine benutzergepflegte Sichtbarkeitsregel pro Dashboard-Kachel (`ALWAYS` / `AUTO` / `WHEN_ON` / `NEVER`), sodass z. B. die Waschmaschinen-Steckdose nur bei Zustand „on" prominent auf der Schalter-Kachel erscheint.

**Architecture:** Neue Tabelle `entity_tile_visibility` (Entität→Kachel→Regel; kein Eintrag = `AUTO`), gekapselt in `EntityTileVisibilityService`. `SwitchQueryService` bekommt eine Kachel-Sicht (`view=tile`) mit Filterung (`NEVER`, inaktive `WHEN_ON`) und Gruppen-Sortierung (aktive `WHEN_ON` → `ALWAYS` → Rest nach Nutzung). Gepflegt wird die Regel über `PUT /v1/entities/{entityId}/tiles/{tileKey}` und ein Dropdown auf der Entitäten-Seite.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / Lombok (Backend), Angular 19 standalone / Signals (Frontend), JUnit 5 + Mockito + MockMvc, Jasmine/Karma.

**Spec:** `docs/superpowers/specs/2026-07-20-entity-tile-visibility-design.md`

## Wichtige Umgebungshinweise

- **Backend-Maven braucht JDK 21** (Standard der Maschine ist JDK 17). Vor jedem `mvn`:
  - PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'`
  - Bash: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`
  - Maven-Kommandos aus dem Verzeichnis `backend/` ausführen. Es gibt keinen `mvnw`-Wrapper.
- Die Integrationstests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal designbedingt fehl (keine Test-DB). Immer gezielt mit `-Dtest=...` testen.
- Frontend-Tests: aus `frontend/` mit `npx ng test --watch=false`.
- JPA-Repositories MÜSSEN in `com.household.manager.repository` liegen (JpaConfig scannt nur dieses Paket).

## File-Struktur (Übersicht)

| Datei | Zweck |
| --- | --- |
| `backend/src/main/resources/db/changelog/changes/20260720-0035-create-entity-tile-visibility-table.xml` | Neue Tabelle (Create) |
| `backend/src/main/resources/db/changelog/db.changelog-master.xml` | Include des Changesets (Modify) |
| `backend/src/main/java/com/household/manager/entitystate/TileVisibility.java` | Enum der Sichtbarkeits-Stufen (Create) |
| `backend/src/main/java/com/household/manager/entitystate/DashboardTiles.java` | Bekannte Kachel-Keys (Create) |
| `backend/src/main/java/com/household/manager/model/entity/EntityTileVisibility.java` | JPA-Entity (Create) |
| `backend/src/main/java/com/household/manager/repository/EntityTileVisibilityRepository.java` | Repository (Create) |
| `backend/src/main/java/com/household/manager/entitystate/EntityTileVisibilityService.java` | Lese-/Schreiblogik der Regeln (Create) |
| `backend/src/main/java/com/household/manager/dto/UpdateTileVisibilityRequest.java` | Request-DTO (Create) |
| `backend/src/main/java/com/household/manager/dto/EntityStateResponse.java` | + Feld `tileVisibility` (Modify) |
| `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java` | Overload mit Sichtbarkeits-Map (Modify) |
| `backend/src/main/java/com/household/manager/controller/EntityStateController.java` | Neuer PUT-Endpoint, Map in Antworten (Modify) |
| `backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java` | Kachel-Sicht: Filter + Gruppen-Sortierung (Modify) |
| `backend/src/main/java/com/household/manager/controller/SwitchController.java` | `view`-Parameter (Modify) |
| `frontend/src/app/models/entity-state.model.ts` | Typ `TileVisibility`, Feld `tileVisibility` (Modify) |
| `frontend/src/app/services/entity-state.service.ts` | `setTileVisibility(...)` (Modify) |
| `frontend/src/app/services/switch.service.ts` | `view`-Parameter (Modify) |
| `frontend/src/app/pages/entities/entities.component.{ts,html}` | Dropdown „Schalter-Kachel" (Modify) |
| `frontend/src/app/pages/entities/entities.component.spec.ts` | Neue Specs (Create) |
| `frontend/src/app/pages/dashboard/dashboard.component.ts` | Kachel ruft `view=tile` (Modify) |

---

### Task 1: Liquibase-Changeset für `entity_tile_visibility`

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260720-0035-create-entity-tile-visibility-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (am Ende, vor `</databaseChangeLog>`)

- [ ] **Step 1: Changeset-Datei anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260720-0035-create-entity-tile-visibility-table" author="household-manager">
        <preConditions onFail="MARK_RAN">
            <not>
                <tableExists tableName="entity_tile_visibility"/>
            </not>
        </preConditions>
        <createTable tableName="entity_tile_visibility">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="entity_id" type="VARCHAR(150)">
                <constraints nullable="false"/>
            </column>
            <column name="tile_key" type="VARCHAR(50)">
                <constraints nullable="false"/>
            </column>
            <column name="visibility" type="VARCHAR(20)">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>
        <addUniqueConstraint
                tableName="entity_tile_visibility"
                columnNames="entity_id, tile_key"
                constraintName="uk_entity_tile_visibility"/>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Include im Master-Changelog ergänzen**

In `db.changelog-master.xml` nach dem letzten Include (`20260716-0034-create-waste-collection-events-table.xml`) einfügen:

```xml
    <!-- Kachel-Sichtbarkeit fuer Entitaeten -->
    <include file="db/changelog/changes/20260720-0035-create-entity-tile-visibility-table.xml"/>
```

- [ ] **Step 3: Kompilieren (validiert XML-Wohlgeformtheit im Classpath-Build)**

Aus `backend/` (PowerShell):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn compile -q
```
Erwartet: BUILD SUCCESS (Liquibase läuft erst beim App-Start; hier geht es nur darum, dass nichts kaputt ist).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog
git commit -m "feat(entities): Liquibase-Tabelle entity_tile_visibility"
```

---

### Task 2: `TileVisibility`-Enum, `DashboardTiles`, JPA-Entity, Repository

Reine Datenklassen ohne Logik — kein TDD nötig; die Logik folgt in Task 3 testgetrieben.

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/TileVisibility.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/DashboardTiles.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/EntityTileVisibility.java`
- Create: `backend/src/main/java/com/household/manager/repository/EntityTileVisibilityRepository.java`

- [ ] **Step 1: Enum `TileVisibility`**

```java
package com.household.manager.entitystate;

import java.util.Locale;
import java.util.Optional;

/**
 * Sichtbarkeitsregel einer Entität auf einer Dashboard-Kachel.
 * {@code AUTO} ist der Standard und wird nie persistiert (kein Eintrag = AUTO).
 */
public enum TileVisibility {

    /** Immer auf der Kachel anzeigen (gepinnt). */
    ALWAYS,
    /** Standard: nutzungsbasierte Platzvergabe wie bisher. */
    AUTO,
    /** Nur anzeigen, solange der Zustand "on" ist (z. B. fertige Waschmaschine). */
    WHEN_ON,
    /** Nie auf der Kachel anzeigen. */
    NEVER;

    /** Case-insensitives Parsen; leer bei unbekanntem Wert. */
    public static Optional<TileVisibility> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 2: Konstantenklasse `DashboardTiles`**

```java
package com.household.manager.entitystate;

import java.util.Set;

/**
 * Stabile Schlüssel der Dashboard-Kacheln, für die Sichtbarkeitsregeln
 * gepflegt werden können. Unbekannte Keys lehnt die API ab.
 */
public final class DashboardTiles {

    /** Schalter-Kachel des Dashboards. */
    public static final String SWITCHES = "switches";

    private static final Set<String> KNOWN = Set.of(SWITCHES);

    private DashboardTiles() {
    }

    public static boolean isKnown(String tileKey) {
        return tileKey != null && KNOWN.contains(tileKey);
    }
}
```

- [ ] **Step 3: JPA-Entity `EntityTileVisibility`**

```java
package com.household.manager.model.entity;

import com.household.manager.entitystate.TileVisibility;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Benutzergepflegte Sichtbarkeitsregel einer Entität auf einer Dashboard-Kachel.
 * Eine Zeile je (Entität, Kachel); kein Eintrag bedeutet AUTO. Wird ausschließlich
 * benutzerinitiiert geschrieben, nie vom Polling-Upsert der Integrationen.
 */
@Entity
@Table(name = "entity_tile_visibility")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityTileVisibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Entity-ID der Spiegel-Schicht (Fremdschlüssel-Semantik, bewusst ohne FK-Constraint). */
    @Column(name = "entity_id", nullable = false, length = 150)
    private String entityId;

    /** Stabiler Kachel-Schlüssel, siehe {@link com.household.manager.entitystate.DashboardTiles}. */
    @Column(name = "tile_key", nullable = false, length = 50)
    private String tileKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private TileVisibility visibility;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Repository (MUSS in `com.household.manager.repository` liegen)**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.EntityTileVisibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EntityTileVisibilityRepository extends JpaRepository<EntityTileVisibility, Long> {

    Optional<EntityTileVisibility> findByEntityIdAndTileKey(String entityId, String tileKey);

    List<EntityTileVisibility> findByTileKey(String tileKey);

    List<EntityTileVisibility> findByEntityIdIn(Collection<String> entityIds);

    void deleteByEntityIdAndTileKey(String entityId, String tileKey);
}
```

- [ ] **Step 5: Kompilieren**

Aus `backend/`:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn compile -q
```
Erwartet: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java
git commit -m "feat(entities): TileVisibility-Enum, DashboardTiles und Persistenz-Bausteine"
```

---

### Task 3: `EntityTileVisibilityService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/EntityTileVisibilityService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityTileVisibilityServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityTileVisibilityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntityTileVisibilityServiceTest {

    @Mock
    private EntityTileVisibilityRepository repository;

    @InjectMocks
    private EntityTileVisibilityService service;

    private EntityTileVisibility rule(String entityId, String tileKey, TileVisibility visibility) {
        return EntityTileVisibility.builder()
                .entityId(entityId)
                .tileKey(tileKey)
                .visibility(visibility)
                .updatedAt(LocalDateTime.of(2026, 7, 20, 10, 0))
                .build();
    }

    @Test
    void legt_neue_regel_an() {
        when(repository.findByEntityIdAndTileKey("switch.kasa_wm", "switches"))
                .thenReturn(Optional.empty());

        service.setVisibility("switch.kasa_wm", "switches", TileVisibility.WHEN_ON);

        ArgumentCaptor<EntityTileVisibility> captor = ArgumentCaptor.forClass(EntityTileVisibility.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEntityId()).isEqualTo("switch.kasa_wm");
        assertThat(captor.getValue().getTileKey()).isEqualTo("switches");
        assertThat(captor.getValue().getVisibility()).isEqualTo(TileVisibility.WHEN_ON);
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void aktualisiert_bestehende_regel() {
        EntityTileVisibility existing = rule("switch.kasa_wm", "switches", TileVisibility.WHEN_ON);
        when(repository.findByEntityIdAndTileKey("switch.kasa_wm", "switches"))
                .thenReturn(Optional.of(existing));

        service.setVisibility("switch.kasa_wm", "switches", TileVisibility.NEVER);

        ArgumentCaptor<EntityTileVisibility> captor = ArgumentCaptor.forClass(EntityTileVisibility.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getVisibility()).isEqualTo(TileVisibility.NEVER);
    }

    @Test
    void auto_loescht_die_regel_statt_sie_zu_speichern() {
        service.setVisibility("switch.kasa_wm", "switches", TileVisibility.AUTO);

        verify(repository).deleteByEntityIdAndTileKey("switch.kasa_wm", "switches");
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void liefert_die_regeln_einer_kachel_als_map() {
        when(repository.findByTileKey("switches")).thenReturn(List.of(
                rule("switch.kasa_wm", "switches", TileVisibility.WHEN_ON),
                rule("switch.kasa_stehlampe", "switches", TileVisibility.ALWAYS)
        ));

        Map<String, TileVisibility> rules = service.tileRules("switches");

        assertThat(rules).containsExactlyInAnyOrderEntriesOf(Map.of(
                "switch.kasa_wm", TileVisibility.WHEN_ON,
                "switch.kasa_stehlampe", TileVisibility.ALWAYS
        ));
    }

    @Test
    void gruppiert_regeln_je_entitaet_fuer_die_api_antwort() {
        when(repository.findByEntityIdIn(List.of("switch.kasa_wm"))).thenReturn(List.of(
                rule("switch.kasa_wm", "switches", TileVisibility.WHEN_ON)
        ));

        Map<String, Map<String, String>> byEntity = service.visibilityByEntity(List.of("switch.kasa_wm"));

        assertThat(byEntity).containsExactlyEntriesOf(
                Map.of("switch.kasa_wm", Map.of("switches", "WHEN_ON")));
    }

    @Test
    void leere_entity_liste_fragt_das_repository_nicht_ab() {
        assertThat(service.visibilityByEntity(List.of())).isEmpty();
        verify(repository, never()).findByEntityIdIn(org.mockito.ArgumentMatchers.anyCollection());
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

Aus `backend/`:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityTileVisibilityServiceTest" -q
```
Erwartet: COMPILATION ERROR (Klasse `EntityTileVisibilityService` existiert nicht).

- [ ] **Step 3: Service implementieren**

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityTileVisibilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verwaltet die benutzergepflegten Sichtbarkeitsregeln von Entitäten auf
 * Dashboard-Kacheln. Kein Eintrag bedeutet {@link TileVisibility#AUTO};
 * AUTO wird deshalb nie gespeichert, sondern löscht die Regel.
 */
@Service
@RequiredArgsConstructor
public class EntityTileVisibilityService {

    private final EntityTileVisibilityRepository repository;

    /** Setzt die Regel einer Entität für eine Kachel; AUTO entfernt sie. */
    @Transactional
    public void setVisibility(String entityId, String tileKey, TileVisibility visibility) {
        if (visibility == TileVisibility.AUTO) {
            repository.deleteByEntityIdAndTileKey(entityId, tileKey);
            return;
        }
        EntityTileVisibility rule = repository.findByEntityIdAndTileKey(entityId, tileKey)
                .orElseGet(() -> EntityTileVisibility.builder()
                        .entityId(entityId)
                        .tileKey(tileKey)
                        .build());
        rule.setVisibility(visibility);
        rule.setUpdatedAt(LocalDateTime.now());
        repository.save(rule);
    }

    /** Alle expliziten Regeln einer Kachel, nach Entity-ID indiziert. */
    @Transactional(readOnly = true)
    public Map<String, TileVisibility> tileRules(String tileKey) {
        return repository.findByTileKey(tileKey).stream()
                .collect(Collectors.toMap(
                        EntityTileVisibility::getEntityId,
                        EntityTileVisibility::getVisibility));
    }

    /** Regeln der angefragten Entitäten als Map entityId → (tileKey → visibility). */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> visibilityByEntity(Collection<String> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByEntityIdIn(entityIds).stream()
                .collect(Collectors.groupingBy(
                        EntityTileVisibility::getEntityId,
                        Collectors.toMap(
                                EntityTileVisibility::getTileKey,
                                rule -> rule.getVisibility().name())));
    }
}
```

- [ ] **Step 4: Test ausführen — muss grün sein**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityTileVisibilityServiceTest" -q
```
Erwartet: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(entities): EntityTileVisibilityService fuer Kachel-Regeln"
```

---

### Task 4: API — `PUT /v1/entities/{entityId}/tiles/{tileKey}` + `tileVisibility` in der Antwort (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/UpdateTileVisibilityRequest.java`
- Modify: `backend/src/main/java/com/household/manager/dto/EntityStateResponse.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java`
- Modify: `backend/src/main/java/com/household/manager/controller/EntityStateController.java`
- Test: `backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java` (erweitern)

- [ ] **Step 1: Failing Tests in `EntityStateControllerTest` ergänzen**

Der bestehende `setUp()` baut den Controller mit `new EntityStateController(entityStateService, responseMapper)`. Der Konstruktor bekommt eine dritte Abhängigkeit — `setUp()` anpassen und ein neues Mock-Feld ergänzen:

```java
// neues Mock-Feld neben entityStateService:
@Mock
private EntityTileVisibilityService tileVisibilityService;

// in setUp() die Controller-Zeile ersetzen durch:
mockMvc = MockMvcBuilders.standaloneSetup(
                new EntityStateController(entityStateService, tileVisibilityService, responseMapper))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
        .build();
```

Neue Imports oben ergänzen:

```java
import com.household.manager.entitystate.EntityTileVisibilityService;
import com.household.manager.entitystate.TileVisibility;
import java.util.Map;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
```

Neue Tests am Ende der Klasse:

```java
    @Test
    void setztTileVisibilityUndLiefertDieEntitaet() throws Exception {
        when(entityStateService.getByEntityId("sensor.zigbee_wohnzimmer_temperature"))
                .thenReturn(Optional.of(sensor()));
        when(tileVisibilityService.visibilityByEntity(List.of("sensor.zigbee_wohnzimmer_temperature")))
                .thenReturn(Map.of("sensor.zigbee_wohnzimmer_temperature", Map.of("switches", "WHEN_ON")));

        mockMvc.perform(put("/v1/entities/sensor.zigbee_wohnzimmer_temperature/tiles/switches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"WHEN_ON\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tileVisibility.switches").value("WHEN_ON"));

        verify(tileVisibilityService).setVisibility(
                "sensor.zigbee_wohnzimmer_temperature", "switches", TileVisibility.WHEN_ON);
    }

    @Test
    void tileVisibilityFuerUnbekannteEntitaetLiefert404() throws Exception {
        when(entityStateService.getByEntityId("switch.weg")).thenReturn(Optional.empty());

        mockMvc.perform(put("/v1/entities/switch.weg/tiles/switches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"WHEN_ON\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unbekannterTileKeyLiefert400() throws Exception {
        mockMvc.perform(put("/v1/entities/sensor.zigbee_wohnzimmer_temperature/tiles/unbekannt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"WHEN_ON\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unbekannterVisibilityWertLiefert400() throws Exception {
        mockMvc.perform(put("/v1/entities/sensor.zigbee_wohnzimmer_temperature/tiles/switches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"MANCHMAL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listeEnthaeltTileVisibilityDerEntitaeten() throws Exception {
        when(entityStateService.find(null, null)).thenReturn(List.of(sensor()));
        when(tileVisibilityService.visibilityByEntity(anyCollection()))
                .thenReturn(Map.of("sensor.zigbee_wohnzimmer_temperature", Map.of("switches", "NEVER")));

        mockMvc.perform(get("/v1/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tileVisibility.switches").value("NEVER"));
    }
```

Hinweis: Die bestehenden Tests, die `responseMapper.toResponse(entity)` indirekt nutzen, bleiben unverändert gültig — für sie liefert `visibilityByEntity` per Mock-Default eine leere Map, `tileVisibility` ist dann `{}`.

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityStateControllerTest" -q
```
Erwartet: COMPILATION ERROR (`EntityTileVisibilityService`-Konstruktor-Parameter, `UpdateTileVisibilityRequest` fehlt).

- [ ] **Step 3: Request-DTO anlegen**

```java
package com.household.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Setzen der Kachel-Sichtbarkeit einer Entität.
 * "AUTO" entfernt die Regel (Standardverhalten).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTileVisibilityRequest {

    @NotBlank(message = "Visibility must not be blank")
    private String visibility;
}
```

- [ ] **Step 4: `EntityStateResponse` erweitern**

Das Record-Feld `tileVisibility` ergänzen (nach `attributes`):

```java
@Builder
public record EntityStateResponse(
        String entityId,
        String domain,
        String source,
        String sourceRef,
        String friendlyName,
        String customName,
        String displayName,
        String state,
        Map<String, Object> attributes,
        Map<String, String> tileVisibility,
        LocalDateTime lastChanged,
        LocalDateTime lastUpdated
) {
}
```

- [ ] **Step 5: Mapper-Overload in `EntityStateResponseMapper`**

Die bestehende Methode `toResponse(EntityState entity)` delegiert an eine neue Überladung; alle anderen Aufrufer bleiben unverändert:

```java
    public EntityStateResponse toResponse(EntityState entity) {
        return toResponse(entity, Map.of());
    }

    /** @param tileVisibility explizite Kachel-Regeln der Entität (tileKey → visibility) */
    public EntityStateResponse toResponse(EntityState entity, Map<String, String> tileVisibility) {
        return EntityStateResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .sourceRef(entity.getSourceRef())
                .friendlyName(entity.getFriendlyName())
                .customName(entity.getCustomName())
                .displayName(displayName(entity))
                .state(entity.getState())
                .attributes(parseAttributes(entity.getAttributes()))
                .tileVisibility(tileVisibility)
                .lastChanged(entity.getLastChanged())
                .lastUpdated(entity.getLastUpdated())
                .build();
    }
```

- [ ] **Step 6: Controller erweitern**

`EntityStateController`: neue Abhängigkeit + Endpoint + Sichtbarkeit in den GET-Antworten.

```java
package com.household.manager.controller;

import com.household.manager.dto.EntityStateResponse;
import com.household.manager.dto.UpdateEntityCustomNameRequest;
import com.household.manager.dto.UpdateTileVisibilityRequest;
import com.household.manager.entitystate.DashboardTiles;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityTileVisibilityService;
import com.household.manager.entitystate.TileVisibility;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST-API für die generische Entity-/State-Schicht.
 */
@RestController
@RequestMapping("/v1/entities")
@RequiredArgsConstructor
@Slf4j
public class EntityStateController {

    private final EntityStateService entityStateService;
    private final EntityTileVisibilityService tileVisibilityService;
    private final EntityStateResponseMapper responseMapper;

    @GetMapping
    public List<EntityStateResponse> getEntities(
            @RequestParam(required = false) EntityDomain domain,
            @RequestParam(required = false) EntitySource source) {
        List<EntityState> entities = entityStateService.find(domain, source);
        Map<String, Map<String, String>> visibility = tileVisibilityService.visibilityByEntity(
                entities.stream().map(EntityState::getEntityId).toList());
        return entities.stream()
                .map(entity -> responseMapper.toResponse(
                        entity, visibility.getOrDefault(entity.getEntityId(), Map.of())))
                .toList();
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<EntityStateResponse> getEntity(@PathVariable String entityId) {
        return entityStateService.getByEntityId(entityId)
                .map(entity -> ResponseEntity.ok(toResponseWithVisibility(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{entityId}")
    public ResponseEntity<Void> deleteEntity(@PathVariable String entityId) {
        boolean deleted = entityStateService.deleteByEntityId(entityId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/{entityId}/custom-name")
    public ResponseEntity<EntityStateResponse> setCustomName(
            @PathVariable String entityId,
            @Valid @RequestBody UpdateEntityCustomNameRequest request) {
        return entityStateService.setCustomName(entityId, request.getCustomName())
                .map(entity -> ResponseEntity.ok(toResponseWithVisibility(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Setzt die Sichtbarkeitsregel einer Entität für eine Dashboard-Kachel.
     * "AUTO" entfernt die Regel (Standardverhalten).
     */
    @PutMapping("/{entityId}/tiles/{tileKey}")
    public ResponseEntity<EntityStateResponse> setTileVisibility(
            @PathVariable String entityId,
            @PathVariable String tileKey,
            @Valid @RequestBody UpdateTileVisibilityRequest request) {
        if (!DashboardTiles.isKnown(tileKey)) {
            return ResponseEntity.badRequest().build();
        }
        Optional<TileVisibility> visibility = TileVisibility.parse(request.getVisibility());
        if (visibility.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return entityStateService.getByEntityId(entityId)
                .map(entity -> {
                    tileVisibilityService.setVisibility(entityId, tileKey, visibility.get());
                    return ResponseEntity.ok(toResponseWithVisibility(entity));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private EntityStateResponse toResponseWithVisibility(EntityState entity) {
        Map<String, String> visibility = tileVisibilityService
                .visibilityByEntity(List.of(entity.getEntityId()))
                .getOrDefault(entity.getEntityId(), Map.of());
        return responseMapper.toResponse(entity, visibility);
    }
}
```

- [ ] **Step 7: Tests ausführen — müssen grün sein**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityStateControllerTest" -q
```
Erwartet: alle Tests grün (bestehende + 5 neue).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(entities): API-Endpoint fuer Kachel-Sichtbarkeit und tileVisibility in Antworten"
```

---

### Task 5: `SwitchQueryService` — Kachel-Sicht mit Filter und Gruppen-Sortierung (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java` (erweitern)

- [ ] **Step 1: Failing Tests ergänzen**

Der Service bekommt eine neue Abhängigkeit (`EntityTileVisibilityService`) und die Methode `listSwitches(Integer limit, boolean tileView)`. In `SwitchQueryServiceTest`:

Neues Mock-Feld und `setUp()`-Anpassung:

```java
    @Mock
    private EntityTileVisibilityService tileVisibilityService;

    @BeforeEach
    void setUp() {
        EntityStateResponseMapper entityMapper = new EntityStateResponseMapper(new ObjectMapper());
        service = new SwitchQueryService(entityStateRepository, entityUsageService,
                tileVisibilityService, new SwitchResponseMapper(entityMapper), entityMapper);
    }
```

Neue Hilfsmethode neben `device(...)` (Gerät mit steuerbarem Zustand):

```java
    private EntityState deviceWithState(String ref, String name, String state) {
        return EntityState.builder()
                .entityId("switch.kasa_" + ref)
                .domain(EntityDomain.SWITCH)
                .source(EntitySource.KASA)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }
```

Neue Tests am Ende der Klasse:

```java
    @Test
    void kachel_sicht_filtert_never_heraus() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Sichtbar"), device("b", "Versteckt")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());
        when(tileVisibilityService.tileRules(DashboardTiles.SWITCHES))
                .thenReturn(Map.of("switch.kasa_b", TileVisibility.NEVER));

        assertThat(namesOf(service.listSwitches(null, true))).containsExactly("Sichtbar");
    }

    @Test
    void kachel_sicht_filtert_inaktive_when_on_heraus() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(
                        deviceWithState("wm", "Waschmaschine", "off"),
                        device("a", "Stehlampe")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());
        when(tileVisibilityService.tileRules(DashboardTiles.SWITCHES))
                .thenReturn(Map.of("switch.kasa_wm", TileVisibility.WHEN_ON));

        assertThat(namesOf(service.listSwitches(null, true))).containsExactly("Stehlampe");
    }

    @Test
    void kachel_sicht_sortiert_aktive_when_on_vor_gepinnte_vor_rest() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(
                        device("oft", "Oft genutzt"),
                        device("pin", "Gepinnt"),
                        deviceWithState("wm", "Waschmaschine", "on")));
        // "Oft genutzt" hat die meisten Toggles und stuende rein nutzungsbasiert vorn.
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_oft", usage("switch.kasa_oft", 99, LocalDateTime.of(2026, 7, 19, 10, 0))
        ));
        when(tileVisibilityService.tileRules(DashboardTiles.SWITCHES)).thenReturn(Map.of(
                "switch.kasa_wm", TileVisibility.WHEN_ON,
                "switch.kasa_pin", TileVisibility.ALWAYS
        ));

        assertThat(namesOf(service.listSwitches(null, true)))
                .containsExactly("Waschmaschine", "Gepinnt", "Oft genutzt");
    }

    @Test
    void kachel_sicht_sortiert_innerhalb_der_gruppen_nach_nutzung() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("pin1", "Pin selten"), device("pin2", "Pin oft")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of(
                "switch.kasa_pin1", usage("switch.kasa_pin1", 1, LocalDateTime.of(2026, 7, 19, 10, 0)),
                "switch.kasa_pin2", usage("switch.kasa_pin2", 8, LocalDateTime.of(2026, 7, 19, 10, 0))
        ));
        when(tileVisibilityService.tileRules(DashboardTiles.SWITCHES)).thenReturn(Map.of(
                "switch.kasa_pin1", TileVisibility.ALWAYS,
                "switch.kasa_pin2", TileVisibility.ALWAYS
        ));

        assertThat(namesOf(service.listSwitches(null, true)))
                .containsExactly("Pin oft", "Pin selten");
    }

    @Test
    void kachel_sicht_wendet_das_limit_nach_filter_und_sortierung_an() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(
                        device("a", "Eins"), device("b", "Zwei"),
                        deviceWithState("wm", "Waschmaschine", "on")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());
        when(tileVisibilityService.tileRules(DashboardTiles.SWITCHES))
                .thenReturn(Map.of("switch.kasa_wm", TileVisibility.WHEN_ON));

        List<SwitchResponse> result = service.listSwitches(2, true);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).displayName()).isEqualTo("Waschmaschine");
    }

    @Test
    void dialog_sicht_zeigt_alle_und_ignoriert_die_regeln() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(
                        deviceWithState("wm", "Waschmaschine", "off"),
                        device("b", "Versteckt")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(namesOf(service.listSwitches(null, false)))
                .containsExactlyInAnyOrder("Waschmaschine", "Versteckt");
    }
```

Die bestehenden Tests rufen `service.listSwitches(null)` bzw. `listSwitches(2)` auf — diese Ein-Parameter-Überladung bleibt bestehen (delegiert mit `tileView=false`) und die Tests bleiben unverändert.

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=SwitchQueryServiceTest" -q
```
Erwartet: COMPILATION ERROR (neuer Konstruktor-Parameter, `listSwitches(Integer, boolean)` fehlt).

- [ ] **Step 3: `SwitchQueryService` umbauen**

Vollständige neue Fassung:

```java
package com.household.manager.entitystate;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
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
 * Liefert die schaltbaren Entitäten für Schalter-Kachel und -Dialog.
 * <p>
 * Die Dialog-Sicht zeigt alle Schalter nutzungsbasiert sortiert. Die
 * Kachel-Sicht wendet zusätzlich die benutzergepflegten Sichtbarkeitsregeln
 * an: NEVER und inaktive WHEN_ON werden gefiltert, sortiert wird in Gruppen
 * (aktive WHEN_ON, dann ALWAYS, dann Rest) — innerhalb jeder Gruppe nach Nutzung.
 */
@Service
@RequiredArgsConstructor
public class SwitchQueryService {

    private static final String STATE_ON = "on";

    private final EntityStateRepository entityStateRepository;
    private final EntityUsageService entityUsageService;
    private final EntityTileVisibilityService tileVisibilityService;
    private final SwitchResponseMapper switchResponseMapper;
    private final EntityStateResponseMapper entityStateResponseMapper;

    /** Dialog-Sicht ohne Sichtbarkeitsregeln (Kompatibilitäts-Überladung). */
    @Transactional(readOnly = true)
    public List<SwitchResponse> listSwitches(Integer limit) {
        return listSwitches(limit, false);
    }

    /**
     * @param limit    maximale Anzahl Einträge; null oder <= 0 liefert alle
     * @param tileView true wendet die Kachel-Sichtbarkeitsregeln an
     */
    @Transactional(readOnly = true)
    public List<SwitchResponse> listSwitches(Integer limit, boolean tileView) {
        Map<String, TileVisibility> rules = tileView
                ? tileVisibilityService.tileRules(DashboardTiles.SWITCHES)
                : Map.of();

        List<EntityState> switchable = entityStateRepository
                .findByDomainInOrderByEntityIdAsc(SwitchableEntities.SWITCHABLE_DOMAINS).stream()
                .filter(SwitchableEntities::isSwitchable)
                // Haus-Modi haben eine eigene Leiste im Dashboard und die Modus-API.
                .filter(entity -> !HouseModes.isMode(
                        entityStateResponseMapper.parseAttributes(entity.getAttributes())))
                .filter(entity -> !tileView || visibleOnTile(entity, rules))
                .toList();

        Map<String, EntityUsage> usage = entityUsageService.usageFor(
                switchable.stream().map(EntityState::getEntityId).toList());

        record Ranked(SwitchResponse response, int rank) {
        }
        List<SwitchResponse> switches = switchable.stream()
                .map(entity -> new Ranked(
                        switchResponseMapper.toResponse(entity, usage.get(entity.getEntityId())),
                        tileRank(entity, rules)))
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparing(Ranked::response, byUsage()))
                .map(Ranked::response)
                .toList();

        if (limit != null && limit > 0 && limit < switches.size()) {
            return List.copyOf(switches.subList(0, limit));
        }
        return switches;
    }

    /** Kachel-Filter: NEVER nie, WHEN_ON nur solange der Zustand "on" ist. */
    private boolean visibleOnTile(EntityState entity, Map<String, TileVisibility> rules) {
        return switch (rules.getOrDefault(entity.getEntityId(), TileVisibility.AUTO)) {
            case NEVER -> false;
            case WHEN_ON -> STATE_ON.equals(entity.getState());
            case ALWAYS, AUTO -> true;
        };
    }

    /** Gruppen-Rang der Kachel: aktive WHEN_ON (0) vor ALWAYS (1) vor Rest (2). */
    private int tileRank(EntityState entity, Map<String, TileVisibility> rules) {
        return switch (rules.getOrDefault(entity.getEntityId(), TileVisibility.AUTO)) {
            case WHEN_ON -> 0;
            case ALWAYS -> 1;
            case AUTO, NEVER -> 2;
        };
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

Hinweis zur Korrektheit: In der Dialog-Sicht ist `rules` leer, damit ist `tileRank` für alle Einträge 2 und die Sortierung bleibt rein nutzungsbasiert. `WHEN_ON` bekommt Rang 0 nur nach dem Filter — inaktive `WHEN_ON` sind in der Kachel-Sicht bereits herausgefiltert.

- [ ] **Step 4: Tests ausführen — müssen grün sein**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=SwitchQueryServiceTest" -q
```
Erwartet: alle Tests grün (8 bestehende + 6 neue).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(switches): Kachel-Sicht mit Sichtbarkeitsregeln und Gruppen-Sortierung"
```

---

### Task 6: `SwitchController` — `view`-Parameter (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/controller/SwitchController.java`
- Test: `backend/src/test/java/com/household/manager/controller/SwitchControllerTest.java` (anpassen + erweitern)

- [ ] **Step 1: Tests anpassen und ergänzen**

Die bestehenden Mocks `listSwitches(isNull())` und `listSwitches(4)` auf die Zwei-Parameter-Signatur umstellen und einen `view=tile`-Test ergänzen:

```java
    // bestehenden Test "liefert_die_schalterliste" anpassen:
    when(switchQueryService.listSwitches(isNull(), eq(false)))
            .thenReturn(List.of(response("switch.kasa_abc", "Stehlampe", "on")));

    // bestehenden Test "reicht_das_limit_an_den_service_durch" anpassen:
    when(switchQueryService.listSwitches(4, false)).thenReturn(List.of());

    // neuer Test:
    @Test
    void view_tile_aktiviert_die_kachel_sicht() throws Exception {
        when(switchQueryService.listSwitches(4, true)).thenReturn(List.of());

        mockMvc.perform(get("/v1/switches").param("limit", "4").param("view", "tile"))
                .andExpect(status().isOk());
    }
```

Import ergänzen: `import static org.mockito.ArgumentMatchers.eq;`

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=SwitchControllerTest" -q
```
Erwartet: FAIL (Mock passt nicht auf die alte Ein-Parameter-Signatur bzw. Controller kennt `view` nicht — je nach Reihenfolge Compile- oder Assertion-Fehler).

- [ ] **Step 3: Controller anpassen**

`getSwitches` in `SwitchController` ersetzen:

```java
    /**
     * @param limit optionale Obergrenze; ohne Angabe werden alle Schalter geliefert
     * @param view  "tile" wendet die Kachel-Sichtbarkeitsregeln an; Standard "all"
     */
    @GetMapping
    public List<SwitchResponse> getSwitches(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "all") String view) {
        return switchQueryService.listSwitches(limit, "tile".equals(view));
    }
```

- [ ] **Step 4: Tests ausführen — müssen grün sein**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=SwitchControllerTest" -q
```
Erwartet: alle Tests grün.

- [ ] **Step 5: Gesamtes Backend-Testpaket der berührten Schicht laufen lassen**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=SwitchQueryServiceTest,SwitchControllerTest,EntityStateControllerTest,EntityTileVisibilityServiceTest,SwitchCommandServiceTest" -q
```
Erwartet: grün.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(switches): view-Parameter fuer Kachel- vs. Dialog-Sicht"
```

---

### Task 7: Frontend — Modell und Services

**Files:**
- Modify: `frontend/src/app/models/entity-state.model.ts`
- Modify: `frontend/src/app/services/entity-state.service.ts`
- Modify: `frontend/src/app/services/switch.service.ts`

- [ ] **Step 1: Modell erweitern**

In `entity-state.model.ts` den Typ ergänzen und das Interface erweitern:

```ts
/** Sichtbarkeitsregel einer Entität auf einer Dashboard-Kachel. */
export type TileVisibility = 'ALWAYS' | 'AUTO' | 'WHEN_ON' | 'NEVER';

/** Schlüssel der Schalter-Kachel des Dashboards. */
export const SWITCH_TILE_KEY = 'switches';
```

Im Interface `EntityState` nach `attributes` ergänzen:

```ts
  /** Explizite Kachel-Regeln (tileKey → Regel); fehlender Eintrag = AUTO. */
  tileVisibility?: Record<string, TileVisibility>;
```

- [ ] **Step 2: `EntityStateService.setTileVisibility` ergänzen**

Nach `setCustomName` einfügen (Import von `TileVisibility` oben ergänzen):

```ts
  /** Setzt die Kachel-Sichtbarkeit einer Entität; 'AUTO' entfernt die Regel. */
  setTileVisibility(entityId: string, tileKey: string, visibility: TileVisibility): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.baseUrl}/${entityId}/tiles/${tileKey}`, { visibility }).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 3: `SwitchService.getSwitches` um `view` erweitern**

Signatur und Params anpassen:

```ts
  /**
   * Schaltbare Entitäten, meistgenutzte zuerst.
   * @param view 'tile' wendet die Kachel-Sichtbarkeitsregeln an; Standard alle
   */
  getSwitches(limit?: number, view?: 'tile' | 'all'): Observable<SwitchEntity[]> {
    let params = new HttpParams();
    if (limit != null) {
      params = params.set('limit', limit);
    }
    if (view) {
      params = params.set('view', view);
    }
    return this.http.get<SwitchEntity[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 4: Frontend bauen (Typprüfung)**

Aus `frontend/`:
```powershell
npx ng build --configuration production
```
Erwartet: Build erfolgreich.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app
git commit -m "feat(frontend): TileVisibility-Modell und Service-Methoden"
```

---

### Task 8: Entitäten-Seite — Dropdown „Schalter-Kachel" (TDD)

**Files:**
- Modify: `frontend/src/app/pages/entities/entities.component.ts`
- Modify: `frontend/src/app/pages/entities/entities.component.html`
- Test (Create): `frontend/src/app/pages/entities/entities.component.spec.ts`

- [ ] **Step 1: Spec-Datei anlegen (failing)**

```ts
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { EntitiesComponent } from './entities.component';
import { EntityStateService } from '../../services/entity-state.service';
import { EntityState } from '../../models/entity-state.model';

describe('EntitiesComponent', () => {
  let service: jasmine.SpyObj<EntityStateService>;

  const entity = (overrides: Partial<EntityState>): EntityState => ({
    entityId: 'switch.kasa_wm',
    domain: 'SWITCH',
    source: 'KASA',
    sourceRef: 'wm',
    friendlyName: 'Waschmaschine',
    displayName: 'Waschmaschine',
    state: 'off',
    attributes: {},
    lastChanged: '2026-07-20T10:00:00',
    lastUpdated: '2026-07-20T10:00:00',
    ...overrides
  });

  beforeEach(async () => {
    service = jasmine.createSpyObj('EntityStateService', ['getEntities', 'setTileVisibility']);
    service.getEntities.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [EntitiesComponent],
      providers: [{ provide: EntityStateService, useValue: service }]
    }).compileComponents();
  });

  const createComponent = (): EntitiesComponent =>
    TestBed.createComponent(EntitiesComponent).componentInstance;

  it('bietet die Kachel-Einstellung nur fuer schaltbare Entitaeten an', () => {
    const component = createComponent();

    expect(component.isSwitchTileConfigurable(entity({ domain: 'SWITCH' }))).toBeTrue();
    expect(component.isSwitchTileConfigurable(entity({ domain: 'INPUT_BOOLEAN' }))).toBeTrue();
    expect(component.isSwitchTileConfigurable(entity({ domain: 'SENSOR' }))).toBeFalse();
    // Haus-Modi haben eine eigene Leiste und keine Kachel-Einstellung.
    expect(component.isSwitchTileConfigurable(
      entity({ domain: 'INPUT_BOOLEAN', attributes: { mode: true } }))).toBeFalse();
  });

  it('liefert AUTO als Standard-Sichtbarkeit', () => {
    const component = createComponent();

    expect(component.tileVisibilityOf(entity({}))).toBe('AUTO');
    expect(component.tileVisibilityOf(entity({ tileVisibility: { switches: 'WHEN_ON' } })))
      .toBe('WHEN_ON');
  });

  it('speichert die Sichtbarkeit und uebernimmt die aktualisierte Entitaet', () => {
    const updated = entity({ tileVisibility: { switches: 'WHEN_ON' } });
    service.setTileVisibility.and.returnValue(of(updated));
    const component = createComponent();
    component.entities.set([entity({})]);

    component.setTileVisibility(entity({}), 'WHEN_ON');

    expect(service.setTileVisibility).toHaveBeenCalledWith('switch.kasa_wm', 'switches', 'WHEN_ON');
    expect(component.entities()[0].tileVisibility?.['switches']).toBe('WHEN_ON');
  });
});
```

- [ ] **Step 2: Spec ausführen — muss fehlschlagen**

Aus `frontend/`:
```powershell
npx ng test --watch=false --include='**/entities.component.spec.ts'
```
Erwartet: FAIL (`isSwitchTileConfigurable` etc. existieren nicht).

- [ ] **Step 3: Component-Methoden implementieren**

In `entities.component.ts` — Imports erweitern:

```ts
import { EntityState, EntityDomain, TileVisibility, SWITCH_TILE_KEY } from '../../models/entity-state.model';
```

Neue Konstante und Methoden in der Klasse (z. B. nach `saveCustomName`):

```ts
  /** Auswahloptionen der Kachel-Sichtbarkeit fuer das Template. */
  readonly tileVisibilityOptions: { value: TileVisibility; label: string }[] = [
    { value: 'AUTO', label: 'Automatisch' },
    { value: 'ALWAYS', label: 'Immer' },
    { value: 'WHEN_ON', label: 'Nur wenn an' },
    { value: 'NEVER', label: 'Nie' }
  ];

  /**
   * Nur schaltbare Entitaeten (Schalter-Kachel-Kandidaten) bekommen die
   * Einstellung; Haus-Modi haben eine eigene Leiste im Dashboard.
   */
  isSwitchTileConfigurable(entity: EntityState): boolean {
    const switchable = entity.domain === 'SWITCH' || entity.domain === 'INPUT_BOOLEAN';
    return switchable && !entity.attributes?.['mode'];
  }

  /** Aktuelle Regel fuer die Schalter-Kachel; fehlender Eintrag = AUTO. */
  tileVisibilityOf(entity: EntityState): TileVisibility {
    return entity.tileVisibility?.[SWITCH_TILE_KEY] ?? 'AUTO';
  }

  setTileVisibility(entity: EntityState, visibility: TileVisibility): void {
    this.entityStateService.setTileVisibility(entity.entityId, SWITCH_TILE_KEY, visibility)
      .subscribe({
        next: updated => this.entities.update(list =>
          list.map(e => e.entityId === updated.entityId ? updated : e)),
        error: err => this.error.set(err.message)
      });
  }
```

- [ ] **Step 4: Template erweitern**

In `entities.component.html` innerhalb des aufgeklappten Detail-Blocks (`entities-table__details`), vor der `<dl class="entities-table__attributes">`:

```html
              @if (isSwitchTileConfigurable(entity)) {
                <div class="entities-table__tile-visibility">
                  <label>Schalter-Kachel:</label>
                  <select
                    [ngModel]="tileVisibilityOf(entity)"
                    (ngModelChange)="setTileVisibility(entity, $event)"
                    (click)="$event.stopPropagation()">
                    @for (option of tileVisibilityOptions; track option.value) {
                      <option [value]="option.value">{{ option.label }}</option>
                    }
                  </select>
                </div>
              }
```

Optionales Styling in `entities.component.scss` (an bestehende Klassen anlehnen):

```scss
.entities-table__tile-visibility {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;

  select {
    padding: 0.25rem 0.5rem;
  }
}
```

- [ ] **Step 5: Spec ausführen — muss grün sein**

```powershell
npx ng test --watch=false --include='**/entities.component.spec.ts'
```
Erwartet: 3 Specs, 0 Failures.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/entities
git commit -m "feat(entities-ui): Dropdown fuer Kachel-Sichtbarkeit auf der Entitaeten-Seite"
```

---

### Task 9: Dashboard — Kachel ruft `view=tile`

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` (Methode `topSwitchRequest`, ca. Zeile 546)

- [ ] **Step 1: Kachel-Abfrage umstellen**

In `dashboard.component.ts` die Methode `topSwitchRequest` ändern — die Kachel nutzt die Kachel-Sicht, der Dialog (`openSwitchDialog`) bleibt unverändert bei der vollen Liste:

```ts
  private topSwitchRequest() {
    return this.switchService.getSwitches(DashboardComponent.SWITCH_TILE_LIMIT, 'tile').pipe(
      catchError(() => of<SwitchEntity[]>([]))
    );
  }
```

- [ ] **Step 2: Bestehende Dashboard-Specs ausführen**

Aus `frontend/`:
```powershell
npx ng test --watch=false --include='**/dashboard.component.spec.ts'
```
Erwartet: grün. Falls ein Spy auf `getSwitches` mit exakten Argumenten prüft, den erwarteten Aufruf auf `(4, 'tile')` anpassen.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(dashboard): Schalter-Kachel nutzt die Kachel-Sicht (view=tile)"
```

---

### Task 10: Abschluss — Gesamtverifikation

- [ ] **Step 1: Alle berührten Backend-Tests**

Aus `backend/`:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityTileVisibilityServiceTest,EntityStateControllerTest,SwitchQueryServiceTest,SwitchControllerTest,SwitchCommandServiceTest,EntityStateServiceTest,HouseModeQueryServiceTest" -q
```
Erwartet: grün.

- [ ] **Step 2: Kompletter Backend-Testlauf (bekannte DB-Fehler ignorieren)**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test
```
Erwartet: nur die bekannten, umgebungsbedingten Fehler `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` (keine Test-DB auf dieser Maschine). Alles andere grün.

- [ ] **Step 3: Kompletter Frontend-Testlauf + Produktions-Build**

Aus `frontend/`:
```powershell
npx ng test --watch=false
npx ng build --configuration production
```
Erwartet: Tests grün, Build erfolgreich.

- [ ] **Step 4: CLAUDE.md aktualisieren**

Im Abschnitt „Database Schema → Current Entities" von `CLAUDE.md` ergänzen:

```markdown
- **Entity Tile Visibility**: Per-entity visibility rules for dashboard tiles
  - Tile key (currently `switches`), visibility (ALWAYS / WHEN_ON / NEVER; no row = AUTO)
  - Controls which switches appear on the dashboard switch tile and in which order
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Kachel-Sichtbarkeit in CLAUDE.md dokumentieren"
```
