# Editierbarer Kurzname für Entitäten – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jede Entität bekommt einen optionalen, frei editierbaren Kurznamen (`customName`), der die integrations-gelieferten Namen in der GUI ersetzt, ohne beim Polling überschrieben zu werden.

**Architecture:** Neue nullable Spalte `custom_name` auf `entity_states`, die der fehlertolerante Upsert-Pfad nie anfasst. Ein separater, benutzerinitiierter `@Transactional`-Schreibpfad (`setCustomName`) setzt/löscht den Wert. Die API liefert zusätzlich einen berechneten `displayName = customName ?? friendlyName`, den alle Anzeigestellen verwenden.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase (Backend), Angular 19 Standalone / Signals / SCSS (Frontend). Tests: JUnit + Mockito + MockMvc (Backend), Jasmine/Karma (Frontend).

**Voraussetzung (Backend-Build):** `JAVA_HOME` muss auf das JDK 21 zeigen (`jdk-21.0.10`), sonst schlägt der Maven-Build fehl (Default ist JDK 17).

---

## Dateiübersicht

**Backend – erstellen:**
- `backend/src/main/resources/db/changelog/changes/20260715-0032-add-custom-name-to-entity-states.xml` – Migration
- `backend/src/main/java/com/household/manager/dto/UpdateEntityCustomNameRequest.java` – Request-DTO

**Backend – ändern:**
- `backend/src/main/resources/db/changelog/db.changelog-master.xml` – `<include>` für neue Migration
- `backend/src/main/java/com/household/manager/model/entity/EntityState.java` – Feld `customName`
- `backend/src/main/java/com/household/manager/dto/EntityStateResponse.java` – Felder `customName`, `displayName`
- `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java` – Mapping + Fallback
- `backend/src/main/java/com/household/manager/entitystate/EntityStateService.java` – `setCustomName`
- `backend/src/main/java/com/household/manager/controller/EntityStateController.java` – Endpoint `PUT /{entityId}/custom-name`

**Backend – Tests ändern:**
- `backend/src/test/java/com/household/manager/entitystate/EntityStateServiceTest.java` – `setCustomName`
- `backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java` – Endpoint + `displayName`
- `backend/src/test/java/com/household/manager/entitystate/EntityStateWriterTest.java` – Regressionsschutz

**Frontend – ändern:**
- `frontend/src/app/models/entity-state.model.ts` – `customName`, `displayName`
- `frontend/src/app/services/entity-state.service.ts` – `setCustomName`
- `frontend/src/app/services/entity-state.service.spec.ts` – Test für `setCustomName`
- `frontend/src/app/pages/entities/entities.component.ts` – Inline-Edit-Logik, Suche
- `frontend/src/app/pages/entities/entities.component.html` – Name-Spalte + Edit-UI
- `frontend/src/app/pages/entities/entities.component.scss` – Edit-Styles
- `frontend/src/app/pages/flows/pickers/entity-picker.component.ts` – `displayName`
- `frontend/src/app/pages/flows/pickers/entity-picker.component.html` – `displayName`
- `frontend/src/app/pages/flows/pickers/entity-picker.component.spec.ts` – Testdaten auf `displayName`

---

## Task 1: DB-Migration + Entity-Feld `customName`

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260715-0032-add-custom-name-to-entity-states.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `backend/src/main/java/com/household/manager/model/entity/EntityState.java`

- [ ] **Step 1: Migrations-Changeset anlegen**

Datei `backend/src/main/resources/db/changelog/changes/20260715-0032-add-custom-name-to-entity-states.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260715-0032-add-custom-name-to-entity-states" author="household-manager">
        <preConditions onFail="MARK_RAN">
            <not>
                <columnExists tableName="entity_states" columnName="custom_name"/>
            </not>
        </preConditions>
        <addColumn tableName="entity_states">
            <column name="custom_name" type="VARCHAR(255)"/>
        </addColumn>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Changeset in den Master aufnehmen**

In `backend/src/main/resources/db/changelog/db.changelog-master.xml` vor dem schließenden `</databaseChangeLog>` ergänzen:

```xml
    <!-- Editierbarer Kurzname für Entitäten -->
    <include file="db/changelog/changes/20260715-0032-add-custom-name-to-entity-states.xml"/>
