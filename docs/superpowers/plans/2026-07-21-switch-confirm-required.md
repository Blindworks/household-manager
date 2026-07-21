# Bestätigungspflicht für Schalter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Schalter können als „Bestätigung erforderlich" markiert werden; das Dashboard schaltet sie dann nicht direkt, sondern öffnet einen Bestätigungsdialog mit der echten Schalter-Zeile.

**Architecture:** Benutzergepflegte Boolean-Spalte `confirm_required` an `entity_states` (Muster `custom_name`), Endpoint `PUT /v1/entities/{entityId}/confirm-required`, Flag in `EntityStateResponse` und `SwitchResponse`. Im Dashboard wird `toggleSwitch` zum Guard; der eigentliche Schaltpfad wandert nach `executeToggle`, der Bestätigungsdialog (Lumina-Stil) rendert `app-switch-list` mit genau einer Entität. Reiner UI-Schutz — die API erzwingt nichts.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / Lombok (Backend), Angular 19 standalone (Frontend), JUnit 5 + Mockito + MockMvc, Jasmine/Karma.

**Spec:** `docs/superpowers/specs/2026-07-21-switch-confirm-required-design.md`

## Wichtige Umgebungshinweise

- **Backend-Maven braucht JDK 21** (Maschinen-Default ist JDK 17). Vor jedem `mvn` in PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'`. Aus `backend/` ausführen; kein `mvnw`.
- Gezielt mit `-Dtest=...` testen — der volle Backend-Lauf enthält 3 bekannte, umgebungsbedingte DB-Fehler (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest`).
- Frontend-Tests aus `frontend/`: `npx ng test --watch=false --browsers=ChromeHeadless [--include=...]`. Bekannt-rot (vorbestehend, NICHT anfassen): 4 Tests in App/Hero/Header-Specs (`NullInjectorError: ActivatedRoute`).
- Lumina-Styles leben ausschließlich in `dashboard.component.scss` — der neue Dialog gehört dorthin (Markup in `dashboard.component.html`).

## File-Struktur (Übersicht)

| Datei | Zweck |
| --- | --- |
| `backend/src/main/resources/db/changelog/changes/20260721-0036-add-confirm-required-to-entity-states.xml` | Neue Spalte (Create) |
| `backend/src/main/resources/db/changelog/db.changelog-master.xml` | Include (Modify) |
| `backend/src/main/java/com/household/manager/model/entity/EntityState.java` | Feld `confirmRequired` (Modify) |
| `backend/src/main/java/com/household/manager/entitystate/EntityStateService.java` | `setConfirmRequired` (Modify) |
| `backend/src/main/java/com/household/manager/dto/UpdateConfirmRequiredRequest.java` | Request-DTO (Create) |
| `backend/src/main/java/com/household/manager/dto/EntityStateResponse.java` | + `confirmRequired` (Modify) |
| `backend/src/main/java/com/household/manager/dto/SwitchResponse.java` | + `confirmRequired` (Modify) |
| `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java` | Mapping (Modify) |
| `backend/src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java` | Mapping (Modify) |
| `backend/src/main/java/com/household/manager/controller/EntityStateController.java` | PUT-Endpoint (Modify) |
| `frontend/src/app/models/switch.model.ts` | + `confirmRequired` (Modify) |
| `frontend/src/app/models/entity-state.model.ts` | + `confirmRequired?` (Modify) |
| `frontend/src/app/services/entity-state.service.ts` | `setConfirmRequired` (Modify) |
| `frontend/src/app/pages/entities/entities.component.{ts,html,scss,spec.ts}` | Checkbox (Modify) |
| `frontend/src/app/pages/dashboard/dashboard.component.{ts,html,scss,spec.ts}` | Guard + Bestätigungsdialog (Modify) |
| `CLAUDE.md` | Doku (Modify) |

---

### Task 1: Liquibase-Spalte + Entity-Feld

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260721-0036-add-confirm-required-to-entity-states.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (am Ende, vor `</databaseChangeLog>`)
- Modify: `backend/src/main/java/com/household/manager/model/entity/EntityState.java`

- [ ] **Step 1: Changeset-Datei anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260721-0036-add-confirm-required-to-entity-states" author="household-manager">
        <preConditions onFail="MARK_RAN">
            <not>
                <columnExists tableName="entity_states" columnName="confirm_required"/>
            </not>
        </preConditions>
        <addColumn tableName="entity_states">
            <column name="confirm_required" type="BOOLEAN" defaultValueBoolean="false">
                <constraints nullable="false"/>
            </column>
        </addColumn>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Include im Master-Changelog**

Nach dem Include von `20260720-0035-create-entity-tile-visibility-table.xml` einfügen:

```xml
    <!-- Bestaetigungspflicht fuer Schalter -->
    <include file="db/changelog/changes/20260721-0036-add-confirm-required-to-entity-states.xml"/>
```

- [ ] **Step 3: Entity-Feld ergänzen**

In `EntityState.java` nach dem Feld `customName` einfügen:

```java
    /**
     * Bestätigungspflicht beim Schalten (reiner UI-Schutz im Dashboard).
     * Benutzergepflegt wie {@link #customName}; wird vom Polling-Upsert nie überschrieben.
     */
    @Column(name = "confirm_required", nullable = false)
    private boolean confirmRequired;
```

Hinweis: Der `EntityStateWriter`-Upsert lädt bestehende Entities und setzt nur `friendlyName`/`attributes`/`state`/Zeitstempel — das neue Feld bleibt automatisch erhalten. Beim Builder-Neuanlegen ist der Primitive-Default `false`. KEINE Änderung am Writer nötig.

- [ ] **Step 4: Kompilieren**

Aus `backend/` (PowerShell):
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn compile -q
```
Erwartet: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java
git commit -m "feat(entities): Spalte confirm_required an entity_states"
```

---

### Task 2: `EntityStateService.setConfirmRequired` (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntityStateService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/EntityStateServiceTest.java` (erweitern)

- [ ] **Step 1: Failing Tests ergänzen**

Am Ende von `EntityStateServiceTest` (gleicher Stil wie die `setCustomName*`-Tests — Service wird inline mit `new EntityStateService(writer, repository, eventPublisher)` gebaut):

```java
    @Test
    void setConfirmRequiredPersists() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        EntityState entity = EntityState.builder().entityId("switch.kasa_x").friendlyName("Pumpe").build();
        when(repository.findByEntityId("switch.kasa_x")).thenReturn(Optional.of(entity));
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<EntityState> result = svc.setConfirmRequired("switch.kasa_x", true);

        assertTrue(result.isPresent());
        assertTrue(result.get().isConfirmRequired());
    }

    @Test
    void setConfirmRequiredCanBeCleared() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        EntityState entity = EntityState.builder()
                .entityId("switch.kasa_x").friendlyName("Pumpe").confirmRequired(true).build();
        when(repository.findByEntityId("switch.kasa_x")).thenReturn(Optional.of(entity));
        when(repository.save(any(EntityState.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<EntityState> result = svc.setConfirmRequired("switch.kasa_x", false);

        assertTrue(result.isPresent());
        assertFalse(result.get().isConfirmRequired());
    }

    @Test
    void setConfirmRequiredReturnsEmptyForUnknownEntity() {
        EntityStateService svc = new EntityStateService(writer, repository, eventPublisher);
        when(repository.findByEntityId("switch.unknown")).thenReturn(Optional.empty());

        Optional<EntityState> result = svc.setConfirmRequired("switch.unknown", true);

        assertFalse(result.isPresent());
    }
```

(Die statischen Imports `assertTrue`/`assertFalse` und `when`/`any` existieren in der Datei bereits.)

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityStateServiceTest" -q
```
Erwartet: COMPILATION ERROR (`setConfirmRequired` existiert nicht).

- [ ] **Step 3: Service-Methode implementieren**

In `EntityStateService.java` nach `setCustomName`/`normalizeCustomName` einfügen:

```java
    /**
     * Setzt die Bestätigungspflicht eines Schalters (reiner UI-Schutz im Dashboard;
     * Flows und API schalten weiterhin direkt). Benutzerinitiierter Schreibpfad
     * wie {@link #setCustomName}.
     *
     * @return aktualisierte Entität, oder leer wenn die entityId unbekannt ist
     */
    @Transactional
    public Optional<EntityState> setConfirmRequired(String entityId, boolean confirmRequired) {
        return repository.findByEntityId(entityId).map(entity -> {
            entity.setConfirmRequired(confirmRequired);
            return repository.save(entity);
        });
    }
```

- [ ] **Step 4: Tests ausführen — müssen grün sein**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityStateServiceTest" -q
```
Erwartet: alle Tests grün (bestehende + 3 neue).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(entities): setConfirmRequired im EntityStateService"
```

---

### Task 3: API — Endpoint, DTO und `confirmRequired` in beiden Antworten (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/UpdateConfirmRequiredRequest.java`
- Modify: `backend/src/main/java/com/household/manager/dto/EntityStateResponse.java`
- Modify: `backend/src/main/java/com/household/manager/dto/SwitchResponse.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/EntityStateResponseMapper.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java`
- Modify: `backend/src/main/java/com/household/manager/controller/EntityStateController.java`
- Test: `backend/src/test/java/com/household/manager/controller/EntityStateControllerTest.java` (erweitern)
- Test: `backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java` (erweitern)

- [ ] **Step 1: Failing Tests ergänzen**

In `EntityStateControllerTest` am Ende:

```java
    @Test
    void setztConfirmRequiredUndLiefertDieEntitaet() throws Exception {
        EntityState entity = sensor();
        entity.setConfirmRequired(true);
        when(entityStateService.setConfirmRequired("sensor.zigbee_wohnzimmer_temperature", true))
                .thenReturn(Optional.of(entity));

        mockMvc.perform(put("/v1/entities/sensor.zigbee_wohnzimmer_temperature/confirm-required")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmRequired\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmRequired").value(true));
    }

    @Test
    void confirmRequiredFuerUnbekannteEntitaetLiefert404() throws Exception {
        when(entityStateService.setConfirmRequired("switch.weg", true)).thenReturn(Optional.empty());

        mockMvc.perform(put("/v1/entities/switch.weg/confirm-required")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmRequired\":true}"))
                .andExpect(status().isNotFound());
    }
```

In `SwitchQueryServiceTest` am Ende:

```java
    @Test
    void uebernimmt_die_bestaetigungspflicht_in_die_antwort() {
        EntityState pumpe = device("a", "Pumpe");
        pumpe.setConfirmRequired(true);
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(pumpe));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(service.listSwitches(null).get(0).confirmRequired()).isTrue();
    }
```

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityStateControllerTest,SwitchQueryServiceTest" -q
```
Erwartet: COMPILATION ERROR (Endpoint/Record-Feld fehlen).

- [ ] **Step 3: Request-DTO anlegen**

```java
package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Anfrage zum Setzen der Bestätigungspflicht eines Schalters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateConfirmRequiredRequest {

    @NotNull(message = "confirmRequired must not be null")
    private Boolean confirmRequired;
}
```

- [ ] **Step 4: Records erweitern**

`EntityStateResponse`: nach `tileVisibility` das Feld `boolean confirmRequired` einfügen:

```java
        Map<String, Object> attributes,
        Map<String, String> tileVisibility,
        boolean confirmRequired,
        LocalDateTime lastChanged,
```

`SwitchResponse`: nach `icon` das Feld `boolean confirmRequired` einfügen:

```java
        boolean available,
        String icon,
        boolean confirmRequired,
        long toggleCount,
```

- [ ] **Step 5: Mapper erweitern**

`EntityStateResponseMapper.toResponse(entity, tileVisibility)`: im Builder nach `.tileVisibility(tileVisibility)` ergänzen:

```java
                .confirmRequired(entity.isConfirmRequired())
```

`SwitchResponseMapper.toResponse`: im Builder nach `.icon(icon(entity))` ergänzen:

```java
                .confirmRequired(entity.isConfirmRequired())
```

- [ ] **Step 6: Controller-Endpoint ergänzen**

In `EntityStateController` nach `setCustomName` einfügen (Import `com.household.manager.dto.UpdateConfirmRequiredRequest` ergänzen):

```java
    /**
     * Setzt die Bestätigungspflicht eines Schalters. Reiner UI-Schutz:
     * das Dashboard fragt vor dem Schalten nach; die API erzwingt nichts.
     */
    @PutMapping("/{entityId}/confirm-required")
    public ResponseEntity<EntityStateResponse> setConfirmRequired(
            @PathVariable String entityId,
            @Valid @RequestBody UpdateConfirmRequiredRequest request) {
        return entityStateService.setConfirmRequired(entityId, request.getConfirmRequired())
                .map(entity -> ResponseEntity.ok(toResponseWithVisibility(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
```

- [ ] **Step 7: Tests ausführen — müssen grün sein**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityStateControllerTest,SwitchQueryServiceTest,SwitchControllerTest,EntityStateServiceTest" -q
```
Erwartet: alles grün. Zusätzlich `mvn test-compile -q` (andere Aufrufer der Records — die Test-Builder in `SwitchControllerTest` nutzen `SwitchResponse.builder()`, das neue Feld hat Primitive-Default `false`, nichts bricht).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat(entities): API fuer Bestaetigungspflicht und confirmRequired in Antworten"
```

---

### Task 4: Frontend — Modelle, Service, Spec-Factories

**Files:**
- Modify: `frontend/src/app/models/switch.model.ts`
- Modify: `frontend/src/app/models/entity-state.model.ts`
- Modify: `frontend/src/app/services/entity-state.service.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (nur Factory)

- [ ] **Step 1: `SwitchEntity` erweitern**

In `switch.model.ts` nach `icon` einfügen:

```ts
  /** Erfordert im Dashboard eine Bestätigung vor dem Schalten. */
  confirmRequired: boolean;
```

- [ ] **Step 2: `EntityState` erweitern**

In `entity-state.model.ts` nach `tileVisibility` einfügen:

```ts
  /** Bestätigungspflicht beim Schalten (reiner UI-Schutz im Dashboard). */
  confirmRequired?: boolean;
```

- [ ] **Step 3: `EntityStateService.setConfirmRequired`**

In `entity-state.service.ts` nach `setTileVisibility` einfügen:

```ts
  /** Setzt die Bestätigungspflicht eines Schalters (reiner UI-Schutz). */
  setConfirmRequired(entityId: string, confirmRequired: boolean): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.baseUrl}/${entityId}/confirm-required`, { confirmRequired }).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 4: Dashboard-Spec-Factory ergänzen**

`SwitchEntity.confirmRequired` ist Pflichtfeld — die `entity`-Factory in `dashboard.component.spec.ts` (Zeile ~20) bekommt den Default:

```ts
    icon: 'toggle_on',
    confirmRequired: false,
    toggleCount: 3,
```

- [ ] **Step 5: Build + Dashboard-Spec**

Aus `frontend/`:
```powershell
npx ng build --configuration production
npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```
Erwartet: Build grün (nur bekannte SCSS-Budget-Warnung), 17 Dashboard-Tests grün.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app
git commit -m "feat(frontend): confirmRequired in Modellen und EntityStateService"
```

---

### Task 5: Entitäten-Seite — Checkbox „Bestätigung erforderlich" (TDD)

**Files:**
- Modify (Test): `frontend/src/app/pages/entities/entities.component.spec.ts`
- Modify: `frontend/src/app/pages/entities/entities.component.ts`
- Modify: `frontend/src/app/pages/entities/entities.component.html`
- Modify: `frontend/src/app/pages/entities/entities.component.scss`

- [ ] **Step 1: Failing Test ergänzen**

In `entities.component.spec.ts`: den Spy um `'setConfirmRequired'` erweitern:

```ts
    service = jasmine.createSpyObj('EntityStateService', ['getEntities', 'setTileVisibility', 'setConfirmRequired']);
```

Neuen Test am Ende:

```ts
  it('speichert die Bestaetigungspflicht und uebernimmt die aktualisierte Entitaet', () => {
    const updated = entity({ confirmRequired: true });
    service.setConfirmRequired.and.returnValue(of(updated));
    const component = createComponent();
    component.entities.set([entity({})]);

    component.setConfirmRequired(entity({}), true);

    expect(service.setConfirmRequired).toHaveBeenCalledWith('switch.kasa_wm', true);
    expect(component.entities()[0].confirmRequired).toBeTrue();
  });
```

- [ ] **Step 2: Spec ausführen — muss fehlschlagen**

Aus `frontend/`:
```powershell
npx ng test --watch=false --browsers=ChromeHeadless --include='**/entities.component.spec.ts'
```
Erwartet: FAIL (`setConfirmRequired` existiert nicht am Component).

- [ ] **Step 3: Component-Methode implementieren**

In `entities.component.ts` nach `setTileVisibility` einfügen:

```ts
  setConfirmRequired(entity: EntityState, confirmRequired: boolean): void {
    this.entityStateService.setConfirmRequired(entity.entityId, confirmRequired)
      .subscribe({
        next: updated => this.entities.update(list =>
          list.map(e => e.entityId === updated.entityId ? updated : e)),
        error: err => this.error.set(err.message)
      });
  }
```

- [ ] **Step 4: Template erweitern**

In `entities.component.html` direkt NACH dem `<div class="entities-table__tile-visibility">…</div>` (innerhalb desselben `@if (isSwitchTileConfigurable(entity))`-Blocks) einfügen:

```html
                <div class="entities-table__confirm-required">
                  <label (click)="$event.stopPropagation()">
                    <input
                      type="checkbox"
                      [ngModel]="entity.confirmRequired ?? false"
                      (ngModelChange)="setConfirmRequired(entity, $event)" />
                    Bestätigung erforderlich
                  </label>
                </div>
```

- [ ] **Step 5: Styling**

In `entities.component.scss` neben `.entities-table__tile-visibility` (gleiche Nesting-Ebene, Datei-Konventionen übernehmen):

```scss
.entities-table__confirm-required {
  margin-bottom: 0.75rem;

  label {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    cursor: pointer;
  }
}
```

- [ ] **Step 6: Spec ausführen — muss grün sein**

```powershell
npx ng test --watch=false --browsers=ChromeHeadless --include='**/entities.component.spec.ts'
```
Erwartet: 4 Specs, 0 Failures.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/entities
git commit -m "feat(entities-ui): Checkbox Bestaetigung erforderlich"
```

---

### Task 6: Dashboard — Guard und Bestätigungsdialog (TDD)

**Files:**
- Modify (Test): `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

- [ ] **Step 1: Failing Tests ergänzen**

In `dashboard.component.spec.ts`, describe-Block „DashboardComponent (Schalter)", am Ende:

```ts
  it('oeffnet bei Bestaetigungspflicht den Dialog statt zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true }));
    tick();

    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.confirmSwitch?.entityId).toBe('switch.kasa_abc');

    discardPeriodicTasks();
  }));

  it('schaltet nach Bestaetigung im Dialog und schliesst ihn', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    const guarded = entity({ confirmRequired: true });
    fixture.componentInstance.toggleSwitch(guarded);

    fixture.componentInstance.confirmToggle(guarded);
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();
    expect(fixture.componentInstance.topSwitches[0].state).toBe('on');

    discardPeriodicTasks();
  }));

  it('abbrechen schliesst den Bestaetigungsdialog ohne zu schalten', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggleSwitch(entity({ confirmRequired: true }));

    fixture.componentInstance.closeConfirmDialog();
    tick();

    expect(switchServiceSpy.toggle).not.toHaveBeenCalled();
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

    discardPeriodicTasks();
  }));

  it('schalter ohne bestaetigungspflicht schalten weiterhin direkt', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.toggleSwitch(entity({ state: 'off' }));
    tick();

    expect(switchServiceSpy.toggle).toHaveBeenCalledWith('switch.kasa_abc');
    expect(fixture.componentInstance.confirmSwitch).toBeNull();

    discardPeriodicTasks();
  }));
