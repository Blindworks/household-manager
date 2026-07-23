# Verbrauchsgraph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Klick auf einen Verbraucher in der Dashboard-Kachel öffnet einen Dialog mit dem Leistungsverlauf (Watt über Zeit), umschaltbar zwischen 24 Stunden, 7 Tagen und 30 Tagen.

**Architecture:** Eine neue Tabelle `entity_power_history` sammelt Messwerte. Befüllt wird sie von einem `@EventListener` auf `EntityStateChangedEvent` — dem Event, das die einzige Schreibstelle der Entity-State-Schicht nach Commit publiziert; die Integrationen bleiben dadurch unverändert. Ein Aggregations-Job verdichtet alte Werte und löscht nach 30 Tagen. Ein neuer Endpoint unter `/v1/power-consumers/{entityId}/history` liefert die Zeitreihe, das Dashboard zeigt sie als ECharts-Linie in einem Dialog.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase / JUnit 5 + Mockito + AssertJ (Backend), Angular 19 standalone / ngx-echarts / RxJS / Karma+Jasmine (Frontend).

**Spec:** `docs/superpowers/specs/2026-07-23-verbrauchsgraph-design.md`

**Wichtig — Umgebung (diese Maschine):**
- Vor jedem Maven-Aufruf: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` (Bash). Das Default-JAVA_HOME zeigt auf JDK 17, `mvn` bricht sonst ab. Aus `backend/` heraus aufrufen, es gibt kein `mvnw`.
- Die Integrationstests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` (3 Fehler) scheitern lokal an der fehlenden Test-Datenbank („Access denied for user 'root'"). Vorbestehend — beim Bewerten eigener Änderungen ignorieren, deshalb gezielt mit `-Dtest=<Klasse>` arbeiten.
- Frontend-Tests: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless`. Vier Specs schlagen vorbestehend fehl (`AppComponent`, `HeroComponent`, 2× `HeaderComponent` — fehlender `ActivatedRoute`-Provider). Erwartete Bilanz **vor** diesem Plan: 182 Specs, 178 grün.
- **Nicht** `cd` ohne absoluten Pfad im Bash-Tool: das Arbeitsverzeichnis bleibt zwischen Aufrufen bestehen.

**Dateiübersicht:**

| Datei | Verantwortung |
| --- | --- |
| `db/changelog/changes/20260723-0038-create-entity-power-history-table.xml` | Schema der Historie-Tabelle |
| `model/entity/EntityPowerHistory.java` | JPA-Entity eines Messpunkts |
| `repository/EntityPowerHistoryRepository.java` | Datenzugriff (muss in `repository` liegen — JpaConfig scannt nur dort) |
| `entitystate/PowerHistoryRecorder.java` | Schreibt Messpunkte aus dem State-Event |
| `entitystate/PowerHistoryAggregationJob.java` | Verdichtung + Aufbewahrung |
| `entitystate/PowerRange.java` | Zeitraum-Enum (1/7/30 Tage) |
| `entitystate/PowerHistoryService.java` | Baut die Zeitreihe für die API |
| `dto/PowerHistoryResponse.java` | API-Antwort |
| `controller/PowerConsumerController.java` | Neuer History-Endpoint |
| `entitystate/PowerConsumerQueryService.java` | Neue Methode `findConsumer` — hält die Definition „was ist ein Verbraucher" an einer Stelle |
| `frontend/models/power-consumer.model.ts` | Typen für Verlauf und Zeitraum |
| `frontend/services/power-consumer.service.ts` | `getHistory` |
| `frontend/pages/dashboard/dashboard.component.*` | Klickbare Zeilen + Graph-Dialog |

---

## Task 0: Feature-Branch anlegen

Das Repository steht auf `main` (die Verbraucher-Kachel ist bereits gemergt).

- [ ] **Step 1: Branch anlegen**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git checkout -b feature/verbrauchsgraph
```

Erwartung: `Switched to a new branch 'feature/verbrauchsgraph'`

---

## Task 1: Tabelle, Entity und Repository

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260723-0038-create-entity-power-history-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (Ende, vor `</databaseChangeLog>`)
- Create: `backend/src/main/java/com/household/manager/model/entity/EntityPowerHistory.java`
- Create: `backend/src/main/java/com/household/manager/repository/EntityPowerHistoryRepository.java`

- [ ] **Step 1: Changeset anlegen**

Datei `20260723-0038-create-entity-power-history-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260723-0038-create-entity-power-history-table" author="household-manager">
        <preConditions onFail="MARK_RAN">
            <not>
                <tableExists tableName="entity_power_history"/>
            </not>
        </preConditions>
        <createTable tableName="entity_power_history">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="entity_id" type="VARCHAR(150)">
                <constraints nullable="false"/>
            </column>
            <column name="measured_at" type="TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <!-- NULL = Sensor war zu diesem Zeitpunkt nicht erreichbar (bewusste Luecke im Graphen). -->
            <column name="power_watts" type="DOUBLE"/>
        </createTable>
        <createIndex tableName="entity_power_history" indexName="idx_power_history_entity_time">
            <column name="entity_id"/>
            <column name="measured_at"/>
        </createIndex>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Im Master-Changelog einbinden**

In `db.changelog-master.xml` direkt vor `</databaseChangeLog>` einfügen:

```xml
    <!-- Verbrauchshistorie der Power-Sensoren -->
    <include file="db/changelog/changes/20260723-0038-create-entity-power-history-table.xml"/>

```

- [ ] **Step 3: Entity anlegen**

```java
package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ein Messpunkt der Verbrauchshistorie eines Power-Sensors.
 * Gefuellt vom PowerHistoryRecorder, verdichtet vom PowerHistoryAggregationJob.
 */
@Entity
@Table(name = "entity_power_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityPowerHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Entity-ID des Power-Sensors, z. B. "sensor.meross_<uuid>_power". */
    @Column(name = "entity_id", nullable = false, length = 150)
    private String entityId;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    /** Leistung in Watt; null = Sensor war nicht erreichbar (Luecke im Graphen). */
    @Column(name = "power_watts")
    private Double powerWatts;
}
```

- [ ] **Step 4: Repository anlegen**

```java
package com.household.manager.repository;

import com.household.manager.model.entity.EntityPowerHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EntityPowerHistoryRepository extends JpaRepository<EntityPowerHistory, Long> {

    List<EntityPowerHistory> findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
            String entityId, LocalDateTime from, LocalDateTime to);

    /** Fenster fuer die Verdichtung: nur der noch unverdichtete Bereich. */
    List<EntityPowerHistory> findByMeasuredAtBetween(LocalDateTime from, LocalDateTime to);

    void deleteAllByIdIn(List<Long> ids);

    void deleteByMeasuredAtBefore(LocalDateTime cutoff);
}
```

- [ ] **Step 5: Kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn compile -q
```
Erwartung: BUILD SUCCESS (keine Ausgabe bei `-q` bedeutet Erfolg)