```

- [ ] **Step 3: Feld auf der Entity ergänzen**

In `backend/src/main/java/com/household/manager/model/entity/EntityState.java` nach dem `friendlyName`-Feld (nach Zeile 37) einfügen:

```java

    /** Optionaler, vom Benutzer gesetzter Kurzname. Wird vom Polling-Upsert nie überschrieben. */
    @Column(name = "custom_name", length = 255)
    private String customName;
```

- [ ] **Step 4: Kompilieren**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS (keine Compile-Fehler).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog/changes/20260715-0032-add-custom-name-to-entity-states.xml \
        backend/src/main/resources/db/changelog/db.changelog-master.xml \
        backend/src/main/java/com/household/manager/model/entity/EntityState.java
git commit -m "feat(entities): custom_name-Spalte und Entity-Feld"
```

---

## Task 2: Response-DTO + Mapper (`customName`, `displayName`)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/dto/EntityStateResponse.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java`
- Test: `backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java`

- [ ] **Step 1: Fehlerschlagenden Test schreiben**

In `EntityStateControllerTest.java` zwei Tests ergänzen (der bestehende `sensor()`-Builder liefert `customName == null`). Import ergänzen falls nötig: `EntityState` ist bereits importiert.

```java
    @Test
    void displayNameFallsBackToFriendlyNameWhenNoCustomName() throws Exception {
        when(entityStateService.find(null, null)).thenReturn(List.of(sensor()));

        mockMvc.perform(get("/v1/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customName").doesNotExist())
                .andExpect(jsonPath("$[0].displayName").value("Wohnzimmer Temperatur"));
    }

    @Test
    void displayNameUsesCustomNameWhenSet() throws Exception {
        EntityState withCustom = sensor();
        withCustom.setCustomName("Wohnzimmer");
        when(entityStateService.find(null, null)).thenReturn(List.of(withCustom));

        mockMvc.perform(get("/v1/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customName").value("Wohnzimmer"))
                .andExpect(jsonPath("$[0].displayName").value("Wohnzimmer"));
    }
```

- [ ] **Step 2: Test ausführen – muss fehlschlagen**

Run: `cd backend && mvn -q test -Dtest=EntityStateControllerTest`
Expected: FAIL – `displayName`/`customName` existieren noch nicht in der JSON-Antwort.

- [ ] **Step 3: Response-Record erweitern**

`backend/src/main/java/com/household/manager/dto/EntityStateResponse.java` – Felder ergänzen:

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
        LocalDateTime lastChanged,
        LocalDateTime lastUpdated
) {
}
```

- [ ] **Step 4: Mapper erweitern**

In `EntityStateResponseMapper.java` die `toResponse`-Methode ergänzen und eine private Hilfsmethode hinzufügen:

```java
    public EntityStateResponse toResponse(EntityState entity) {
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
                .lastChanged(entity.getLastChanged())
                .lastUpdated(entity.getLastUpdated())
                .build();
    }

    /** Effektiver Anzeigename: Kurzname, falls gesetzt, sonst der Integrationsname. */
    private String displayName(EntityState entity) {
        String custom = entity.getCustomName();
        return custom != null && !custom.isBlank() ? custom : entity.getFriendlyName();
    }
```

- [ ] **Step 5: Test ausführen – muss bestehen**

Run: `cd backend && mvn -q test -Dtest=EntityStateControllerTest`
Expected: PASS (alle Tests grün).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/EntityStateResponse.java \
        backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java \
        backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java
git commit -m "feat(entities): customName und displayName in API-Response"
```

---

## Task 3: `EntityStateService.setCustomName`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntityStateService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityStateServiceTest.java`

