# Verbraucher-Kachel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die statische Platzhalter-Kachel „Schlafzimmer" im Lumina-Dashboard durch eine Live-Kachel ersetzen, die die Stromverbraucher des Hauses (Power-Sensoren der Steckdosen) mit aktueller Leistung anzeigt — Top 4 auf der Kachel, alle im Dialog.

**Architecture:** Neuer lesender Backend-Endpoint `GET /v1/power-consumers` (Muster `/v1/switches`): ein `PowerConsumerQueryService` filtert die Entity-State-Schicht auf `SENSOR`-Entitäten mit `deviceClass = "power"`, schließt die Haus-Bilanz-Quellen `TASMOTA` und `ANKER_SOLIX` aus und sortiert absteigend nach Watt. Das Frontend pollt alle 30 s und rendert die Kachel direkt im Dashboard-Template (lumina-Styles sind in `dashboard.component.scss` gekapselt — Kind-Komponenten kämen nicht an sie heran).

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / JUnit 5 + Mockito + AssertJ (Backend), Angular 19 standalone / RxJS / Karma+Jasmine (Frontend).

**Spec:** `docs/superpowers/specs/2026-07-22-verbraucher-kachel-design.md`

**Wichtig — Umgebung (diese Maschine):**
- Vor jedem Maven-Aufruf: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (Bash) — das Default-JAVA_HOME zeigt auf JDK 17 und `mvn` bricht sonst ab. Maven aus `backend/` heraus aufrufen, es gibt kein `mvnw`.
- Die lokalen Integrationstests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen mit „Access denied for user 'root'" fehl (keine lokale Test-DB). Das ist vorbestehend und zu ignorieren — deshalb immer gezielt `-Dtest=<Klasse>` ausführen.
- Frontend-Tests: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`.

---

## Task 0: Feature-Branch anlegen

Die Arbeit gehört auf einen eigenen Branch. Achtung: Der aktuelle Branch ist
`feature/blink-gesichtserkennung` (dort liegt bereits der Spec-Commit
`docs: Design-Spec fuer Verbraucher-Kachel...`).

- [ ] **Step 1: Branch vom aktuellen Stand abzweigen**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git checkout -b feature/verbraucher-kachel
```

(Vom Blink-Branch abzweigen ist in Ordnung — er enthält gegenüber `main` nur
Doku-Commits. Falls das Repo zwischenzeitlich auf `main` steht: von dort abzweigen
und den Spec-Commit per `git cherry-pick 1085eb6` mitnehmen.)

---

## Task 1: Backend — DTO `PowerConsumerResponse`

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/PowerConsumerResponse.java`

- [ ] **Step 1: DTO anlegen**

```java
package com.household.manager.dto;

import java.math.BigDecimal;

/**
 * Ein Stromverbraucher für die Verbraucher-Kachel: Power-Sensor einer
 * Steckdose (Meross, Shelly, ...) mit aktueller Leistung.
 */
public record PowerConsumerResponse(
        String entityId,
        String displayName,
        /** Aktuelle Leistung in Watt; null, wenn der Sensor nicht erreichbar ist. */
        BigDecimal powerWatts,
        boolean unavailable
) {
}
```

- [ ] **Step 2: Kompilieren**

Run (Bash):
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/PowerConsumerResponse.java
git commit -m "feat(verbraucher): PowerConsumerResponse-DTO fuer die Verbraucher-Kachel"
```

---

## Task 2: Backend — `PowerConsumerQueryService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/PowerConsumerQueryService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/PowerConsumerQueryServiceTest.java`

Muster: `SwitchQueryService` + `SwitchQueryServiceTest` (Mock-Repository, echter
`EntityStateResponseMapper` mit `new ObjectMapper()`). Attribute liegen als
JSON-String auf der Entity (`{"unit":"W","deviceClass":"power"}`).