- [ ] **Step 6: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/EntityPowerHistory.java backend/src/main/java/com/household/manager/repository/EntityPowerHistoryRepository.java
git commit -m "feat(verbrauchsgraph): Tabelle, Entity und Repository fuer die Verbrauchshistorie"
```

---

## Task 2: PowerHistoryRecorder (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/PowerHistoryRecorder.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/PowerHistoryRecorderTest.java`

Muster: `EntityStateLoggingListener` (schlanker `@EventListener`-Component).
`EntityStateChangedEvent` liefert `entityId`, `oldState`, `newState`, `attributes`
(Map) und `timestamp` — der Filter auf `deviceClass` funktioniert also direkt am
Event, ohne die Entität nachzuladen.

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerHistoryRecorderTest {

    private static final LocalDateTime MOMENT = LocalDateTime.of(2026, 7, 23, 10, 30);

    @Mock
    private EntityPowerHistoryRepository repository;

    private PowerHistoryRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new PowerHistoryRecorder(repository);
    }

    private EntityStateChangedEvent event(String state, Map<String, Object> attributes) {
        return new EntityStateChangedEvent(
                "sensor.meross_wm_power", "0", state, attributes, MOMENT);
    }

    private EntityPowerHistory captureSaved() {
        ArgumentCaptor<EntityPowerHistory> captor = ArgumentCaptor.forClass(EntityPowerHistory.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void schreibt_messpunkt_fuer_power_sensor() {
        recorder.onStateChanged(event("1250.5", Map.of("deviceClass", "power", "unit", "W")));

        EntityPowerHistory saved = captureSaved();
        assertThat(saved.getEntityId()).isEqualTo("sensor.meross_wm_power");
        assertThat(saved.getMeasuredAt()).isEqualTo(MOMENT);
        assertThat(saved.getPowerWatts()).isEqualTo(1250.5);
    }

    @Test
    void ignoriert_sensoren_ohne_device_class_power() {
        recorder.onStateChanged(event("21.5", Map.of("deviceClass", "temperature")));

        verify(repository, never()).save(any());
    }

    @Test
    void ignoriert_events_ohne_attribute() {
        recorder.onStateChanged(event("on", Map.of()));

        verify(repository, never()).save(any());
    }

    @Test
    void schreibt_luecke_wenn_der_sensor_nicht_erreichbar_ist() {
        recorder.onStateChanged(event("unavailable", Map.of("deviceClass", "power")));

        assertThat(captureSaved().getPowerWatts()).isNull();
    }

    @Test
    void ein_repository_fehler_schlaegt_nie_zur_integration_durch() {
        when(repository.save(any())).thenThrow(new RuntimeException("DB weg"));

        assertThatCode(() -> recorder.onStateChanged(
                event("42", Map.of("deviceClass", "power")))).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerHistoryRecorderTest -q
```
Erwartung: COMPILATION ERROR — „cannot find symbol: class PowerHistoryRecorder"

- [ ] **Step 3: Recorder implementieren**

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Schreibt jede Zustandsaenderung eines Power-Sensors in die Verbrauchshistorie.
 * <p>
 * Haengt am {@link EntityStateChangedEvent} statt an den einzelnen Integrationen:
 * so bekommt jede Quelle mit deviceClass "power" ohne eigenen Code eine Historie.
 * Das Event feuert nur bei Wertaenderung — ein konstant ausgeschaltetes Geraet
 * kostet daher nichts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PowerHistoryRecorder {

    private static final String DEVICE_CLASS_POWER = "power";

    private final EntityPowerHistoryRepository repository;

    @EventListener
    public void onStateChanged(EntityStateChangedEvent event) {
        if (!isPowerSensor(event.attributes())) {
            return;
        }
        try {
            repository.save(EntityPowerHistory.builder()
                    .entityId(event.entityId())
                    .measuredAt(event.timestamp())
                    .powerWatts(parseWatts(event.newState()))
                    .build());
        } catch (Exception ex) {
            // Bewusst geschluckt: der Recorder haengt am Schreibpfad der Integrationen,
            // ein Historie-Fehler darf das Polling nie brechen.
            log.warn("Failed to record power history for {}: {}", event.entityId(), ex.getMessage());
        }
    }

    private boolean isPowerSensor(Map<String, Object> attributes) {
        return attributes != null && DEVICE_CLASS_POWER.equals(attributes.get("deviceClass"));
    }

    /** Nicht-numerische Zustaende ("unavailable", "unknown") ergeben null = Luecke. */
    private Double parseWatts(String state) {
        if (state == null) {
            return null;
        }
        try {
            return Double.valueOf(state.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Test ausführen — grün**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerHistoryRecorderTest -q
```
Erwartung: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add backend/src/main/java/com/household/manager/entitystate/PowerHistoryRecorder.java backend/src/test/java/com/household/manager/entitystate/PowerHistoryRecorderTest.java
git commit -m "feat(verbrauchsgraph): Recorder schreibt Power-Sensor-Werte in die Historie"
```

---

## Task 3: PowerHistoryAggregationJob (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/PowerHistoryAggregationJob.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/PowerHistoryAggregationJobTest.java`

Vorbild `ShellyReadingAggregationJob`: gruppieren, alte Zeilen löschen, eine
verdichtete speichern. Abweichung: Wir laden nur das jeweils betroffene
**Zeitfenster** statt „alles vor dem Stichtag" — sonst zieht der Job jede Minute
die kompletten 30 Tage aus der Tabelle.

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerHistoryAggregationJobTest {

    @Mock
    private EntityPowerHistoryRepository repository;

    private PowerHistoryAggregationJob job;

    @BeforeEach
    void setUp() {
        job = new PowerHistoryAggregationJob(repository);
    }

    private EntityPowerHistory point(long id, LocalDateTime at, Double watts) {
        return EntityPowerHistory.builder()
                .id(id).entityId("sensor.meross_wm_power").measuredAt(at).powerWatts(watts).build();
    }

    private List<EntityPowerHistory> captureAllSaved() {
        ArgumentCaptor<EntityPowerHistory> captor = ArgumentCaptor.forClass(EntityPowerHistory.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void verdichtet_mehrere_werte_derselben_minute_zum_mittelwert() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), 100.0),
                        point(2L, minute.withSecond(35), 200.0)))
                .thenReturn(List.of());

        job.aggregate();

        EntityPowerHistory saved = captureAllSaved().get(0);
        assertThat(saved.getMeasuredAt()).isEqualTo(minute);
        assertThat(saved.getPowerWatts()).isEqualTo(150.0);
        verify(repository).deleteAllByIdIn(List.of(1L, 2L));
    }

    @Test
    void laesst_einzelwerte_unangetastet() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(point(1L, minute.withSecond(5), 100.0)))
                .thenReturn(List.of());

        job.aggregate();

        verify(repository, never()).save(any());
        verify(repository, never()).deleteAllByIdIn(anyList());
    }

    @Test
    void ein_bucket_nur_aus_luecken_bleibt_eine_luecke() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), null),
                        point(2L, minute.withSecond(35), null)))
                .thenReturn(List.of());

        job.aggregate();

        assertThat(captureAllSaved().get(0).getPowerWatts()).isNull();
    }

    @Test
    void luecken_verwaessern_den_mittelwert_nicht() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), 100.0),
                        point(2L, minute.withSecond(35), null)))
                .thenReturn(List.of());

        job.aggregate();

        assertThat(captureAllSaved().get(0).getPowerWatts()).isEqualTo(100.0);
    }

    @Test
    void trennt_geraete_voneinander() {
        LocalDateTime minute = LocalDateTime.of(2026, 7, 23, 10, 5);
        EntityPowerHistory other = EntityPowerHistory.builder()
                .id(3L).entityId("sensor.meross_tv_power").measuredAt(minute.withSecond(5))
                .powerWatts(50.0).build();
        when(repository.findByMeasuredAtBetween(any(), any()))
                .thenReturn(List.of(
                        point(1L, minute.withSecond(5), 100.0),
                        point(2L, minute.withSecond(35), 200.0),
                        other))
                .thenReturn(List.of());

        job.aggregate();

        // Nur die zwei Punkte der Waschmaschine bilden einen Bucket; das TV-Geraet bleibt allein.
        assertThat(captureAllSaved()).hasSize(1);
        verify(repository).deleteAllByIdIn(List.of(1L, 2L));
    }

    @Test
    void loescht_zeilen_aelter_als_die_aufbewahrungsfrist() {
        when(repository.findByMeasuredAtBetween(any(), any())).thenReturn(List.of());

        job.aggregate();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteByMeasuredAtBefore(captor.capture());
        assertThat(captor.getValue()).isBefore(LocalDateTime.now().minusDays(29));
    }
}
```

- [ ] **Step 2: Test ausführen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerHistoryAggregationJobTest -q
```
Erwartung: COMPILATION ERROR — „cannot find symbol: class PowerHistoryAggregationJob"