- [ ] **Step 1: Fehlerschlagende Tests schreiben**

In `EntityStateServiceTest.java` ein Repository-Mock-Feld und drei Tests ergänzen. Neue Imports oben ergänzen:

```java
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
```

Mock-Feld zur Klasse hinzufügen:

```java
    @Mock
    private EntityStateRepository repository;
```

Tests (bauen bewusst eine eigene Service-Instanz mit gemocktem Repository, da das `setUp()` `repository == null` übergibt):

```java
    @Test
    void setCustomNameTrimsAndPersists() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        EntityState entity = EntityState.builder().entityId("sensor.x").friendlyName("Langer Name").build();
        when(repository.findByEntityId("sensor.x")).thenReturn(Optional.of(entity));
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<EntityState> result = svc.setCustomName("sensor.x", "  Kurz  ");

        assertTrue(result.isPresent());
        assertEquals("Kurz", result.get().getCustomName());
    }

    @Test
    void setCustomNameClearsWhenBlank() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        EntityState entity = EntityState.builder()
                .entityId("sensor.x").friendlyName("Langer Name").customName("Alt").build();
        when(repository.findByEntityId("sensor.x")).thenReturn(Optional.of(entity));
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<EntityState> result = svc.setCustomName("sensor.x", "   ");

        assertTrue(result.isPresent());
        assertNull(result.get().getCustomName());
    }

    @Test
    void setCustomNameReturnsEmptyForUnknownEntity() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        when(repository.findByEntityId("sensor.unknown")).thenReturn(Optional.empty());

        Optional<EntityState> result = svc.setCustomName("sensor.unknown", "Kurz");

        assertFalse(result.isPresent());
    }
```

- [ ] **Step 2: Tests ausführen – müssen fehlschlagen**

Run: `cd backend && mvn -q test -Dtest=EntityStateServiceTest`
Expected: FAIL – Methode `setCustomName` existiert nicht (Compile-Fehler).

- [ ] **Step 3: Methode implementieren**

In `EntityStateService.java` (nach `deleteByEntityId`, vor der schließenden Klammer) ergänzen:

```java

    /**
     * Setzt oder löscht den benutzerdefinierten Kurznamen einer Entität.
     * Leerer/nur-Whitespace-Wert löscht den Kurznamen (Fallback auf friendlyName).
     * Bewusst ein direkter, benutzerinitiierter Schreibpfad – nicht der
     * fehlertolerante reportState/upsert-Pfad der Integrationen.
     *
     * @return aktualisierte Entität, oder leer wenn die entityId unbekannt ist
     */
    @Transactional
    public Optional<EntityState> setCustomName(String entityId, String customName) {
        return repository.findByEntityId(entityId).map(entity -> {
            entity.setCustomName(normalizeCustomName(customName));
            return repository.save(entity);
        });
    }

    private String normalizeCustomName(String customName) {
        if (customName == null) {
            return null;
        }
        String trimmed = customName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
```

- [ ] **Step 4: Tests ausführen – müssen bestehen**

Run: `cd backend && mvn -q test -Dtest=EntityStateServiceTest`
Expected: PASS (alle Tests grün).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntityStateService.java \
        backend/src/test/java/com/household/manager/entitystate/EntityStateServiceTest.java
git commit -m "feat(entities): EntityStateService.setCustomName"
```

---

## Task 4: Request-DTO + Controller-Endpoint

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/UpdateEntityCustomNameRequest.java`
- Modify: `backend/src/main/java/com/household/manager/controller/EntityStateController.java`
- Test: `backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java`

- [ ] **Step 1: Fehlerschlagende Tests schreiben**

In `EntityStateControllerTest.java` neue Imports und Tests ergänzen.