- [ ] **Step 1: Failing Tests schreiben**

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerConsumerQueryServiceTest {

    private static final String POWER_ATTRIBUTES = "{\"unit\":\"W\",\"deviceClass\":\"power\"}";
    private static final String TEMPERATURE_ATTRIBUTES = "{\"unit\":\"C\",\"deviceClass\":\"temperature\"}";

    @Mock
    private EntityStateRepository entityStateRepository;

    private PowerConsumerQueryService service;

    @BeforeEach
    void setUp() {
        service = new PowerConsumerQueryService(
                entityStateRepository, new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState sensor(EntitySource source, String ref, String name, String state, String attributes) {
        return EntityState.builder()
                .entityId("sensor." + source.name().toLowerCase() + "_" + ref + "_power")
                .domain(EntityDomain.SENSOR)
                .source(source)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private List<String> namesOf(List<PowerConsumerResponse> consumers) {
        return consumers.stream().map(PowerConsumerResponse::displayName).toList();
    }

    @Test
    void liefert_nur_power_sensoren() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "wm", "Waschmaschine Leistung", "1200", POWER_ATTRIBUTES),
                sensor(EntitySource.ZIGBEE, "wz", "Wohnzimmer Temperatur", "21.5", TEMPERATURE_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Waschmaschine Leistung");
    }

    @Test
    void schliesst_haus_bilanz_quellen_aus() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "wm", "Waschmaschine Leistung", "1200", POWER_ATTRIBUTES),
                sensor(EntitySource.TASMOTA, "main", "Hausverbrauch", "3400", POWER_ATTRIBUTES),
                sensor(EntitySource.ANKER_SOLIX, "pv_power", "Solarleistung", "800", POWER_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Waschmaschine Leistung");
    }

    @Test
    void sortiert_absteigend_nach_leistung() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "a", "Klein", "5.5", POWER_ATTRIBUTES),
                sensor(EntitySource.SHELLY, "b", "Gross", "1450", POWER_ATTRIBUTES),
                sensor(EntitySource.MEROSS, "c", "Mittel", "230", POWER_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Gross", "Mittel", "Klein");
    }

    @Test
    void nicht_numerische_states_gelten_als_unavailable_und_stehen_hinten() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "a", "Offline", "unavailable", POWER_ATTRIBUTES),
                sensor(EntitySource.SHELLY, "b", "Aktiv", "42", POWER_ATTRIBUTES)));

        List<PowerConsumerResponse> consumers = service.listConsumers(null);

        assertThat(namesOf(consumers)).containsExactly("Aktiv", "Offline");
        assertThat(consumers.get(1).unavailable()).isTrue();
        assertThat(consumers.get(1).powerWatts()).isNull();
        assertThat(consumers.get(0).powerWatts()).isEqualByComparingTo(new BigDecimal("42"));
    }

    @Test
    void limit_kappt_die_liste_nach_der_sortierung() {
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR)).thenReturn(List.of(
                sensor(EntitySource.MEROSS, "a", "Klein", "5", POWER_ATTRIBUTES),
                sensor(EntitySource.SHELLY, "b", "Gross", "1450", POWER_ATTRIBUTES)));

        assertThat(namesOf(service.listConsumers(1))).containsExactly("Gross");
    }

    @Test
    void custom_name_gewinnt_ueber_friendly_name() {
        EntityState entity = sensor(EntitySource.MEROSS, "wm", "Waschmaschine Leistung", "10", POWER_ATTRIBUTES);
        entity.setCustomName("Waschmaschine");
        when(entityStateRepository.findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR))
                .thenReturn(List.of(entity));

        assertThat(namesOf(service.listConsumers(null))).containsExactly("Waschmaschine");
    }
}
```

- [ ] **Step 2: Tests ausführen — sie müssen fehlschlagen (Klasse existiert nicht)**

Run (Bash):
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=PowerConsumerQueryServiceTest -q
```
Expected: COMPILATION ERROR („cannot find symbol: class PowerConsumerQueryService")

- [ ] **Step 3: Service implementieren**

```java
package com.household.manager.entitystate;

import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Liefert die Stromverbraucher für die Verbraucher-Kachel: alle Power-Sensoren
 * der Entity-State-Schicht (deviceClass "power"), absteigend nach Leistung.
 * <p>
 * Haus-Bilanz-Quellen (Tasmota-Gesamtverbrauch, Anker-Solix PV/Akku/Netz) sind
 * keine Einzelverbraucher und werden ausgeschlossen.
 */
@Service
@RequiredArgsConstructor
public class PowerConsumerQueryService {

    private static final String DEVICE_CLASS_POWER = "power";
    /** Quellen, deren Power-Sensoren die Haus-Bilanz abbilden, keine Einzelgeräte. */
    private static final Set<EntitySource> HOUSE_BALANCE_SOURCES =
            Set.of(EntitySource.TASMOTA, EntitySource.ANKER_SOLIX);

    private final EntityStateRepository entityStateRepository;
    private final EntityStateResponseMapper entityStateResponseMapper;

    /** @param limit maximale Anzahl Einträge; null oder <= 0 liefert alle */
    @Transactional(readOnly = true)
    public List<PowerConsumerResponse> listConsumers(Integer limit) {
        List<PowerConsumerResponse> consumers = entityStateRepository
                .findByDomainOrderByEntityIdAsc(EntityDomain.SENSOR).stream()
                .filter(entity -> !HOUSE_BALANCE_SOURCES.contains(entity.getSource()))
                .filter(this::isPowerSensor)
                .map(this::toResponse)
                .sorted(byPowerDescending())
                .toList();

        if (limit != null && limit > 0 && limit < consumers.size()) {
            return List.copyOf(consumers.subList(0, limit));
        }
        return consumers;
    }

    private boolean isPowerSensor(EntityState entity) {
        Map<String, Object> attributes =
                entityStateResponseMapper.parseAttributes(entity.getAttributes());
        return DEVICE_CLASS_POWER.equals(attributes.get("deviceClass"));
    }

    private PowerConsumerResponse toResponse(EntityState entity) {
        BigDecimal watts = parseWatts(entity.getState());
        return new PowerConsumerResponse(
                entity.getEntityId(),
                entityStateResponseMapper.displayName(entity),
                watts,
                watts == null);
    }

    /** Nicht-numerische States ("unavailable", "unknown") ergeben null. */
    private BigDecimal parseWatts(String state) {
        if (state == null) {
            return null;
        }
        try {
            return new BigDecimal(state.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Größter Verbraucher zuerst; unavailable ans Ende; Gleichstand alphabetisch. */
    private Comparator<PowerConsumerResponse> byPowerDescending() {
        return Comparator.comparing(PowerConsumerResponse::powerWatts,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(consumer -> consumer.displayName().toLowerCase(Locale.ROOT));
    }
}
```

- [ ] **Step 4: Tests ausführen — alle grün**

Run (Bash):
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest=PowerConsumerQueryServiceTest -q
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/PowerConsumerQueryService.java \
        backend/src/test/java/com/household/manager/entitystate/PowerConsumerQueryServiceTest.java
git commit -m "feat(verbraucher): PowerConsumerQueryService filtert und sortiert Power-Sensoren"
```

---

## Task 3: Backend — `PowerConsumerController`

**Files:**
- Create: `backend/src/main/java/com/household/manager/controller/PowerConsumerController.java`

Reiner Durchreicher (Muster `SwitchController`) — die Logik steckt im getesteten
Query-Service, daher kein eigener Controller-Test (konsistent mit `SwitchController`,
der ebenfalls keinen hat).

- [ ] **Step 1: Controller anlegen**

```java
package com.household.manager.controller;

import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.entitystate.PowerConsumerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API für die Verbraucher-Kachel (Stromverbraucher, größter zuerst).
 */
@RestController
@RequestMapping("/v1/power-consumers")
@RequiredArgsConstructor
public class PowerConsumerController {

    private final PowerConsumerQueryService powerConsumerQueryService;

    /** @param limit optionale Obergrenze; ohne Angabe werden alle Verbraucher geliefert */
    @GetMapping
    public List<PowerConsumerResponse> getConsumers(
            @RequestParam(required = false) Integer limit) {
        return powerConsumerQueryService.listConsumers(limit);
    }
}
```

- [ ] **Step 2: Kompilieren + Regressionstests des Pakets**

Run (Bash):
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -Dtest='PowerConsumerQueryServiceTest,SwitchQueryServiceTest' -q
```
Expected: BUILD SUCCESS, alle Tests grün

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/PowerConsumerController.java
git commit -m "feat(verbraucher): GET /v1/power-consumers"
```

---

## Task 4: Frontend — Model + `PowerConsumerService`

**Files:**
- Create: `frontend/src/app/models/power-consumer.model.ts`
- Create: `frontend/src/app/services/power-consumer.service.ts`

Muster: `switch.model.ts` / `switch.service.ts`. Dev-Proxy und nginx leiten
`/api/...` an das Backend weiter (Context-Path `/api` + Controller-Pfad
`/v1/power-consumers`).

- [ ] **Step 1: Model anlegen**

```typescript
/**
 * Ein Stromverbraucher für die Verbraucher-Kachel: Power-Sensor einer
 * Steckdose (Meross, Shelly, ...) mit aktueller Leistung.
 */
export interface PowerConsumer {
  entityId: string;
  displayName: string;
  /** Aktuelle Leistung in Watt; null, wenn der Sensor nicht erreichbar ist. */
  powerWatts: number | null;
  unavailable: boolean;
}
```

- [ ] **Step 2: Service anlegen**

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PowerConsumer } from '../models/power-consumer.model';

/**
 * REST-Service für die Verbraucher-Kachel (Stromverbraucher, größter zuerst).
 */
@Injectable({ providedIn: 'root' })
export class PowerConsumerService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/power-consumers';

  /** Stromverbraucher, absteigend nach Leistung sortiert. */
  getConsumers(limit?: number): Observable<PowerConsumer[]> {
    let params = new HttpParams();
    if (limit != null) {
      params = params.set('limit', limit);
    }
    return this.http.get<PowerConsumer[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Verbraucher-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Verbraucher-Anfrage.'));
  }
}
```

- [ ] **Step 3: Build prüfen**

Run (Bash):
```bash
cd frontend && npx ng build --configuration production 2>&1 | tail -5
```
Expected: Build erfolgreich, keine Compile-Fehler

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/power-consumer.model.ts \
        frontend/src/app/services/power-consumer.service.ts
git commit -m "feat(verbraucher): PowerConsumer-Model und -Service im Frontend"
```

---

## Task 5: Frontend — Dashboard-Umbau (TDD)

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

Neuen `describe`-Block ans Ende von `dashboard.component.spec.ts` anhängen.
Imports oben ergänzen:

```typescript
import { PowerConsumerService } from '../../services/power-consumer.service';
import { PowerConsumer } from '../../models/power-consumer.model';
```

```typescript
describe('DashboardComponent (Verbraucher-Kachel)', () => {
  let consumerServiceSpy: jasmine.SpyObj<PowerConsumerService>;

  const consumer = (overrides: Partial<PowerConsumer> = {}): PowerConsumer => ({
    entityId: 'sensor.meross_wm_power',
    displayName: 'Waschmaschine',
    powerWatts: 1250,
    unavailable: false,
    ...overrides
  });

  beforeEach(async () => {
    consumerServiceSpy = jasmine.createSpyObj('PowerConsumerService', ['getConsumers']);
    consumerServiceSpy.getConsumers.and.returnValue(of([consumer()]));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

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
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PowerConsumerService, useValue: consumerServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  it('laedt die groessten Verbraucher fuer die Kachel', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith(4);
    expect(fixture.componentInstance.topConsumers.length).toBe(1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Waschmaschine');

    discardPeriodicTasks();
  }));

  it('formatiert die Leistung deutsch und ganzzahlig', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.powerLabel(consumer({ powerWatts: 1250.4 })))
      .toBe('1.250 W');

    discardPeriodicTasks();
  }));

  it('zeigt fuer unavailable-Verbraucher einen Strich', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.powerLabel(
      consumer({ powerWatts: null, unavailable: true }))).toBe('–');

    discardPeriodicTasks();
  }));

  it('oeffnet den Dialog und laedt dafuer alle Verbraucher', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    consumerServiceSpy.getConsumers.calls.reset();

    fixture.componentInstance.openConsumerDialog();
    tick();

    expect(fixture.componentInstance.consumerDialogOpen).toBeTrue();
    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith();

    discardPeriodicTasks();
  }));

  it('aktualisiert die Dialogliste im Poll-Takt mit', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openConsumerDialog();
    tick();
    consumerServiceSpy.getConsumers.calls.reset();

    tick(30000);

    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith(4);
    expect(consumerServiceSpy.getConsumers).toHaveBeenCalledWith();

    discardPeriodicTasks();
  }));

  it('behaelt beim Ladefehler die zuletzt bekannte Liste', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.topConsumers.length).toBe(1);
    consumerServiceSpy.getConsumers.and.returnValue(throwError(() => new Error('kaputt')));

    tick(30000);

    expect(fixture.componentInstance.topConsumers.length).toBe(1);

    discardPeriodicTasks();
  }));

  it('schliessen leert die Dialogliste', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openConsumerDialog();
    tick();

    fixture.componentInstance.closeConsumerDialog();

    expect(fixture.componentInstance.consumerDialogOpen).toBeFalse();
    expect(fixture.componentInstance.allConsumers.length).toBe(0);

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Tests ausführen — sie müssen fehlschlagen**

Run (Bash):
```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -20
```
Expected: FAIL — `topConsumers`, `powerLabel`, `openConsumerDialog` existieren nicht

- [ ] **Step 3: Component umbauen**

In `dashboard.component.ts`:

**3a — Imports ergänzen** (bei den anderen Service-/Model-Imports):

```typescript
import { PowerConsumerService } from '../../services/power-consumer.service';
import { PowerConsumer } from '../../models/power-consumer.model';
```

**3b — Injektion + Subscription + Konstanten** (Muster der Nachbarn):

```typescript
  private readonly powerConsumerService = inject(PowerConsumerService);
```

```typescript
  private consumerSubscription?: Subscription;
```

```typescript
  /** Anzahl der Verbraucher auf der Kachel; alle weiteren stehen im Dialog. */
  private static readonly CONSUMER_TILE_LIMIT = 4;
  /** Aktualisierungsintervall der Verbraucher-Kachel (30 s). */
  private static readonly CONSUMER_REFRESH_MS = 30000;
```

**3c — Platzhalter entfernen:** das Feld `rooms` (Zeilen um 122-125), das
Interface `RoomTile` am Dateiende sowie den Kommentar darüber ersatzlos löschen.

**3d — Felder für die Kachel** (bei den Schalter-Feldern):

```typescript
  /** Größte Stromverbraucher für die Kachel. */
  topConsumers: PowerConsumer[] = [];
  /** Alle Verbraucher; nur gefüllt, solange der Verbraucher-Dialog offen ist. */
  allConsumers: PowerConsumer[] = [];
  /** True, wenn der Verbraucher-Dialog geöffnet ist. */
  consumerDialogOpen = false;
```

**3e — Lifecycle:** in `ngOnInit()` nach `this.startSwitchRefresh();` einfügen:

```typescript
    this.startConsumerRefresh();
```

in `ngOnDestroy()` bei den anderen unsubscribes:

```typescript
    this.consumerSubscription?.unsubscribe();
```

im Escape-Handler `onEscape()`:

```typescript
    this.closeConsumerDialog();
```

**3f — Methoden** (nach `closeSwitchDialog()` einfügen):

```typescript
  /** Öffnet den Verbraucher-Dialog und lädt dafür die vollständige Liste. */
  openConsumerDialog(): void {
    if (this.consumerDialogOpen) {
      return;
    }
    this.consumerDialogOpen = true;
    this.loadAllConsumers();
  }

  closeConsumerDialog(): void {
    if (!this.consumerDialogOpen) {
      return;
    }
    this.consumerDialogOpen = false;
    this.allConsumers = [];
  }

  /** Leistung als "1.250 W"; unavailable-Geräte zeigen einen Strich. */
  powerLabel(consumer: PowerConsumer): string {
    if (consumer.powerWatts == null) {
      return '–';
    }
    return `${Math.round(consumer.powerWatts).toLocaleString('de-DE')} W`;
  }
```

**3g — Polling** (nach `startSwitchRefresh()` einfügen):

```typescript
  private startConsumerRefresh(): void {
    this.consumerSubscription = interval(DashboardComponent.CONSUMER_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannte Liste (null = kein Update).
        switchMap(() => this.powerConsumerService
          .getConsumers(DashboardComponent.CONSUMER_TILE_LIMIT)
          .pipe(catchError(() => of<PowerConsumer[] | null>(null))))
      )
      .subscribe(consumers => {
        if (consumers) {
          this.topConsumers = consumers;
        }
        // Der offene Dialog soll dieselbe Aktualität haben wie die Kachel.
        if (this.consumerDialogOpen) {
          this.loadAllConsumers();
        }
      });
  }

  private loadAllConsumers(): void {
    this.powerConsumerService.getConsumers()
      .pipe(catchError(() => of<PowerConsumer[] | null>(null)))
      .subscribe(consumers => {
        if (consumers) {
          this.allConsumers = consumers;
        }
      });
  }
```

**3h — Template:** In `dashboard.component.html` den Block
`<!-- Raum-Kacheln (Platzhalter) -->` samt `<button *ngFor="let room of rooms" ...>`
(Zeilen 122-141) ersetzen durch:

```html
        <!-- Verbraucher-Kachel: Stromverbraucher, groesster zuerst -->
        <div
          class="lumina-card lumina__room lumina__consumer-tile lumina__fade"
          style="--delay: 0.3s"
        >
          <div class="lumina__room-top">
            <div class="lumina__room-icon">
              <span class="material-symbols-outlined">bolt</span>
            </div>
            <button
              type="button"
              class="lumina__switch-all"
              (click)="openConsumerDialog()"
              title="Alle Verbraucher anzeigen"
              aria-label="Alle Verbraucher anzeigen"
            >
              <span class="material-symbols-outlined">expand_content</span>
            </button>
          </div>
          <div class="lumina__room-body">
            <h3 class="lumina__room-name">Verbraucher</h3>
            <div class="lumina__consumer-rows">
              <p *ngIf="topConsumers.length === 0" class="lumina__consumer-empty">
                Keine Verbraucher
              </p>
              <div
                *ngFor="let consumer of topConsumers"
                class="lumina__consumer-row"
                [class.lumina__consumer-row--unavailable]="consumer.unavailable"
              >
                <span class="lumina__consumer-name">{{ consumer.displayName }}</span>
                <span class="lumina__consumer-value">{{ powerLabel(consumer) }}</span>
              </div>
            </div>
          </div>
        </div>
```

**3i — Dialog:** hinter dem Schalter-Dialog (nach dessen schließendem `</div>`,
vor dem Bestätigungsdialog) einfügen:

```html
  <!-- Verbraucher-Dialog (oeffnet sich ueber den Button in der Verbraucher-Kachel) -->
  <div
    *ngIf="consumerDialogOpen"
    class="lumina__dialog-backdrop"
    (click)="closeConsumerDialog()"
  >
    <div
      class="lumina__dialog"
      role="dialog"
      aria-modal="true"
      aria-label="Alle Verbraucher"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <h2 class="lumina__dialog-title">Alle Verbraucher</h2>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="closeConsumerDialog()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>
      <div class="lumina__dialog-body">
        <p *ngIf="allConsumers.length === 0" class="lumina__consumer-empty lumina__consumer-empty--dialog">
          Keine Verbraucher
        </p>
        <div
          *ngFor="let consumer of allConsumers"
          class="lumina__consumer-row lumina__consumer-row--dialog"
          [class.lumina__consumer-row--unavailable]="consumer.unavailable"
        >
          <span class="lumina__consumer-name">{{ consumer.displayName }}</span>
          <span class="lumina__consumer-value">{{ powerLabel(consumer) }}</span>
        </div>
      </div>
    </div>
  </div>
```

**3j — Doku-Kommentar der Komponente aktualisieren:** im Klassen-JSDoc den Satz
über „Raeume ... statische Platzhalter" anpassen, z. B.:

```typescript
 * Echte Daten: Uhr, Wetter (WeatherService), Live-Energie (EnergyLiveService),
 * Klima, Schalter, Verbraucher, Modi, Müllabfuhr und Türschloss.
 * Szenen und Intelligence-Hinweise sind aktuell statische Platzhalter.
```

- [ ] **Step 4: Tests ausführen — alle grün**

Run (Bash):
```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -10
```
Expected: alle Specs grün (auch die bestehenden Schalter-/Modi-/Nuki-Specs)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts \
        frontend/src/app/pages/dashboard/dashboard.component.html \
        frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(verbraucher): Schlafzimmer-Platzhalter durch Verbraucher-Kachel ersetzt"
```

---

## Task 6: Frontend — Styles für Kachel und Dialog

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

Muster: die `lumina__climate-*`-Regeln (Zeilen ~379-436). Die Kachel ist dunkel,
der Dialog hell (vgl. Kommentar bei `lumina__switch-error--dialog`).

- [ ] **Step 1: Styles ergänzen** (nach dem `lumina__climate-*`-Block einfügen):

```scss
.lumina__consumer-rows {
  display: flex;
  flex-direction: column;
  margin-top: 14px;
}

.lumina__consumer-empty {
  margin: 6px 0 0;
  font-size: 14px;
  color: rgba(192, 198, 214, 0.5);
}

.lumina__consumer-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 11px 0;
  border-top: 1px solid rgba(255, 255, 255, 0.06);

  &--unavailable {
    opacity: 0.45;
  }
}