- [ ] **Step 3: Job implementieren**

```java
package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Haelt die Verbrauchshistorie klein: verdichtet aeltere Messpunkte und wirft
 * abgelaufene weg. Vorbild ist der ShellyReadingAggregationJob; anders als dort
 * laden wir nur das jeweils betroffene Zeitfenster statt aller alten Zeilen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PowerHistoryAggregationJob {

    /** Juenger als das bleibt in voller Aufloesung. */
    private static final int RAW_MINUTES = 10;
    /** Ab hier wird auf Stundenwerte verdichtet. */
    private static final int MINUTE_RESOLUTION_DAYS = 2;
    /** Aelteres wird geloescht (laengster anzeigbarer Zeitraum). */
    private static final int RETENTION_DAYS = 30;

    private final EntityPowerHistoryRepository repository;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void aggregate() {
        LocalDateTime now = LocalDateTime.now();

        int minutes = compact(now.minusDays(MINUTE_RESOLUTION_DAYS), now.minusMinutes(RAW_MINUTES),
                ChronoUnit.MINUTES);
        int hours = compact(now.minusDays(RETENTION_DAYS), now.minusDays(MINUTE_RESOLUTION_DAYS),
                ChronoUnit.HOURS);

        repository.deleteByMeasuredAtBefore(now.minusDays(RETENTION_DAYS));

        if (minutes > 0 || hours > 0) {
            log.debug("Verbrauchshistorie verdichtet: {} Minuten-, {} Stunden-Buckets", minutes, hours);
        }
    }

    /** Fasst alle Punkte eines (Entitaet, Zeitfenster)-Buckets zu einem Mittelwert zusammen. */
    private int compact(LocalDateTime from, LocalDateTime to, ChronoUnit unit) {
        List<EntityPowerHistory> points = repository.findByMeasuredAtBetween(from, to);
        if (points.isEmpty()) {
            return 0;
        }

        Map<String, List<EntityPowerHistory>> buckets = points.stream()
                .collect(Collectors.groupingBy(point ->
                        point.getEntityId() + "|" + point.getMeasuredAt().truncatedTo(unit)));

        int compacted = 0;
        for (List<EntityPowerHistory> bucket : buckets.values()) {
            if (bucket.size() <= 1) {
                continue;
            }
            repository.deleteAllByIdIn(bucket.stream().map(EntityPowerHistory::getId).toList());
            repository.save(EntityPowerHistory.builder()
                    .entityId(bucket.get(0).getEntityId())
                    .measuredAt(bucket.get(0).getMeasuredAt().truncatedTo(unit))
                    .powerWatts(averageWatts(bucket))
                    .build());
            compacted++;
        }
        return compacted;
    }

    /**
     * Mittelwert der echten Messwerte. Enthaelt ein Bucket ausschliesslich Luecken,
     * bleibt es eine Luecke — sonst wuerden wir eine Null-Leistung erfinden, die
     * so nie gemessen wurde.
     */
    private Double averageWatts(List<EntityPowerHistory> bucket) {
        OptionalDouble average = bucket.stream()
                .map(EntityPowerHistory::getPowerWatts)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }
}
```

- [ ] **Step 4: Test ausführen — grün**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerHistoryAggregationJobTest -q
```
Erwartung: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add backend/src/main/java/com/household/manager/entitystate/PowerHistoryAggregationJob.java backend/src/test/java/com/household/manager/entitystate/PowerHistoryAggregationJobTest.java
git commit -m "feat(verbrauchsgraph): Aggregations-Job verdichtet und begrenzt die Historie"
```

---

## Task 4: PowerRange und `findConsumer` im Query-Service