Imports oben:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import org.springframework.http.MediaType;
```

Tests:

```java
    @Test
    void setsCustomName() throws Exception {
        EntityState updated = sensor();
        updated.setCustomName("Wohnzimmer");
        when(entityStateService.setCustomName("sensor.zigbee_wohnzimmer_temperature", "Wohnzimmer"))
                .thenReturn(Optional.of(updated));

        mockMvc.perform(put("/v1/entities/sensor.zigbee_wohnzimmer_temperature/custom-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customName\":\"Wohnzimmer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customName").value("Wohnzimmer"))
                .andExpect(jsonPath("$.displayName").value("Wohnzimmer"));
    }

    @Test
    void setCustomNameReturns404ForUnknownEntity() throws Exception {
        when(entityStateService.setCustomName("sensor.unknown", "X")).thenReturn(Optional.empty());

        mockMvc.perform(put("/v1/entities/sensor.unknown/custom-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customName\":\"X\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void setCustomNameAcceptsNullToClear() throws Exception {
        EntityState cleared = sensor();
        when(entityStateService.setCustomName("sensor.zigbee_wohnzimmer_temperature", null))
                .thenReturn(Optional.of(cleared));

        mockMvc.perform(put("/v1/entities/sensor.zigbee_wohnzimmer_temperature/custom-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customName\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Wohnzimmer Temperatur"));
    }
```

- [ ] **Step 2: Tests ausführen – müssen fehlschlagen**

Run: `cd backend && mvn -q test -Dtest=EntityStateControllerTest`
Expected: FAIL – Endpoint `/custom-name` liefert 404/Fehler bzw. `setCustomName` existiert nicht am Mock-Interface.

- [ ] **Step 3: Request-DTO anlegen**

Datei `backend/src/main/java/com/household/manager/dto/UpdateEntityCustomNameRequest.java`:

```java
package com.household.manager.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Setzen oder Löschen des Kurznamens einer Entität.
 * Ein leerer/null Wert löscht den Kurznamen (Fallback auf friendlyName).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEntityCustomNameRequest {

    @Size(max = 255, message = "Custom name must not exceed 255 characters")
    private String customName;
}
```

- [ ] **Step 4: Endpoint im Controller ergänzen**

In `EntityStateController.java` Imports ergänzen:

```java
import com.household.manager.dto.UpdateEntityCustomNameRequest;
import jakarta.validation.Valid;
```

Methode vor der schließenden Klammer (nach `deleteEntity`) einfügen:

```java

    @PutMapping("/{entityId}/custom-name")
    public ResponseEntity<EntityStateResponse> setCustomName(
            @PathVariable String entityId,
            @Valid @RequestBody UpdateEntityCustomNameRequest request) {
        return entityStateService.setCustomName(entityId, request.getCustomName())
                .map(entity -> ResponseEntity.ok(responseMapper.toResponse(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 5: Tests ausführen – müssen bestehen**

Run: `cd backend && mvn -q test -Dtest=EntityStateControllerTest`
Expected: PASS (alle Tests grün).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/UpdateEntityCustomNameRequest.java \
        backend/src/main/java/com/household/manager/controller/EntityStateController.java \
        backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java
git commit -m "feat(entities): PUT /v1/entities/{entityId}/custom-name"
```

---

## Task 5: Regressionsschutz – Kurzname überlebt Upsert

**Files:**
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityStateWriterTest.java`

Hintergrund: `EntityStateWriter.upsert` referenziert `customName` bewusst nirgends – bei bestehenden Entitäten wird nur `friendlyName`, `attributes`, `state`, `lastUpdated`/`lastChanged` gesetzt. Dieser Test sichert diese zentrale Eigenschaft dauerhaft ab. Er muss **sofort grün** sein (keine Produktionsänderung nötig); schlägt er fehl, verletzt eine Änderung die Kern-Invariante.

- [ ] **Step 1: Regressionstest schreiben**

In `EntityStateWriterTest.java` ergänzen (nutzt vorhandene `update(...)`-Hilfe und `repository`-Mock aus `setUp()`):

```java
    @Test
    void upsertPreservesUserSetCustomName() {
        EntityState existing = EntityState.builder()
                .entityId("sensor.zigbee_wohnzimmer_temperature")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.ZIGBEE)
                .sourceRef("Wohnzimmer")
                .friendlyName("Wohnzimmer Temperatur")
                .customName("Küche")
                .state("21.5")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(repository.findByEntityId("sensor.zigbee_wohnzimmer_temperature"))
                .thenReturn(Optional.of(existing));

        writer.upsert(update("22.0"));

        ArgumentCaptor<EntityState> captor = ArgumentCaptor.forClass(EntityState.class);
        verify(repository).save(captor.capture());
        assertEquals("Küche", captor.getValue().getCustomName());
    }
```

- [ ] **Step 2: Test ausführen – muss bestehen**

Run: `cd backend && mvn -q test -Dtest=EntityStateWriterTest`
Expected: PASS – der Upsert lässt `customName` unangetastet.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/household/manager/entitystate/EntityStateWriterTest.java
git commit -m "test(entities): Kurzname ueberlebt Polling-Upsert"
```

---

## Task 6: Frontend – Model, Service, Service-Spec

**Files:**
- Modify: `frontend/src/app/models/entity-state.model.ts`
- Modify: `frontend/src/app/services/entity-state.service.ts`
- Test: `frontend/src/app/services/entity-state.service.spec.ts`

- [ ] **Step 1: Fehlerschlagenden Service-Test schreiben**

In `entity-state.service.spec.ts` vor dem schließenden `});` des `describe` ergänzen:

```typescript
  it('sets a custom name for an entity', () => {
    service.setCustomName('sensor.zigbee_bad_temperature', 'Bad').subscribe();

    const req = httpMock.expectOne('/api/v1/entities/sensor.zigbee_bad_temperature/custom-name');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ customName: 'Bad' });
    req.flush({});
  });

  it('clears a custom name by sending null', () => {
    service.setCustomName('sensor.zigbee_bad_temperature', null).subscribe();

    const req = httpMock.expectOne('/api/v1/entities/sensor.zigbee_bad_temperature/custom-name');
    expect(req.request.body).toEqual({ customName: null });
    req.flush({});
  });
```

- [ ] **Step 2: Test ausführen – muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL – `service.setCustomName` existiert nicht (Compile-Fehler in der Spec).

- [ ] **Step 3: Model erweitern**

In `frontend/src/app/models/entity-state.model.ts` das Interface `EntityState` erweitern:

```typescript
export interface EntityState {
  entityId: string;
  domain: EntityDomain;
  source: string;
  sourceRef: string;
  friendlyName: string;
  customName?: string | null;
  displayName: string;
  state: string;
  attributes: Record<string, unknown>;
  lastChanged: string;
  lastUpdated: string;
}
```

- [ ] **Step 4: Service-Methode ergänzen**

In `frontend/src/app/services/entity-state.service.ts` nach `deleteEntity` einfügen:

```typescript
  /** Setzt oder löscht (null) den Kurznamen einer Entität. Gilt für alle Quellen. */
  setCustomName(entityId: string, customName: string | null): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.baseUrl}/${entityId}/custom-name`, { customName }).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 5: Test ausführen – muss bestehen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS (Service-Tests grün).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/entity-state.model.ts \
        frontend/src/app/services/entity-state.service.ts \
        frontend/src/app/services/entity-state.service.spec.ts
git commit -m "feat(entities): Frontend-Model und setCustomName-Service"
```

---

## Task 7: Frontend – Entitäten-Seite (Anzeige + Inline-Edit)

**Files:**
- Modify: `frontend/src/app/pages/entities/entities.component.ts`
- Modify: `frontend/src/app/pages/entities/entities.component.html`
- Modify: `frontend/src/app/pages/entities/entities.component.scss`

Hinweis: Für diese Seite existiert kein Karma-Spec; Verifikation erfolgt über Build und manuelle Ansicht. Es wird bewusst kein neuer Spec angelegt (bestehendes Muster der Seite).

- [ ] **Step 1: Komponenten-Logik ergänzen**

In `entities.component.ts` neue Signals und Methoden hinzufügen. Innerhalb der Klasse nach `expandedEntityId` einfügen:

```typescript
  readonly editingEntityId = signal<string | null>(null);
  readonly editName = signal<string>('');
```

Suche in `filteredEntities` um `displayName` erweitern (den `search`-Block ersetzen):

```typescript
      (!search ||
        e.displayName.toLowerCase().includes(search) ||
        e.friendlyName.toLowerCase().includes(search) ||
        e.entityId.toLowerCase().includes(search))
```

Methoden nach `toggleExpanded` einfügen:

```typescript
  startEditName(entity: EntityState, event: Event): void {
    event.stopPropagation();
    this.editingEntityId.set(entity.entityId);
    this.editName.set(entity.customName ?? '');
  }

  cancelEditName(event: Event): void {
    event.stopPropagation();
    this.editingEntityId.set(null);
  }

  saveCustomName(entity: EntityState, event: Event): void {
    event.stopPropagation();
    const value = this.editName().trim();
    this.entityStateService.setCustomName(entity.entityId, value === '' ? null : value)
      .subscribe({
        next: updated => {
          this.entities.update(list =>
            list.map(e => e.entityId === updated.entityId ? updated : e));
          this.editingEntityId.set(null);
        },
        error: err => this.error.set(err.message)
      });
  }
```

- [ ] **Step 2: Template – Name-Spalte ersetzen**

In `entities.component.html` die Name-Zelle (aktuell `<td>{{ entity.friendlyName }}</td>`, Zeile 51) ersetzen durch:

```html
          <td class="entities-table__name-cell" (click)="$event.stopPropagation()">
            @if (editingEntityId() === entity.entityId) {
              <div class="entities-table__edit">
                <input
                  type="text"
                  [ngModel]="editName()"
                  (ngModelChange)="editName.set($event)"
                  (keyup.enter)="saveCustomName(entity, $event)"
                  placeholder="Kurzname (leer = Standard)" />
                <button type="button" (click)="saveCustomName(entity, $event)" title="Speichern">✓</button>
                <button type="button" (click)="cancelEditName($event)" title="Abbrechen">✕</button>
              </div>
            } @else {
              <span class="entities-table__name">{{ entity.displayName }}</span>
              <button
                type="button"
                class="entities-table__edit-btn"
                (click)="startEditName(entity, $event)"
                title="Kurznamen bearbeiten">✎</button>
            }
          </td>
```

- [ ] **Step 3: SCSS ergänzen**

Ans Ende von `entities.component.scss` anfügen:

```scss
.entities-table__name-cell {
  .entities-table__edit-btn {
    margin-left: 0.4rem;
    padding: 0 0.3rem;
    border: none;
    background: transparent;
    cursor: pointer;
    opacity: 0.5;
    font-size: 0.85rem;

    &:hover {
      opacity: 1;
    }
  }

  .entities-table__edit {
    display: flex;
    gap: 0.25rem;
    align-items: center;

    input {
      flex: 1;
      min-width: 8rem;
      padding: 0.2rem 0.4rem;
    }

    button {
      padding: 0.2rem 0.45rem;
      cursor: pointer;
    }
  }
}
```

- [ ] **Step 4: Build/Lint prüfen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS (Kompilierung erfolgreich, bestehende Tests grün).

- [ ] **Step 5: Manuelle Verifikation**

Backend starten (`cd backend && mvn spring-boot:run`), Frontend (`cd frontend && npm start`), Seite „Entitäten" öffnen:
- Name-Spalte zeigt `displayName`.
- ✎ öffnet Eingabefeld; Speichern setzt Kurznamen (Zeile zeigt sofort neuen Namen).
- Leeres Feld + Speichern setzt zurück auf den Integrationsnamen.
- Klick auf ✎/Eingabefeld klappt die Detailzeile **nicht** auf.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/entities/entities.component.ts \
        frontend/src/app/pages/entities/entities.component.html \
        frontend/src/app/pages/entities/entities.component.scss
git commit -m "feat(entities): Kurzname anzeigen und inline bearbeiten"
```

---

## Task 8: Frontend – Flow-Entity-Picker auf `displayName`

**Files:**
- Modify: `frontend/src/app/pages/flows/pickers/entity-picker.component.ts`
- Modify: `frontend/src/app/pages/flows/pickers/entity-picker.component.html`
- Modify: `frontend/src/app/pages/flows/pickers/entity-picker.component.spec.ts`

- [ ] **Step 1: Spec-Testdaten auf `displayName` umstellen (fehlerschlagend)**

In `entity-picker.component.spec.ts` die drei Stellen mit `friendlyName` auf `displayName` ändern.

Erste Testdaten (Zeilen 11–14):

```typescript
    entityService.getEntities.and.returnValue(of([
      { entityId: 'sensor.a', displayName: 'Sensor A', state: '5' },
      { entityId: 'switch.b', displayName: 'Schalter B', state: 'on' }
    ] as any));
```

Zweite Testdaten (im async-Test, aktuell Zeilen 78–81):

```typescript
    entities$.next([
      { entityId: 'sensor.a', displayName: 'Sensor A', state: '5' },
      { entityId: 'switch.b', displayName: 'Schalter B', state: 'on' }
    ]);
```

Die Assertion `expect(fixture.componentInstance.displayLabel()).toBe('Schalter B (on)');` bleibt unverändert.

- [ ] **Step 2: Test ausführen – muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL – `displayLabel()` liest noch `friendlyName` (jetzt `undefined`), liefert `undefined (on)` statt `Schalter B (on)`.

- [ ] **Step 3: `displayLabel` auf `displayName` umstellen**

In `entity-picker.component.ts` Zeile 26 ersetzen:

```typescript
    return found ? `${found.displayName} (${found.state})` : `nicht gefunden: ${v}`;
```

- [ ] **Step 4: Dropdown-Option auf `displayName` umstellen**

In `entity-picker.component.html` die Option-Zeile ersetzen:

```html
      <option [value]="opt.entityId">{{ opt.displayName }} ({{ opt.state }})</option>
```

- [ ] **Step 5: Test ausführen – muss bestehen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS (alle Picker-Tests grün).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/flows/pickers/entity-picker.component.ts \
        frontend/src/app/pages/flows/pickers/entity-picker.component.html \
        frontend/src/app/pages/flows/pickers/entity-picker.component.spec.ts
git commit -m "feat(flows): Entity-Picker zeigt displayName"
```

---

## Abschluss-Verifikation

- [ ] **Backend gesamt:** `cd backend && mvn -q test` → alle Tests grün (JDK 21 vorausgesetzt; lokale DB-Integrationstests scheitern ggf. by design und sind nicht Teil dieses Features).
- [ ] **Frontend gesamt:** `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless` → alle Tests grün.
- [ ] **End-to-End:** App starten, Kurznamen einer Zigbee-Entität setzen, einen Poll-Zyklus (≥ Intervall) abwarten und prüfen, dass der Kurzname erhalten bleibt; im Flow-Editor prüfen, dass der Picker den Kurznamen zeigt.

---

## Spec-Abdeckung (Self-Review)

- Datenmodell (`custom_name`, Entity-Feld, kein Upsert-Zugriff) → Task 1, abgesichert durch Task 5.
- Backend-Schreibpfad (`setCustomName`, Normalisierung, Endpoint, DTO) → Task 3, Task 4.
- API-Response (`customName`, `displayName`, Fallback) → Task 2.
- Frontend Model/Service → Task 6.
- Anzeige `displayName` überall (Entitäten-Tabelle, Flow-Picker) + Bearbeiten pro Zeile + Suche → Task 7, Task 8.
- Tests (setCustomName, Upsert-Regression, Mapper-Fallback, Frontend-Service) → Task 2–6.
