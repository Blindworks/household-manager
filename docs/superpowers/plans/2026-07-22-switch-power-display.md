# Watt-Anzeige in der Schalter-Kachel — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Steckdosen mit Verbrauchsmessung zeigen ihre aktuelle Leistung (Watt) in der Schalter-Zeile von Dashboard-Kachel und Bestätigungsdialog an.

**Architecture:** Die Schalter-API (`GET /api/v1/switches`) reichert jede `SwitchResponse` um ein Feld `powerWatts` an. Der `SwitchQueryService` verknüpft Schalter und Power-Sensor über die bestehende entityId-Konvention (`sensor.<source>_<slug(sourceRef)>_power`) mit einem einzigen `findByEntityIdIn`-Query; der `SwitchResponseMapper` filtert (nur „on", Sensor frisch < 5 min, numerisch, nicht `unavailable`). Das Frontend rendert den Wert in der gemeinsamen `switch-list`-Komponente.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / JUnit+Mockito+AssertJ (Backend); Angular 19 standalone / Karma+Jasmine (Frontend).

**Spec:** `docs/superpowers/specs/2026-07-22-switch-power-display-design.md`

**Umgebungshinweise:**
- Maven braucht JDK 21: vor jedem `mvn` im Bash-Tool `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` setzen (Standard-JAVA_HOME der Maschine ist JDK 17). Maven liegt auf dem PATH, es gibt keinen `mvnw`-Wrapper. Immer aus `backend/` heraus ausführen.
- Die lokalen Integrationstests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen auf dieser Maschine mit „Access denied for user 'root'@'localhost'" fehl (Test-DB nicht erreichbar). Das ist vorbestehend und zu ignorieren.
- Bekannte, akzeptierte Einschränkung: Die Antwort von `POST /v1/switches/{id}/toggle` enthält kein `powerWatts` (bleibt `null`); nach einem Toggle verschwindet die Anzeige bis zum nächsten Kachel-Refresh (max. 30 s). Der Messwert unmittelbar nach dem Schalten wäre ohnehin veraltet.

---

### Task 1: `SwitchResponse.powerWatts` + Anreicherung im `SwitchResponseMapper`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/dto/SwitchResponse.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/SwitchResponseMapperTest.java`

- [ ] **Step 1: Failing Tests schreiben**

In `SwitchResponseMapperTest` (bestehende Klasse) einen Builder-Helfer und sechs Tests ergänzen. Der bestehende Helfer `entity()` bleibt unverändert.

```java
    private EntityState.EntityStateBuilder powerSensor() {
        return EntityState.builder()
                .entityId("sensor.kasa_abc_power")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Stehlampe Leistung")
                .state("1240.5")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now());
    }

    @Test
    void liefert_die_leistung_eines_frischen_power_sensors() {
        SwitchResponse response = mapper.toResponse(entity().build(), null, powerSensor().build());

        assertThat(response.powerWatts()).isEqualTo(1240.5);
    }

    @Test
    void ohne_power_sensor_bleibt_die_leistung_leer() {
        SwitchResponse response = mapper.toResponse(entity().build(), null);

        assertThat(response.powerWatts()).isNull();
    }

    @Test
    void ausgeschaltete_schalter_haben_keine_leistungsanzeige() {
        SwitchResponse response = mapper.toResponse(entity().state("off").build(), null, powerSensor().build());

        assertThat(response.powerWatts()).isNull();
    }

    @Test
    void veraltete_sensorwerte_werden_verworfen() {
        EntityState stale = powerSensor().lastUpdated(LocalDateTime.now().minusMinutes(10)).build();

        SwitchResponse response = mapper.toResponse(entity().build(), null, stale);

        assertThat(response.powerWatts()).isNull();
    }

    @Test
    void nicht_verfuegbare_sensoren_werden_verworfen() {
        SwitchResponse response = mapper.toResponse(entity().build(), null, powerSensor().state("unavailable").build());

        assertThat(response.powerWatts()).isNull();
    }

    @Test
    void nicht_numerische_sensorwerte_werden_verworfen() {
        SwitchResponse response = mapper.toResponse(entity().build(), null, powerSensor().state("unknown").build());

        assertThat(response.powerWatts()).isNull();
    }
```

- [ ] **Step 2: Tests laufen lassen — sie müssen fehlschlagen**

Bash-Tool, aus `backend/`:
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=SwitchResponseMapperTest
```
Erwartung: Kompilierfehler („method toResponse ... cannot be applied" / „cannot find symbol powerWatts").

- [ ] **Step 3: Implementierung**

`SwitchResponse.java` — Feld `powerWatts` ergänzen (nach `confirmRequired`):

```java
@Builder
public record SwitchResponse(
        String entityId,
        String domain,
        String source,
        String displayName,
        String state,
        boolean available,
        String icon,
        boolean confirmRequired,
        /** Aktuelle Leistung in Watt; null wenn keine (frische) Messung vorliegt. */
        Double powerWatts,
        long toggleCount,
        LocalDateTime lastToggledAt
) {
}
```

`SwitchResponseMapper.java` — Überladung + Filterlogik. Die bestehende 2-Arg-Methode delegiert mit `null`-Sensor, damit `SwitchCommandService` unverändert kompiliert:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Bildet eine schaltbare {@link EntityState} zusammen mit ihrem
 * {@link EntityUsage}-Zähler auf die API-{@link SwitchResponse} ab.
 */
@Component
@RequiredArgsConstructor
public class SwitchResponseMapper {

    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String STATE_ON = "on";
    private static final String DEFAULT_ICON = "toggle_on";
    private static final String ATTR_ICON = "icon";
    /** Ältere Sensorwerte gelten als veraltet (Polling-Ausfall) und werden nicht angezeigt. */
    private static final Duration POWER_MAX_AGE = Duration.ofMinutes(5);

    private final EntityStateResponseMapper entityStateResponseMapper;

    /** @param usage darf null sein (Entität wurde noch nie geschaltet) */
    public SwitchResponse toResponse(EntityState entity, EntityUsage usage) {
        return toResponse(entity, usage, null);
    }

    /** @param powerSensor Power-Sensor gleicher Quelle; darf null sein */
    public SwitchResponse toResponse(EntityState entity, EntityUsage usage, EntityState powerSensor) {
        return SwitchResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .displayName(entityStateResponseMapper.displayName(entity))
                .state(entity.getState())
                .available(!STATE_UNAVAILABLE.equals(entity.getState()))
                .icon(icon(entity))
                .confirmRequired(entity.isConfirmRequired())
                .powerWatts(powerWatts(entity, powerSensor))
                .toggleCount(usage != null ? usage.getToggleCount() : 0L)
                .lastToggledAt(usage != null ? usage.getLastToggledAt() : null)
                .build();
    }

    /** Leistung nur für eingeschaltete Schalter mit frischem, numerischem Sensorwert. */
    private Double powerWatts(EntityState entity, EntityState powerSensor) {
        if (powerSensor == null
                || !STATE_ON.equals(entity.getState())
                || STATE_UNAVAILABLE.equals(powerSensor.getState())
                || powerSensor.getLastUpdated() == null
                || powerSensor.getLastUpdated().isBefore(LocalDateTime.now().minus(POWER_MAX_AGE))) {
            return null;
        }
        try {
            return Double.parseDouble(powerSensor.getState());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String icon(EntityState entity) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON;
    }
}
```

- [ ] **Step 4: Tests laufen lassen — sie müssen bestehen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=SwitchResponseMapperTest
```
Erwartung: alle Tests PASS (6 neue + 6 bestehende).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/SwitchResponse.java backend/src/main/java/com/household/manager/entitystate/mapper/SwitchResponseMapper.java backend/src/test/java/com/household/manager/entitystate/mapper/SwitchResponseMapperTest.java
git commit -m "feat(switches): powerWatts in SwitchResponse mit Frische- und Zustandsfilter"
```

---

### Task 2: Sensor-Verknüpfung im `SwitchQueryService`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/repository/EntityStateRepository.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java`

- [ ] **Step 1: Failing Tests schreiben**

In `SwitchQueryServiceTest` zwei Tests ergänzen. Hinweis: Die bestehenden Tests stubben `findByEntityIdIn` nicht — Mockito liefert dafür standardmäßig eine leere Liste, sie bleiben also unverändert grün.

```java
    @Test
    void reichert_schalter_mit_der_leistung_ihres_power_sensors_an() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("abc", "Waschmaschine")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());
        EntityState sensor = EntityState.builder()
                .entityId("sensor.kasa_abc_power")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.KASA)
                .sourceRef("abc")
                .friendlyName("Waschmaschine Leistung")
                .state("875")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
        when(entityStateRepository.findByEntityIdIn(anyCollection())).thenReturn(List.of(sensor));

        assertThat(service.listSwitches(null).get(0).powerWatts()).isEqualTo(875.0);
    }

    @Test
    void ohne_power_sensor_bleibt_die_leistung_in_der_antwort_leer() {
        when(entityStateRepository.findByDomainInOrderByEntityIdAsc(any()))
                .thenReturn(List.of(device("a", "Stehlampe")));
        when(entityUsageService.usageFor(anyCollection())).thenReturn(Map.of());

        assertThat(service.listSwitches(null).get(0).powerWatts()).isNull();
    }
```

- [ ] **Step 2: Tests laufen lassen — sie müssen fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest=SwitchQueryServiceTest
```
Erwartung: Kompilierfehler „cannot find symbol: method findByEntityIdIn".

- [ ] **Step 3: Implementierung**

`EntityStateRepository.java` — abgeleitete Query-Methode ergänzen (nach `findByDomainInOrderByEntityIdAsc`):

```java
    List<EntityState> findByEntityIdIn(Collection<String> entityIds);
```

`SwitchQueryService.java` — Sensoren in einem Query laden und an den Mapper durchreichen. In `listSwitches(Integer, boolean)` nach dem Laden von `usage` ergänzen und den `map`-Aufruf anpassen:

```java
        Map<String, EntityUsage> usage = entityUsageService.usageFor(
                switchable.stream().map(EntityState::getEntityId).toList());

        Map<String, EntityState> powerSensors = powerSensorsBySwitchId(switchable);

        record Ranked(SwitchResponse response, int rank) {
        }
        List<SwitchResponse> switches = switchable.stream()
                .map(entity -> new Ranked(
                        switchResponseMapper.toResponse(entity, usage.get(entity.getEntityId()),
                                powerSensors.get(entity.getEntityId())),
                        tileRank(entity, rules)))
                .sorted(Comparator.comparingInt(Ranked::rank)
                        .thenComparing(Ranked::response, byUsage()))
                .map(Ranked::response)
                .toList();
```

Neue private Methode (z. B. nach `listSwitches`):

```java
    /**
     * Lädt zu jedem Schalter den Power-Sensor gleicher Quelle über die
     * entityId-Konvention {@code sensor.<source>_<slug(ref)>_power} — ein Query für alle.
     */
    private Map<String, EntityState> powerSensorsBySwitchId(List<EntityState> switches) {
        if (switches.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sensorIdBySwitchId = new HashMap<>();
        for (EntityState sw : switches) {
            sensorIdBySwitchId.put(sw.getEntityId(),
                    EntityIds.build(EntityDomain.SENSOR, sw.getSource(), sw.getSourceRef(), "power"));
        }
        Map<String, EntityState> sensorsById = entityStateRepository
                .findByEntityIdIn(sensorIdBySwitchId.values()).stream()
                .collect(Collectors.toMap(EntityState::getEntityId, Function.identity()));
        Map<String, EntityState> bySwitchId = new HashMap<>();
        sensorIdBySwitchId.forEach((switchId, sensorId) -> {
            EntityState sensor = sensorsById.get(sensorId);
            if (sensor != null) {
                bySwitchId.put(switchId, sensor);
            }
        });
        return bySwitchId;
    }
```

Zusätzliche Imports in `SwitchQueryService.java` (Klassen `EntityIds` und `EntityDomain` liegen im selben Package, brauchen keinen Import):

```java
import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
```

- [ ] **Step 4: Tests laufen lassen — sie müssen bestehen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test -Dtest='SwitchQueryServiceTest,SwitchResponseMapperTest,SwitchCommandServiceTest,SwitchControllerTest'
```
Erwartung: alle PASS (inkl. der unveränderten Bestandstests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/repository/EntityStateRepository.java backend/src/main/java/com/household/manager/entitystate/SwitchQueryService.java backend/src/test/java/com/household/manager/entitystate/SwitchQueryServiceTest.java
git commit -m "feat(switches): Power-Sensoren per entityId-Konvention an Schalter geknuepft"
```

---

### Task 3: Frontend — Watt-Anzeige in der `switch-list`

**Files:**
- Modify: `frontend/src/app/models/switch.model.ts`
- Modify: `frontend/src/app/components/switch-list/switch-list.component.ts`
- Modify: `frontend/src/app/components/switch-list/switch-list.component.html`
- Modify: `frontend/src/app/components/switch-list/switch-list.component.scss`
- Test: `frontend/src/app/components/switch-list/switch-list.component.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

In `switch-list.component.spec.ts` drei Tests ergänzen (der `entity()`-Helfer braucht keine Änderung, `powerWatts` ist optional):

```ts
  it('zeigt die leistung eines eingeschalteten schalters an', () => {
    const fixture = render([entity({ powerWatts: 1240.5 })]);

    const power = (fixture.nativeElement as HTMLElement).querySelector('.switch-list__power');
    expect(power?.textContent).toContain('1.240 W');
  });

  it('zeigt ohne messwert keine leistung an', () => {
    const fixture = render([entity()]);

    expect((fixture.nativeElement as HTMLElement).querySelector('.switch-list__power')).toBeNull();
  });

  it('zeigt bei ausgeschaltetem schalter keine leistung an', () => {
    const fixture = render([entity({ state: 'off', powerWatts: 3 })]);

    expect((fixture.nativeElement as HTMLElement).querySelector('.switch-list__power')).toBeNull();
  });
```

- [ ] **Step 2: Tests laufen lassen — sie müssen fehlschlagen**

Aus `frontend/`:
```bash
npm test -- --watch=false --browsers=ChromeHeadless
```
Erwartung: TypeScript-Fehler „'powerWatts' does not exist in type 'Partial<SwitchEntity>'" (Kompilierfehler zählt als Fail).

- [ ] **Step 3: Implementierung**

`switch.model.ts` — Feld ergänzen (nach `confirmRequired`):

```ts
  /** Erfordert im Dashboard eine Bestätigung vor dem Schalten. */
  confirmRequired: boolean;
  /** Aktuelle Leistung in Watt; null/fehlend wenn keine frische Messung vorliegt. */
  powerWatts?: number | null;
  toggleCount: number;
```

`switch-list.component.ts` — Methode ergänzen (nach `stateLabel`):

```ts
  /** Formatierte Leistungsanzeige, z. B. "1.240 W"; null wenn nichts anzuzeigen ist. */
  powerLabel(entity: SwitchEntity): string | null {
    if (!entity.available || !this.isOn(entity) || entity.powerWatts == null) {
      return null;
    }
    return `${Math.round(entity.powerWatts).toLocaleString('de-DE')} W`;
  }
```

`switch-list.component.html` — neues Span vor dem Zustands-Label:

```html
    <span class="switch-list__name">{{ item.displayName }}</span>
    <span *ngIf="powerLabel(item)" class="switch-list__power">{{ powerLabel(item) }}</span>
    <span class="switch-list__state">{{ stateLabel(item) }}</span>
```

`switch-list.component.scss` — Stil nach dem `.switch-list__state`-Block ergänzen:

```scss
.switch-list__power {
  flex: none;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--secondary, #53e16f);
  opacity: 0.85;
}
```

Und im `.switch-list--dialog`-Block (heller Hintergrund, dunkleres Grün wie das On-Icon des Dialogs) — innerhalb des bestehenden `.switch-list--dialog { ... }` ergänzen:

```scss
  .switch-list__power {
    color: #15803d;
  }
```

- [ ] **Step 4: Tests laufen lassen — sie müssen bestehen**

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```
Erwartung: alle Frontend-Tests PASS (3 neue + Bestand).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/models/switch.model.ts frontend/src/app/components/switch-list/switch-list.component.ts frontend/src/app/components/switch-list/switch-list.component.html frontend/src/app/components/switch-list/switch-list.component.scss frontend/src/app/components/switch-list/switch-list.component.spec.ts
git commit -m "feat(dashboard): Watt-Anzeige in den Schalter-Zeilen"
```

---

### Task 4: Gesamtverifikation

**Files:** keine neuen Änderungen — nur Verifikation.

- [ ] **Step 1: Komplette Backend-Testsuite**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn test
```
Erwartung: alles PASS außer den zwei bekannten, vorbestehenden DB-Fehlschlägen (`HouseholdManagerApplicationTests.contextLoads`, `HealthControllerTest` — „Access denied for user 'root'@'localhost'"). Andere Fehlschläge sind echte Regressionen und müssen behoben werden.

- [ ] **Step 2: Komplette Frontend-Testsuite** (falls in Task 3 nicht schon die volle Suite lief)

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```
Erwartung: alle PASS.

- [ ] **Step 3: Abschluss**

Kein eigener Commit nötig (Task 1–3 haben bereits committet). Bei Regressionen: Fix + Commit im jeweiligen Bereich.