**Files:**
- Create: `backend/src/main/java/com/household/manager/entitystate/PowerRange.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/PowerConsumerQueryService.java`
- Modify: `backend/src/test/java/com/household/manager/entitystate/PowerConsumerQueryServiceTest.java`

`findConsumer` liegt bewusst im Query-Service: Damit bleibt die Definition
„welche Entität ist ein Verbraucher" an genau einer Stelle, statt in Kachel-Liste
und Historie-Endpoint auseinanderzulaufen.

- [ ] **Step 1: PowerRange anlegen**

Vorbild `com.household.manager.service.TemperatureRange`:

```java
package com.household.manager.entitystate;

import lombok.Getter;

/** Auswaehlbarer Zeitraum des Verbrauchsgraphen. */
@Getter
public enum PowerRange {
    DAY(1),
    WEEK(7),
    MONTH(30);

    private final int days;

    PowerRange(int days) {
        this.days = days;
    }
}
```

- [ ] **Step 2: Failing Tests für `findConsumer` ergänzen**

Ans Ende von `PowerConsumerQueryServiceTest` (vor der schließenden Klammer):

```java
    @Test
    void findet_einen_verbraucher_ueber_seine_entity_id() {
        EntityState entity = sensor(EntitySource.MEROSS, "wm", "Waschmaschine", "10", POWER_ATTRIBUTES);
        when(entityStateRepository.findByEntityId("sensor.meross_wm_power"))
                .thenReturn(java.util.Optional.of(entity));

        assertThat(service.findConsumer("sensor.meross_wm_power")).containsSame(entity);
    }

    @Test
    void findet_keinen_verbraucher_fuer_einen_temperatursensor() {
        when(entityStateRepository.findByEntityId("sensor.zigbee_wz_temperature"))
                .thenReturn(java.util.Optional.of(
                        sensor(EntitySource.ZIGBEE, "wz", "Wohnzimmer", "21.5", TEMPERATURE_ATTRIBUTES)));

        assertThat(service.findConsumer("sensor.zigbee_wz_temperature")).isEmpty();
    }

    @Test
    void findet_keinen_verbraucher_fuer_eine_haus_bilanz_quelle() {
        when(entityStateRepository.findByEntityId("sensor.tasmota_main_power"))
                .thenReturn(java.util.Optional.of(
                        sensor(EntitySource.TASMOTA, "main", "Hausverbrauch", "3400", POWER_ATTRIBUTES)));

        assertThat(service.findConsumer("sensor.tasmota_main_power")).isEmpty();
    }

    @Test
    void findet_keinen_verbraucher_fuer_eine_unbekannte_entity_id() {
        when(entityStateRepository.findByEntityId("sensor.gibt_es_nicht"))
                .thenReturn(java.util.Optional.empty());

        assertThat(service.findConsumer("sensor.gibt_es_nicht")).isEmpty();
    }
```

Hinweis: Die Hilfsmethode `sensor(...)` und die Konstanten `POWER_ATTRIBUTES` /
`TEMPERATURE_ATTRIBUTES` existieren in dieser Testklasse bereits.

- [ ] **Step 3: Test ausführen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerConsumerQueryServiceTest -q
```
Erwartung: COMPILATION ERROR — „cannot find symbol: method findConsumer"

- [ ] **Step 4: `findConsumer` implementieren**

In `PowerConsumerQueryService` nach `listConsumers` einfügen:

```java
    /**
     * Sucht eine Entitaet, die als Verbraucher gilt (Power-Sensor, keine
     * Haus-Bilanz-/Erzeuger-Quelle). Einzige Definitionsstelle — die Kachel-Liste
     * und der Historie-Endpoint fragen beide hier.
     *
     * @return die Entitaet, oder leer wenn unbekannt oder kein Verbraucher
     */
    @Transactional(readOnly = true)
    public Optional<EntityState> findConsumer(String entityId) {
        return entityStateRepository.findByEntityId(entityId)
                .filter(entity -> !NON_CONSUMER_SOURCES.contains(entity.getSource()))
                .filter(this::isPowerSensor);
    }
```

Import ergänzen:

```java
import java.util.Optional;
```

- [ ] **Step 5: Test ausführen — grün**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerConsumerQueryServiceTest -q
```
Erwartung: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add backend/src/main/java/com/household/manager/entitystate/PowerRange.java backend/src/main/java/com/household/manager/entitystate/PowerConsumerQueryService.java backend/src/test/java/com/household/manager/entitystate/PowerConsumerQueryServiceTest.java
git commit -m "feat(verbrauchsgraph): PowerRange und Verbraucher-Lookup im Query-Service"
```

---

## Task 5: PowerHistoryService (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/PowerHistoryResponse.java`
- Create: `backend/src/main/java/com/household/manager/entitystate/PowerHistoryService.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/PowerHistoryServiceTest.java`

Das DTO nutzt den vorhandenen `com.household.manager.dto.TimeValue`
(`LocalDateTime time`, `BigDecimal value`, Lombok-`@Builder`) — kein zweiter
Punkt-Typ.

- [ ] **Step 1: DTO anlegen**

```java
package com.household.manager.dto;

import java.util.List;

/**
 * Leistungsverlauf eines Verbrauchers fuer den Graph-Dialog.
 * Punkte aufsteigend nach Zeit; ein null-Wert markiert eine Messluecke.
 */
public record PowerHistoryResponse(
        String entityId,
        String displayName,
        List<TimeValue> points
) {
}
```

- [ ] **Step 2: Failing Test schreiben**