```

- [ ] **Step 2: Spec ausführen — muss fehlschlagen**

Aus `frontend/`:
```powershell
npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```
Erwartet: FAIL (`confirmSwitch`/`confirmToggle`/`closeConfirmDialog` existieren nicht).

- [ ] **Step 3: Component umbauen**

In `dashboard.component.ts`:

1. Neues Feld bei den anderen Dialog-Feldern (nach `switchError`):

```ts
  /** Entität, deren Schalten gerade auf Bestätigung wartet (null = Dialog zu). */
  confirmSwitch: SwitchEntity | null = null;
```

2. `toggleSwitch` ersetzen — der bisherige Rumpf (optimistisches Update + Service-Aufruf) wandert unverändert in die neue private Methode `executeToggle`:

```ts
  /**
   * Schaltet einen Schalter. Bestätigungspflichtige Schalter werden nicht direkt
   * geschaltet, sondern öffnen den Bestätigungsdialog; erst der Klick auf den
   * Schalter im Dialog führt den Toggle aus.
   */
  toggleSwitch(entity: SwitchEntity): void {
    if (this.pendingSwitchIds.has(entity.entityId)) {
      return;
    }
    if (entity.confirmRequired) {
      this.confirmSwitch = entity;
      return;
    }
    this.executeToggle(entity);
  }

  /** Bestätigung im Dialog: schließt ihn und führt den eigentlichen Toggle aus. */
  confirmToggle(entity: SwitchEntity): void {
    this.closeConfirmDialog();
    this.executeToggle(entity);
  }

  closeConfirmDialog(): void {
    this.confirmSwitch = null;
  }

  /** Liste mit genau dem zu bestätigenden Schalter für app-switch-list. */
  get confirmSwitchList(): SwitchEntity[] {
    return this.confirmSwitch ? [this.confirmSwitch] : [];
  }

  /**
   * Führt den Schaltbefehl aus. Der Zustand wird optimistisch umgeschaltet und
   * bei einem Fehler zurueckgesetzt, damit die Kachel sofort reagiert.
   */
  private executeToggle(entity: SwitchEntity): void {
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
```

3. `onEscape` um den neuen Dialog ergänzen:

```ts
  /** Schliesst die geoeffneten Dialoge per Escape-Taste. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeFlowDialog();
    this.closeSwitchDialog();
    this.closeConfirmDialog();
  }
```

- [ ] **Step 4: Dialog-Markup ergänzen**

In `dashboard.component.html` GANZ AM ENDE, nach dem Schalter-Dialog-Block (damit der Bestätigungsdialog über dem Schalter-Dialog liegt), vor dem schließenden `</div>` der `lumina`-Hülle:

```html
  <!-- Bestaetigungsdialog fuer geschuetzte Schalter -->
  <div
    *ngIf="confirmSwitch"
    class="lumina__dialog-backdrop"
    (click)="closeConfirmDialog()"
  >
    <div
      class="lumina__dialog lumina__dialog--confirm"
      role="dialog"
      aria-modal="true"
      aria-label="Schalten bestätigen"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <h2 class="lumina__dialog-title">Schalten bestätigen</h2>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="closeConfirmDialog()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>
      <div class="lumina__dialog-body">
        <p class="lumina__confirm-hint">
          Dieser Schalter erfordert eine Bestätigung. Zum Schalten den Schalter antippen.
        </p>
        <app-switch-list
          [switches]="confirmSwitchList"
          [pendingIds]="pendingSwitchIds"
          variant="dialog"
          (toggled)="confirmToggle($event)"
        ></app-switch-list>
        <button type="button" class="lumina__confirm-cancel" (click)="closeConfirmDialog()">
          Abbrechen
        </button>
      </div>
    </div>
  </div>
```

- [ ] **Step 5: Styles ergänzen**

In `dashboard.component.scss` bei den Dialog-Styles (Selektoren/Nesting an die bestehenden `lumina__dialog*`-Regeln der Datei anpassen; Farbwerte durch dort verwendete Variablen ersetzen, falls vorhanden):

```scss
.lumina__dialog--confirm {
  max-width: 26rem;
}

.lumina__confirm-hint {
  margin: 0 0 1rem;
  font-size: 0.95rem;
  opacity: 0.75;
}

.lumina__confirm-cancel {
  margin-top: 1rem;
  width: 100%;
  padding: 0.7rem;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 0.75rem;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
```

WICHTIG: Lumina-Styles gehören ausschließlich in `dashboard.component.scss` — Kind-Komponenten kommen an sie nicht heran. Der Dialog liegt im Dashboard-Template, `app-switch-list` bringt eigene Styles mit.

- [ ] **Step 6: Spec ausführen — muss grün sein**

```powershell
npx ng test --watch=false --browsers=ChromeHeadless --include='**/dashboard.component.spec.ts'
```
Erwartet: 21 Tests grün (17 bestehende + 4 neue).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(dashboard): Bestaetigungsdialog fuer geschuetzte Schalter"
```

---

### Task 7: Abschluss — Gesamtverifikation + CLAUDE.md

- [ ] **Step 1: Berührte Backend-Tests**

Aus `backend/`:
```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test "-Dtest=EntityStateServiceTest,EntityStateControllerTest,SwitchQueryServiceTest,SwitchControllerTest,SwitchCommandServiceTest,EntityTileVisibilityServiceTest" -q
```
Erwartet: grün.

- [ ] **Step 2: Kompletter Backend-Testlauf**

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test
```
Erwartet: nur die 3 bekannten umgebungsbedingten Fehler (`contextLoads`, 2× `HealthControllerTest`).

- [ ] **Step 3: Kompletter Frontend-Testlauf + Produktions-Build**

Aus `frontend/`:
```powershell
npx ng test --watch=false --browsers=ChromeHeadless
npx ng build --configuration production
```
Erwartet: nur die 4 bekannten vorbestehenden Fehler (ActivatedRoute in App/Hero/Header-Specs); Build grün (bekannte SCSS-Budget-Warnung ok — falls die neuen Styles das Budget weiter reißen, ist das akzeptiert).

- [ ] **Step 4: CLAUDE.md aktualisieren**

In `CLAUDE.md`, Abschnitt „## Database Schema" → „### Current Entities", nach dem Bullet „Entity Tile Visibility" ergänzen:

```markdown
- **Switch Confirmation**: `confirm_required` flag on `entity_states`
  - UI-only guard: the dashboard shows a confirmation dialog (with the real switch row) before toggling; flows and the API keep switching directly
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Bestaetigungspflicht in CLAUDE.md dokumentieren"
```