.lumina__consumer-name {
  font-size: 15px;
  color: rgba(192, 198, 214, 0.85);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lumina__consumer-value {
  margin-left: auto;
  font-family: 'Space Grotesk', 'Geist', sans-serif;
  font-size: 18px;
  font-weight: 600;
  color: var(--on-surface);
  white-space: nowrap;
}

// Der Dialog ist hell: dunkle Texte statt der Kachel-Farben.
.lumina__consumer-row--dialog {
  border-top-color: rgba(15, 23, 42, 0.1);

  .lumina__consumer-name {
    color: rgba(15, 23, 42, 0.75);
  }

  .lumina__consumer-value {
    color: #0f172a;
  }
}

.lumina__consumer-empty--dialog {
  color: rgba(15, 23, 42, 0.55);
}
```

Die Trennlinien-Logik (erste Zeile ohne Linie) direkt mitliefern — sie gilt für
Kachel und Dialog gleichermaßen (im Dialog ist die erste Zeile das erste
`.lumina__consumer-row`-Element im Dialog-Body):

```scss
.lumina__consumer-row:first-of-type {
  border-top: none;
}
```

- [ ] **Step 2: Produktions-Build**

Run (Bash):
```bash
cd frontend && npx ng build --configuration production 2>&1 | tail -5
```
Expected: Build erfolgreich (SCSS-Budget-Warnungen der Datei sind vorbestehend, Fehler nicht)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.scss
git commit -m "feat(verbraucher): Styles fuer Verbraucher-Kachel und -Dialog"
```

---

## Task 7: Gesamtverifikation

Keine Doku-Änderungen nötig (CLAUDE.md beschreibt keine Dashboard-Kacheln im
Detail; die Spec unter `docs/superpowers/specs/` dokumentiert das Feature).

- [ ] **Step 1: Backend-Tests gesamt (bekannte DB-Fehler ignorieren)**

Run (Bash):
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd backend && mvn test -q 2>&1 | tail -30
```
Expected: Nur `HouseholdManagerApplicationTests.contextLoads` und
`HealthControllerTest` schlagen fehl („Access denied for user 'root'") — das ist
vorbestehend. Alle anderen Tests grün, insbesondere `PowerConsumerQueryServiceTest`.

- [ ] **Step 2: Frontend-Tests gesamt**

Run (Bash):
```bash
cd frontend && npx ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -10
```
Expected: alle Specs grün

- [ ] **Step 3: Manuelle Sichtprüfung (optional, wenn Backend + DB lokal laufen)**

```bash
curl -s http://localhost:8080/api/v1/power-consumers | head -c 500
```
Expected: JSON-Array mit `entityId`, `displayName`, `powerWatts`, `unavailable`
(leer `[]`, wenn gerade keine Power-Sensoren gemeldet sind — auch das ist gültig).

- [ ] **Step 4: Abschluss-Commit (falls Doku geändert) und Branch-Abschluss**

Danach superpowers:finishing-a-development-branch verwenden (Merge/PR-Entscheidung
liegt beim Nutzer).