```java
package com.household.manager.entitystate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.PowerHistoryResponse;
import com.household.manager.dto.TimeValue;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityPowerHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerHistoryServiceTest {

    @Mock
    private EntityPowerHistoryRepository historyRepository;
    @Mock
    private PowerConsumerQueryService consumerQueryService;

    private PowerHistoryService service;

    @BeforeEach
    void setUp() {
        service = new PowerHistoryService(historyRepository, consumerQueryService,
                new EntityStateResponseMapper(new ObjectMapper()));
    }

    private EntityState consumer() {
        return EntityState.builder()
                .entityId("sensor.meross_wm_power")
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.MEROSS)
                .sourceRef("wm")
                .friendlyName("Waschmaschine Leistung")
                .state("1200")
                .attributes("{\"deviceClass\":\"power\"}")
                .lastChanged(LocalDateTime.now())
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    private EntityPowerHistory point(LocalDateTime at, Double watts) {
        return EntityPowerHistory.builder()
                .entityId("sensor.meross_wm_power").measuredAt(at).powerWatts(watts).build();
    }

    @Test
    void liefert_die_punkte_des_verbrauchers() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 23, 10, 0);
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                eq("sensor.meross_wm_power"), any(), any()))
                .thenReturn(List.of(point(at, 1200.0)));

        PowerHistoryResponse response = service.getHistory("sensor.meross_wm_power", PowerRange.DAY)
                .orElseThrow();

        assertThat(response.entityId()).isEqualTo("sensor.meross_wm_power");
        assertThat(response.displayName()).isEqualTo("Waschmaschine Leistung");
        assertThat(response.points()).hasSize(1);
        TimeValue first = response.points().get(0);
        assertThat(first.getTime()).isEqualTo(at);
        assertThat(first.getValue()).isEqualByComparingTo("1200.0");
    }

    @Test
    void reicht_messluecken_als_null_weiter() {
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                any(), any(), any()))
                .thenReturn(List.of(point(LocalDateTime.of(2026, 7, 23, 10, 0), null)));

        PowerHistoryResponse response = service.getHistory("sensor.meross_wm_power", PowerRange.DAY)
                .orElseThrow();

        assertThat(response.points().get(0).getValue()).isNull();
    }

    @Test
    void fragt_den_zeitraum_passend_zum_range_ab() {
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                any(), any(), any())).thenReturn(List.of());

        service.getHistory("sensor.meross_wm_power", PowerRange.MONTH);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.Mockito.verify(historyRepository)
                .findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(any(), from.capture(), to.capture());
        assertThat(from.getValue()).isBefore(LocalDateTime.now().minusDays(29));
        assertThat(to.getValue()).isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void liefert_leer_wenn_die_entitaet_kein_verbraucher_ist() {
        when(consumerQueryService.findConsumer("sensor.zigbee_wz_temperature"))
                .thenReturn(Optional.empty());

        assertThat(service.getHistory("sensor.zigbee_wz_temperature", PowerRange.DAY)).isEmpty();
    }

    @Test
    void liefert_eine_leere_punktliste_wenn_noch_nichts_aufgezeichnet_wurde() {
        when(consumerQueryService.findConsumer("sensor.meross_wm_power"))
                .thenReturn(Optional.of(consumer()));
        when(historyRepository.findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                any(), any(), any())).thenReturn(List.of());

        assertThat(service.getHistory("sensor.meross_wm_power", PowerRange.DAY)
                .orElseThrow().points()).isEmpty();
    }
}
```

- [ ] **Step 3: Test ausführen — muss fehlschlagen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerHistoryServiceTest -q
```
Erwartung: COMPILATION ERROR — „cannot find symbol: class PowerHistoryService"

- [ ] **Step 4: Service implementieren**

```java
package com.household.manager.entitystate;

import com.household.manager.dto.PowerHistoryResponse;
import com.household.manager.dto.TimeValue;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Baut den Leistungsverlauf eines Verbrauchers fuer den Graph-Dialog.
 */
@Service
@RequiredArgsConstructor
public class PowerHistoryService {

    private final EntityPowerHistoryRepository historyRepository;
    private final PowerConsumerQueryService consumerQueryService;
    private final EntityStateResponseMapper entityStateResponseMapper;

    /**
     * @return der Verlauf, oder leer wenn die entityId unbekannt ist oder nicht
     *         zu einem Verbraucher gehoert (der Controller macht daraus ein 404)
     */
    @Transactional(readOnly = true)
    public Optional<PowerHistoryResponse> getHistory(String entityId, PowerRange range) {
        return consumerQueryService.findConsumer(entityId).map(entity -> {
            LocalDateTime now = LocalDateTime.now();
            List<TimeValue> points = historyRepository
                    .findByEntityIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(
                            entityId, now.minusDays(range.getDays()), now)
                    .stream()
                    .map(this::toPoint)
                    .toList();
            return new PowerHistoryResponse(
                    entity.getEntityId(),
                    entityStateResponseMapper.displayName(entity),
                    points);
        });
    }

    /** null-Leistung bleibt null: der Graph zeigt dort eine Luecke. */
    private TimeValue toPoint(EntityPowerHistory history) {
        return TimeValue.builder()
                .time(history.getMeasuredAt())
                .value(history.getPowerWatts() == null
                        ? null
                        : BigDecimal.valueOf(history.getPowerWatts()))
                .build();
    }
}
```

- [ ] **Step 5: Test ausführen — grün**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest=PowerHistoryServiceTest -q
```
Erwartung: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add backend/src/main/java/com/household/manager/dto/PowerHistoryResponse.java backend/src/main/java/com/household/manager/entitystate/PowerHistoryService.java backend/src/test/java/com/household/manager/entitystate/PowerHistoryServiceTest.java
git commit -m "feat(verbrauchsgraph): PowerHistoryService liefert die Zeitreihe"
```

---

## Task 6: History-Endpoint im Controller

**Files:**
- Modify: `backend/src/main/java/com/household/manager/controller/PowerConsumerController.java`

- [ ] **Step 1: Endpoint ergänzen**

Die Datei vollständig ersetzen durch:

```java
package com.household.manager.controller;

import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.dto.PowerHistoryResponse;
import com.household.manager.entitystate.PowerConsumerQueryService;
import com.household.manager.entitystate.PowerHistoryService;
import com.household.manager.entitystate.PowerRange;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API für die Verbraucher-Kachel (Stromverbraucher, größter zuerst)
 * und den Verbrauchsgraphen eines einzelnen Verbrauchers.
 */
@RestController
@RequestMapping("/v1/power-consumers")
@RequiredArgsConstructor
public class PowerConsumerController {

    private final PowerConsumerQueryService powerConsumerQueryService;
    private final PowerHistoryService powerHistoryService;

    /** @param limit optionale Obergrenze; ohne Angabe werden alle Verbraucher geliefert */
    @GetMapping
    public List<PowerConsumerResponse> getConsumers(
            @RequestParam(required = false) Integer limit) {
        return powerConsumerQueryService.listConsumers(limit);
    }

    /** @param range Zeitraum des Verlaufs; Standard 24 Stunden */
    @GetMapping("/{entityId}/history")
    public ResponseEntity<PowerHistoryResponse> getHistory(
            @PathVariable String entityId,
            @RequestParam(required = false, defaultValue = "DAY") PowerRange range) {
        return powerHistoryService.getHistory(entityId, range)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 2: Kompilieren und Backend-Tests des Bereichs**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test -Dtest='PowerConsumerQueryServiceTest,PowerHistoryServiceTest,PowerHistoryRecorderTest,PowerHistoryAggregationJobTest' -q
```
Erwartung: BUILD SUCCESS, alle vier Testklassen grün

- [ ] **Step 3: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add backend/src/main/java/com/household/manager/controller/PowerConsumerController.java
git commit -m "feat(verbrauchsgraph): GET /v1/power-consumers/{entityId}/history"
```

---

## Task 7: Frontend — Modelle und Service-Methode

**Files:**
- Modify: `frontend/src/app/models/power-consumer.model.ts`
- Modify: `frontend/src/app/services/power-consumer.service.ts`

- [ ] **Step 1: Modelle ergänzen**

Ans Ende von `power-consumer.model.ts` anhängen (die bestehende
`PowerConsumer`-Schnittstelle bleibt unverändert):

```typescript
/** Auswählbarer Zeitraum des Verbrauchsgraphen. */
export type PowerRange = 'DAY' | 'WEEK' | 'MONTH';

/** Ein Punkt des Leistungsverlaufs; value null = Messlücke. */
export interface PowerHistoryPoint {
  /** ISO-Zeitstempel. */
  time: string;
  value: number | null;
}

/** Leistungsverlauf eines Verbrauchers. */
export interface PowerHistory {
  entityId: string;
  displayName: string;
  points: PowerHistoryPoint[];
}
```

- [ ] **Step 2: Service-Methode ergänzen**

In `power-consumer.service.ts` den Import erweitern:

```typescript
import { PowerConsumer, PowerHistory, PowerRange } from '../models/power-consumer.model';
```

und nach `getConsumers` einfügen:

```typescript
  /** Leistungsverlauf eines Verbrauchers im gewählten Zeitraum. */
  getHistory(entityId: string, range: PowerRange): Observable<PowerHistory> {
    const params = new HttpParams().set('range', range);
    return this.http.get<PowerHistory>(`${this.baseUrl}/${entityId}/history`, { params }).pipe(
      catchError(this.handleError)
    );
  }
```

- [ ] **Step 3: Build prüfen**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager/frontend && npx ng build --configuration production 2>&1 | tail -5
```
Erwartung: Build erfolgreich

- [ ] **Step 4: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add frontend/src/app/models/power-consumer.model.ts frontend/src/app/services/power-consumer.service.ts
git commit -m "feat(verbrauchsgraph): Frontend-Modelle und getHistory"
```

---

## Task 8: Frontend — Graph-Dialog (TDD)

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`
- Test: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

Im bestehenden `describe('DashboardComponent (Verbraucher-Kachel)')`-Block den
Spy um `getHistory` erweitern — die Zeile

```typescript
    consumerServiceSpy = jasmine.createSpyObj('PowerConsumerService', ['getConsumers']);
```

ersetzen durch:

```typescript
    consumerServiceSpy = jasmine.createSpyObj('PowerConsumerService', ['getConsumers', 'getHistory']);
    consumerServiceSpy.getHistory.and.returnValue(of({
      entityId: 'sensor.meross_wm_power',
      displayName: 'Waschmaschine',
      points: [{ time: '2026-07-23T10:00:00', value: 1200 }]
    }));
```

Dann diese Specs ans Ende desselben `describe`-Blocks anhängen:

```typescript
  it('oeffnet den Graph-Dialog fuer den geklickten Verbraucher', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    expect(fixture.componentInstance.historyConsumer?.entityId).toBe('sensor.meross_wm_power');
    expect(consumerServiceSpy.getHistory).toHaveBeenCalledWith('sensor.meross_wm_power', 'DAY');

    discardPeriodicTasks();
  }));

  it('laedt beim Zeitraumwechsel neu', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();
    consumerServiceSpy.getHistory.calls.reset();

    fixture.componentInstance.setHistoryRange('WEEK');
    tick();

    expect(consumerServiceSpy.getHistory).toHaveBeenCalledWith('sensor.meross_wm_power', 'WEEK');
    expect(fixture.componentInstance.historyRange).toBe('WEEK');

    discardPeriodicTasks();
  }));

  it('laedt bei erneuter Wahl desselben Zeitraums nicht noch einmal', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();
    consumerServiceSpy.getHistory.calls.reset();

    fixture.componentInstance.setHistoryRange('DAY');
    tick();

    expect(consumerServiceSpy.getHistory).not.toHaveBeenCalled();

    discardPeriodicTasks();
  }));

  it('zeigt den Leerzustand, solange nichts aufgezeichnet wurde', fakeAsync(() => {
    consumerServiceSpy.getHistory.and.returnValue(of({
      entityId: 'sensor.meross_wm_power', displayName: 'Waschmaschine', points: []
    }));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    expect(fixture.componentInstance.historyEmpty).toBeTrue();

    discardPeriodicTasks();
  }));

  it('meldet einen Ladefehler im Dialog', fakeAsync(() => {
    consumerServiceSpy.getHistory.and.returnValue(throwError(() => new Error('kaputt')));
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    expect(fixture.componentInstance.historyError).toBe('Verlauf konnte nicht geladen werden.');

    discardPeriodicTasks();
  }));

  it('schliessen setzt den Dialog zurueck', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    fixture.componentInstance.closeHistoryDialog();

    expect(fixture.componentInstance.historyConsumer).toBeNull();
    expect(fixture.componentInstance.historyRange).toBe('DAY');

    discardPeriodicTasks();
  }));

  it('laesst den Verbraucher-Dialog offen, wenn der Graph darueber schliesst', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    fixture.componentInstance.openConsumerDialog();
    tick();
    fixture.componentInstance.openHistoryDialog(consumer());
    tick();

    fixture.componentInstance.closeHistoryDialog();

    expect(fixture.componentInstance.consumerDialogOpen).toBeTrue();

    discardPeriodicTasks();
  }));
```

- [ ] **Step 2: Tests ausführen — müssen fehlschlagen**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager/frontend && npx ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -20
```
Erwartung: FAIL — `openHistoryDialog`, `setHistoryRange`, `historyConsumer` existieren nicht

- [ ] **Step 3: Component erweitern**

**3a — Imports** oben in `dashboard.component.ts` ergänzen:

```typescript
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
```

Der bestehende Model-Import wird erweitert:

```typescript
import { PowerConsumer, PowerHistory, PowerRange } from '../../models/power-consumer.model';
```

**3b — ECharts registrieren**, direkt nach den Imports (vor dem `@Component`):

```typescript
echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);
```

**3c — Component-Dekorator:** `NgxEchartsDirective` in `imports` aufnehmen und
`providers` ergänzen:

```typescript
  imports: [CommonModule, RouterLink, EnergyFlowComponent, SwitchListComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
```

**3d — Felder** bei den übrigen Verbraucher-Feldern einfügen:

```typescript
  /** Verbraucher, dessen Verlauf gerade angezeigt wird (null = Dialog zu). */
  historyConsumer: PowerConsumer | null = null;
  /** Gewählter Zeitraum des Verlaufs. */
  historyRange: PowerRange = 'DAY';
  /** ECharts-Optionen des Verlaufs. */
  historyOptions: Record<string, unknown> | null = null;
  /** True, wenn für den Zeitraum noch keine Messpunkte vorliegen. */
  historyEmpty = false;
  historyError: string | null = null;

  /** Auswählbare Zeiträume des Verlaufs. */
  readonly historyRanges: { value: PowerRange; label: string }[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];
```

**3e — Escape-Handler:** als **allererste Anweisung** in `onEscape()` einfügen —
also noch vor `this.closeFlowDialog();`. Der frühe `return` ist wesentlich: Der
Graph liegt als oberster Dialog über allen anderen, ein Escape darf deshalb nur
ihn schließen und nicht gleichzeitig die Dialoge darunter:

```typescript
    if (this.historyConsumer) {
      this.closeHistoryDialog();
      return;
    }
```

**3f — Methoden** nach `closeConsumerDialog()` einfügen:

```typescript
  /** Öffnet den Verlaufs-Dialog für einen Verbraucher (immer mit 24-Stunden-Sicht). */
  openHistoryDialog(consumer: PowerConsumer): void {
    this.historyConsumer = consumer;
    this.historyRange = 'DAY';
    this.loadHistory();
  }

  closeHistoryDialog(): void {
    this.historyConsumer = null;
    this.historyRange = 'DAY';
    this.historyOptions = null;
    this.historyEmpty = false;
    this.historyError = null;
  }

  setHistoryRange(range: PowerRange): void {
    if (range === this.historyRange) {
      return;
    }
    this.historyRange = range;
    this.loadHistory();
  }

  private loadHistory(): void {
    const consumer = this.historyConsumer;
    if (!consumer) {
      return;
    }
    this.historyError = null;
    this.historyEmpty = false;
    this.powerConsumerService.getHistory(consumer.entityId, this.historyRange).subscribe({
      next: history => {
        // Ein zwischenzeitlich geschlossener oder gewechselter Dialog darf nicht überschrieben werden.
        if (this.historyConsumer?.entityId !== history.entityId) {
          return;
        }
        this.historyEmpty = history.points.length === 0;
        this.historyOptions = this.buildHistoryOptions(history);
      },
      error: () => {
        this.historyOptions = null;
        this.historyError = 'Verlauf konnte nicht geladen werden.';
      }
    });
  }

  /** Liniendiagramm des Leistungsverlaufs; null-Werte lassen die Linie bewusst abreißen. */
  private buildHistoryOptions(history: PowerHistory): Record<string, unknown> {
    return {
      grid: { left: 56, right: 16, top: 24, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: '#94a3b8', formatter: '{value} W' },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      },
      series: [
        {
          name: 'Leistung',
          type: 'line',
          smooth: true,
          showSymbol: false,
          connectNulls: false,
          data: history.points.map(point => [point.time, point.value]),
          lineStyle: { width: 2.5, color: '#f59e0b' },
          itemStyle: { color: '#f59e0b' },
          areaStyle: { color: 'rgba(245, 158, 11, 0.15)' }
        }
      ]
    };
  }
```

**3g — Template:** In `dashboard.component.html` die Verbraucherzeile **auf der
Kachel** klickbar machen. Den Block

```html
              <div
                *ngFor="let consumer of topConsumers"
                class="lumina__consumer-row"
                [class.lumina__consumer-row--unavailable]="consumer.unavailable"
              >
                <span class="lumina__consumer-name">{{ consumer.displayName }}</span>
                <span class="lumina__consumer-value">{{ powerLabel(consumer) }}</span>
              </div>
```

ersetzen durch:

```html
              <button
                *ngFor="let consumer of topConsumers"
                type="button"
                class="lumina__consumer-row"
                [class.lumina__consumer-row--unavailable]="consumer.unavailable"
                (click)="openHistoryDialog(consumer)"
                [attr.aria-label]="'Verlauf von ' + consumer.displayName + ' anzeigen'"
              >
                <span class="lumina__consumer-name">{{ consumer.displayName }}</span>
                <span class="lumina__consumer-value">{{ powerLabel(consumer) }}</span>
              </button>
```

Ebenso im Verbraucher-Dialog den Block

```html
        <div
          *ngFor="let consumer of allConsumers"
          class="lumina__consumer-row lumina__consumer-row--dialog"
          [class.lumina__consumer-row--unavailable]="consumer.unavailable"
        >
          <span class="lumina__consumer-name">{{ consumer.displayName }}</span>
          <span class="lumina__consumer-value">{{ powerLabel(consumer) }}</span>
        </div>
```

ersetzen durch:

```html
        <button
          *ngFor="let consumer of allConsumers"
          type="button"
          class="lumina__consumer-row lumina__consumer-row--dialog"
          [class.lumina__consumer-row--unavailable]="consumer.unavailable"
          (click)="openHistoryDialog(consumer)"
          [attr.aria-label]="'Verlauf von ' + consumer.displayName + ' anzeigen'"
        >
          <span class="lumina__consumer-name">{{ consumer.displayName }}</span>
          <span class="lumina__consumer-value">{{ powerLabel(consumer) }}</span>
        </button>
```

**3h — Graph-Dialog** direkt nach dem Verbraucher-Dialog einfügen (er liegt damit
im DOM darüber, wie der Bestätigungsdialog über dem Schalter-Dialog):

```html
  <!-- Verbrauchsgraph (oeffnet sich beim Klick auf einen Verbraucher) -->
  <div
    *ngIf="historyConsumer"
    class="lumina__dialog-backdrop"
    (click)="closeHistoryDialog()"
  >
    <div
      class="lumina__dialog lumina__dialog--history"
      role="dialog"
      aria-modal="true"
      [attr.aria-label]="'Verlauf ' + historyConsumer.displayName"
      (click)="$event.stopPropagation()"
    >
      <header class="lumina__dialog-head">
        <h2 class="lumina__dialog-title">{{ historyConsumer.displayName }}</h2>
        <button
          type="button"
          class="lumina__dialog-close"
          (click)="closeHistoryDialog()"
          aria-label="Schließen"
        >
          <span class="material-symbols-outlined">close</span>
        </button>
      </header>
      <div class="lumina__dialog-body">
        <div class="lumina__history-ranges" role="group" aria-label="Zeitraum">
          <button
            *ngFor="let range of historyRanges"
            type="button"
            class="lumina__history-range"
            [class.lumina__history-range--active]="range.value === historyRange"
            (click)="setHistoryRange(range.value)"
          >
            {{ range.label }}
          </button>
        </div>
        <p *ngIf="historyError" class="lumina__history-message">{{ historyError }}</p>
        <p *ngIf="historyEmpty && !historyError" class="lumina__history-message">
          Noch keine Daten aufgezeichnet
        </p>
        <div
          *ngIf="historyOptions && !historyEmpty && !historyError"
          echarts
          class="lumina__history-chart"
          [options]="historyOptions"
        ></div>
      </div>
    </div>
  </div>
```

- [ ] **Step 4: Tests ausführen — grün**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager/frontend && npx ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -10
```
Erwartung: 189 Specs, 185 grün (die 4 vorbestehenden Fehler bleiben)

- [ ] **Step 5: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(verbrauchsgraph): Klick auf einen Verbraucher oeffnet den Verlaufs-Dialog"
```

---

## Task 9: Styles für Zeilen-Buttons, Zeitraumwahl und Chart

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

Die Verbraucherzeilen sind jetzt `<button>` statt `<div>` — ohne Reset erben sie
Browser-Standards (grauer Hintergrund, Rahmen, zentrierter Text) und die Kachel
sähe kaputt aus.

- [ ] **Step 1: Bestehende Zeilen-Regel um den Button-Reset erweitern**

Die vorhandene Regel `.lumina__consumer-row { … }` um diese Eigenschaften ergänzen
(innerhalb desselben Blocks, vor dem `&--unavailable`):

```scss
  width: 100%;
  background: none;
  border: none;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s ease;

  &:hover {
    background: rgba(255, 255, 255, 0.04);
  }
```

Die bisherige separate `border-top`-Zeile in dieser Regel entfällt dadurch —
sie ist oben bereits enthalten.

- [ ] **Step 2: Dialog-Styles ergänzen** (ans Ende der Datei):

```scss
.lumina__dialog--history {
  width: min(760px, 92vw);
}

.lumina__history-ranges {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.lumina__history-range {
  padding: 7px 14px;
  border: 1px solid rgba(15, 23, 42, 0.15);
  border-radius: var(--radius-full);
  background: none;
  font: inherit;
  font-size: 13px;
  color: rgba(15, 23, 42, 0.7);
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, border-color 0.2s ease;

  &--active {
    border-color: transparent;
    background: #0f172a;
    color: #f8fafc;
  }
}

.lumina__history-chart {
  width: 100%;
  height: 320px;
}

.lumina__history-message {
  margin: 24px 0;
  text-align: center;
  font-size: 14px;
  color: rgba(15, 23, 42, 0.55);
}

// Im hellen Dialog braucht die Zeile andere Hover-/Trennfarben als auf der dunklen Kachel.
.lumina__consumer-row--dialog {
  &:hover {
    background: rgba(15, 23, 42, 0.04);
  }
}
```

- [ ] **Step 3: Produktions-Build**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager/frontend && npx ng build --configuration production 2>&1 | tail -5
```
Erwartung: Build erfolgreich. Warnungen zur SCSS-Dateigröße sind vorbestehend und
kein Fehler.

- [ ] **Step 4: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add frontend/src/app/pages/dashboard/dashboard.component.scss
git commit -m "feat(verbrauchsgraph): Styles fuer klickbare Zeilen, Zeitraumwahl und Chart"
```

---

## Task 10: Dokumentation und Gesamtverifikation

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Database Schema", nach dem Eintrag „Power Consumer Tile")

- [ ] **Step 1: CLAUDE.md ergänzen**

Nach dem bestehenden Block „**Power Consumer Tile**: …" einfügen:

```markdown
- **Power Consumption History**: `entity_power_history` speichert den Leistungsverlauf aller Power-Sensoren
  - Befüllt von `PowerHistoryRecorder` (`@EventListener` auf `EntityStateChangedEvent`) — quellenoffen, die Integrationen bleiben unverändert; das Event feuert nur bei Wertänderung
  - `power_watts = NULL` markiert bewusst eine Messlücke (Sensor `unavailable`); der Graph reißt dort ab, statt Kontinuität vorzutäuschen
  - `PowerHistoryAggregationJob` verdichtet nach 10 min auf Minuten-, nach 2 Tagen auf Stundenwerte und löscht nach 30 Tagen
  - `GET /v1/power-consumers/{entityId}/history?range=DAY|WEEK|MONTH` liefert die Zeitreihe; Klick auf eine Verbraucherzeile öffnet den Graph-Dialog im Dashboard
```

- [ ] **Step 2: Backend-Tests gesamt**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
cd /c/Users/bened/IdeaProjects/Household-Manager/backend && mvn test 2>&1 | grep -E "Tests run:.*Failures.*Errors.*Skipped:.*$|BUILD" | tail -3
```
Erwartung: Genau 3 Errors — `HouseholdManagerApplicationTests.contextLoads` und die
zwei `HealthControllerTest`-Methoden (fehlende lokale Test-DB). Jede andere Zahl
bedeutet eine echte Regression.

- [ ] **Step 3: Frontend-Tests gesamt**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager/frontend && npx ng test --watch=false --browsers=ChromeHeadless 2>&1 | tail -5
```
Erwartung: 189 Specs, 185 grün, 4 vorbestehende Fehler

- [ ] **Step 4: Commit**

```bash
cd /c/Users/bened/IdeaProjects/Household-Manager
git add CLAUDE.md
git commit -m "docs: Verbrauchshistorie und Graph-Dialog dokumentiert"
```

- [ ] **Step 5: Branch abschließen**

Mit superpowers:finishing-a-development-branch fortfahren (Merge/PR entscheidet
der Nutzer).

**Hinweis für die Abnahme:** Der Graph bleibt nach dem Deploy zunächst leer und
füllt sich erst über die Zeit — rückwirkende Daten existieren nicht. Lokal liefert
vermutlich kein Meross-Gerät Werte (die Zugangsdaten liegen nur im
Docker-Deployment), die reale Sichtprüfung gehört deshalb aufs Deployment.
