# Haushaltskalender — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein im Household-Manager pflegbarer Kalender (Monatsansicht + Termindialog) mit voller RRULE-Unterstützung, dessen nächste Termine im Intelligence Hub erscheinen und der `event.calendar_reminder` an die Flow-Engine feuert.

**Architecture:** Eine DB-Zeile pro Termin/Serie (`calendar_events`); RRULEs werden on-the-fly per `org.dmfs:lib-recur` expandiert (einzige Bibliotheksstelle: `RecurrenceExpansionService`). Ausnahmen laufen über EXDATE-Spalte und Override-Zeilen. Frontend: selbstgebautes Monatsraster, Termindialog mit Wiederholungs-Builder, Hub-Einträge nach dem Müllabfuhr-Muster.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase / `org.dmfs:lib-recur` / Angular 19 standalone / SCSS.

**Spec:** `docs/superpowers/specs/2026-07-25-calendar-design.md`

## Abweichungen von der Spec (bewusst, kosmetisch)

- Tabellenname **`calendar_events`** (Plural) statt `calendar_event` — Projektkonvention (`waste_collection_events`, `entity_states`, …).
- `exdates` als **kommagetrennte ISO-Daten** statt JSON-Array — trivial parsebar ohne Jackson in der Entity, semantisch identisch.
- Hub-Text „Morgen, 14:30 Uhr" statt „Morgen 14:30" — Lesbarkeit.

## Wichtige Umgebungs-Hinweise für den Ausführenden

- **Maven braucht JDK 21:** Vor jedem `mvn` in PowerShell: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'`. Maven liegt unter `C:\Users\bened\apache-maven-3.9.11\bin\mvn` (auf PATH), kein `mvnw`. Immer aus `backend/` heraus ausführen.
- **Vorbestehende Backend-Test-Fails (ignorieren):** `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal mit „Access denied for user 'root'@'localhost'" fehl — Umgebungsproblem, kein Regressionssignal.
- **Frontend-Tests:** `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`. Baseline: **4 FAILED** (Header/App/Hero, vorbestehend). Gelegentliche Karma-Flake bei `SmartDeviceListComponent` → einfach erneut laufen lassen.
- **JPA-Repositories** müssen in `com.household.manager.repository` liegen (JpaConfig schränkt das Scanning ein).
- **lib-recur-Falle:** `org.dmfs.rfc5545.DateTime` zählt Monate **0-basiert**.
- Commits enden mit `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Datei-Struktur

**Backend (neu):**
- `backend/src/main/resources/db/changelog/changes/20260725-0039-create-calendar-events-table.xml`
- `backend/src/main/java/com/household/manager/model/entity/CalendarCategory.java`
- `backend/src/main/java/com/household/manager/model/entity/CalendarEvent.java`
- `backend/src/main/java/com/household/manager/repository/CalendarEventRepository.java`
- `backend/src/main/java/com/household/manager/calendar/RecurrenceExpansionService.java`
- `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`
- `backend/src/main/java/com/household/manager/calendar/CalendarEventController.java`
- `backend/src/main/java/com/household/manager/calendar/CalendarReminderScheduler.java`
- `backend/src/main/java/com/household/manager/dto/CalendarEventRequest.java`
- `backend/src/main/java/com/household/manager/dto/CalendarEventResponse.java`
- `backend/src/main/java/com/household/manager/dto/CalendarOccurrenceResponse.java`

**Backend (ändern):** `backend/pom.xml`, `db.changelog-master.xml`, `entitystate/EntitySource.java`

**Frontend (neu):**
- `frontend/src/app/models/calendar-event.model.ts`
- `frontend/src/app/services/calendar.service.ts` (+ `.spec.ts`)
- `frontend/src/app/shared/hub-insight.model.ts`
- `frontend/src/app/shared/relative-day.util.ts`
- `frontend/src/app/shared/calendar-insight.util.ts` (+ `.spec.ts`)
- `frontend/src/app/shared/rrule.util.ts` (+ `.spec.ts`)
- `frontend/src/app/shared/month-grid.util.ts` (+ `.spec.ts`)
- `frontend/src/app/pages/calendar/calendar.component.ts` / `.html` / `.scss`

**Frontend (ändern):** `app.routes.ts`, `components/header/header.component.ts`, `shared/waste-insight.util.ts`, `pages/dashboard/dashboard.component.ts`

---

### Task 1: Liquibase-Changeset `calendar_events`

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260725-0039-create-calendar-events-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (vor `</databaseChangeLog>`)

- [ ] **Step 1: Changeset anlegen**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260725-0039" author="household-manager">
        <comment>Create calendar_events table (Haushaltskalender: Serien via RRULE, Ausnahmen via EXDATE/Override).</comment>

        <createTable tableName="calendar_events">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="title" type="VARCHAR(200)">
                <constraints nullable="false"/>
            </column>
            <column name="notes" type="TEXT"/>
            <column name="category" type="VARCHAR(30)">
                <constraints nullable="false"/>
            </column>
            <column name="all_day" type="BOOLEAN">
                <constraints nullable="false"/>
            </column>
            <column name="start_date" type="DATE">
                <constraints nullable="false"/>
            </column>
            <column name="start_time" type="TIME"/>
            <column name="end_time" type="TIME"/>
            <column name="end_date" type="DATE"/>
            <column name="rrule" type="VARCHAR(500)"/>
            <column name="exdates" type="TEXT"/>
            <column name="recurring_parent_id" type="BIGINT"/>
            <column name="recurrence_date" type="DATE"/>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <addForeignKeyConstraint
                baseTableName="calendar_events" baseColumnNames="recurring_parent_id"
                referencedTableName="calendar_events" referencedColumnNames="id"
                constraintName="fk_calendar_events_parent" onDelete="CASCADE"/>

        <createIndex indexName="idx_calendar_events_start_date" tableName="calendar_events">
            <column name="start_date"/>
        </createIndex>
        <createIndex indexName="idx_calendar_events_parent" tableName="calendar_events">
            <column name="recurring_parent_id"/>
        </createIndex>

        <rollback>
            <dropTable tableName="calendar_events"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Include im Master ergänzen** (nach dem `entity-power-history`-Include)

```xml
    <!-- Haushaltskalender -->
    <include file="db/changelog/changes/20260725-0039-create-calendar-events-table.xml"/>
```

- [ ] **Step 3: Kompilieren**

Run (PowerShell, aus `backend/`): `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog
git commit -m "feat(calendar): Liquibase-Changeset fuer calendar_events"
```

---

### Task 2: Dependency, Entity, Enum, Repository

**Files:**
- Modify: `backend/pom.xml` (im `<dependencies>`-Block, z. B. nach `commons-csv`)
- Create: `backend/src/main/java/com/household/manager/model/entity/CalendarCategory.java`
- Create: `backend/src/main/java/com/household/manager/model/entity/CalendarEvent.java`
- Create: `backend/src/main/java/com/household/manager/repository/CalendarEventRepository.java`

- [ ] **Step 1: lib-recur-Dependency ergänzen**

```xml
        <!-- RRULE-Expansion fuer den Haushaltskalender (einzige Nutzerin: RecurrenceExpansionService) -->
        <dependency>
            <groupId>org.dmfs</groupId>
            <artifactId>lib-recur</artifactId>
            <version>0.17.1</version>
        </dependency>
```

- [ ] **Step 2: Enum `CalendarCategory`**

```java
package com.household.manager.model.entity;

/** Feste Kategorienliste des Haushaltskalenders; Farben und Labels vergibt das Frontend. */
public enum CalendarCategory {
    GENERAL, FAMILY, HEALTH, HOUSEHOLD, WORK, BIRTHDAY
}
```

- [ ] **Step 3: Entity `CalendarEvent`**

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Ein Termin bzw. eine Serie (rrule gesetzt) des Haushaltskalenders.
 * Override-Zeilen (recurringParentId gesetzt) ersetzen genau ein Serien-Vorkommen;
 * geloeschte Einzelvorkommen stehen als EXDATE-Daten in {@link #exdates}.
 */
@Entity
@Table(name = "calendar_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CalendarCategory category;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /** Fuer mehrtaegige ganztaegige Termine. */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** iCal-RRULE; null = Einzeltermin. */
    @Column(length = 500)
    private String rrule;

    /** Kommagetrennte ISO-Daten geloeschter Einzelvorkommen (EXDATE). */
    @Column(columnDefinition = "TEXT")
    private String exdates;

    @Column(name = "recurring_parent_id")
    private Long recurringParentId;

    /** Welches Serien-Vorkommen diese Override-Zeile ersetzt. */
    @Column(name = "recurrence_date")
    private LocalDate recurrenceDate;

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

    public boolean isRecurring() {
        return rrule != null && !rrule.isBlank();
    }

    public boolean isOverride() {
        return recurringParentId != null;
    }

    public Set<LocalDate> exdateSet() {
        if (exdates == null || exdates.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(exdates.split(","))
                .map(String::trim)
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }

    public void addExdate(LocalDate date) {
        Set<LocalDate> all = new TreeSet<>(exdateSet());
        all.add(date);
        exdates = all.stream().map(LocalDate::toString).collect(Collectors.joining(","));
    }
}
```

- [ ] **Step 4: Repository** (MUSS in `com.household.manager.repository` liegen — JpaConfig)

```java
package com.household.manager.repository;

import com.household.manager.model.entity.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findByRecurringParentId(Long recurringParentId);

    Optional<CalendarEvent> findByRecurringParentIdAndRecurrenceDate(
            Long recurringParentId, LocalDate recurrenceDate);

    void deleteByRecurringParentId(Long recurringParentId);
}
```

- [ ] **Step 5: Kompilieren**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn compile -q`
Expected: BUILD SUCCESS (lib-recur wird aufgelöst)

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/household/manager/model/entity backend/src/main/java/com/household/manager/repository/CalendarEventRepository.java
git commit -m "feat(calendar): Entity, Kategorie-Enum und Repository fuer Kalendertermine"
```

---

### Task 3: `RecurrenceExpansionService` (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/calendar/RecurrenceExpansionService.java`
- Test: `backend/src/test/java/com/household/manager/calendar/RecurrenceExpansionServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceExpansionServiceTest {

    private final RecurrenceExpansionService service = new RecurrenceExpansionService();

    @Test
    void taeglicheSerieLiefertJedenTagImFenster() {
        List<LocalDate> result = service.expand("FREQ=DAILY", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 12));
    }

    @Test
    void woechentlicheSerieMitBydayTrifftNurDienstage() {
        // 07.07.2026 ist ein Dienstag
        List<LocalDate> result = service.expand("FREQ=WEEKLY;BYDAY=TU", LocalDate.of(2026, 7, 7),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 7), LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 28));
    }

    @Test
    void jederZweiteDienstagImMonat() {
        List<LocalDate> result = service.expand("FREQ=MONTHLY;BYDAY=2TU", LocalDate.of(2026, 7, 14),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 8, 11), LocalDate.of(2026, 9, 8));
    }

    @Test
    void countBegrenztDieSerie() {
        List<LocalDate> result = service.expand("FREQ=DAILY;COUNT=3", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(result).hasSize(3);
    }

    @Test
    void untilBegrenztDieSerie() {
        List<LocalDate> result = service.expand("FREQ=DAILY;UNTIL=20260703", LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
        assertThat(result).containsExactly(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 3));
    }

    @Test
    void fensterVorSerienstartIstLeer() {
        List<LocalDate> result = service.expand("FREQ=DAILY", LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5));
        assertThat(result).isEmpty();
    }

    @Test
    void expansionIstAufMaxOccurrencesGekappt() {
        List<LocalDate> result = service.expand("FREQ=DAILY", LocalDate.of(2020, 1, 1),
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1));
        assertThat(result).hasSize(RecurrenceExpansionService.MAX_OCCURRENCES);
    }

    @Test
    void ungueltigeRegelWirdMitBadRequestAbgelehnt() {
        assertThatThrownBy(() -> service.validate("FREQ=BANANA"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q "-Dtest=RecurrenceExpansionServiceTest"`
Expected: Kompilierfehler (Klasse existiert nicht)

- [ ] **Step 3: Implementierung**

```java
package com.household.manager.calendar;

import org.dmfs.rfc5545.DateTime;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.dmfs.rfc5545.recur.RecurrenceRule;
import org.dmfs.rfc5545.recur.RecurrenceRuleIterator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Einzige Stelle mit lib-recur-Spezifika (RRULE-Parsing und -Expansion) — nach dem
 * Projektmuster "brittle Fremd-API in eine Klasse sperren".
 *
 * <p>Bibliotheks-Falle: {@link DateTime} zaehlt Monate 0-basiert; die Umrechnung
 * passiert ausschliesslich in {@link #toDateTime}/{@link #toLocalDate}.
 */
@Service
public class RecurrenceExpansionService {

    /** Harte Kappe pro Abfrage — eine pathologische Regel darf nichts festfahren. */
    static final int MAX_OCCURRENCES = 1000;

    /** @throws ResponseStatusException 400, wenn die Regel kein gueltiges RRULE ist */
    public void validate(String rrule) {
        parse(rrule);
    }

    /**
     * Expandiert die Regel ab Serienstart und liefert alle Vorkommen-Daten im Fenster
     * [from, to] (einschliesslich), gekappt bei {@link #MAX_OCCURRENCES}.
     */
    public List<LocalDate> expand(String rrule, LocalDate seriesStart, LocalDate from, LocalDate to) {
        RecurrenceRuleIterator iterator = parse(rrule).iterator(toDateTime(seriesStart));
        if (from.isAfter(seriesStart)) {
            iterator.fastForward(toDateTime(from));
        }
        List<LocalDate> occurrences = new ArrayList<>();
        while (iterator.hasNext() && occurrences.size() < MAX_OCCURRENCES) {
            LocalDate occurrence = toLocalDate(iterator.nextDateTime());
            if (occurrence.isAfter(to)) {
                break;
            }
            if (!occurrence.isBefore(from)) {
                occurrences.add(occurrence);
            }
        }
        return occurrences;
    }

    private RecurrenceRule parse(String rrule) {
        try {
            return new RecurrenceRule(rrule);
        } catch (InvalidRecurrenceRuleException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Wiederholungsregel ist ungueltig: " + ex.getMessage());
        }
    }

    private DateTime toDateTime(LocalDate date) {
        return new DateTime(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
    }

    private LocalDate toLocalDate(DateTime dateTime) {
        return LocalDate.of(dateTime.getYear(), dateTime.getMonth() + 1, dateTime.getDayOfMonth());
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q "-Dtest=RecurrenceExpansionServiceTest"`
Expected: `Tests run: 8, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/calendar backend/src/test/java/com/household/manager/calendar
git commit -m "feat(calendar): RRULE-Expansion via lib-recur mit Sicherheitskappe"
```

---

### Task 4: DTOs + `CalendarEventService` CRUD & Validierung (TDD)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/CalendarEventRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/CalendarEventResponse.java`
- Create: `backend/src/main/java/com/household/manager/dto/CalendarOccurrenceResponse.java`
- Create: `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarEventServiceTest.java`

- [ ] **Step 1: DTOs anlegen**

`CalendarEventRequest.java`:

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.model.entity.CalendarCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** Anlege-/Aenderungsdaten eines Kalendertermins. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventRequest {

    private String title;
    private String notes;
    private CalendarCategory category;
    private boolean allDay;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /** iCal-RRULE; null/leer = Einzeltermin. */
    private String rrule;
}
```

`CalendarEventResponse.java`:

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.model.entity.CalendarCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** Stammdaten eines Termins/einer Serie, wie der Termindialog sie laedt. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventResponse {

    private Long id;
    private String title;
    private String notes;
    private CalendarCategory category;
    private boolean allDay;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private String rrule;
    private boolean recurring;
}
```

`CalendarOccurrenceResponse.java`:

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.model.entity.CalendarCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Ein konkretes (bereits expandiertes) Vorkommen. {@code eventId} zeigt immer auf die
 * Master-Zeile (bei Overrides auf die Serie), {@code recurrenceDate} ist der Schluessel
 * fuer die Occurrence-Endpoints; bei Einzelterminen ist er null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarOccurrenceResponse {

    private Long eventId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate occurrenceDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate recurrenceDate;

    private String title;
    private String notes;
    private CalendarCategory category;
    private boolean allDay;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private boolean recurring;

    /** 0 = heute, 1 = morgen. Serverseitig berechnet (Muster WasteCollectionEventResponse). */
    private long daysUntil;
}
```

- [ ] **Step 2: Failing Tests für CRUD + Validierung schreiben**

```java
package com.household.manager.calendar;

import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.model.entity.CalendarEvent;
import com.household.manager.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarEventServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Fixes "Jetzt": 25.07.2026, 12:00 Uhr Berliner Zeit (ein Samstag). */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZONE);

    @Mock
    private CalendarEventRepository repository;

    private CalendarEventService service;

    @BeforeEach
    void setUp() {
        service = new CalendarEventService(repository, new RecurrenceExpansionService(), CLOCK);
    }

    private CalendarEventRequest.CalendarEventRequestBuilder validRequest() {
        return CalendarEventRequest.builder()
                .title("Zahnarzt")
                .category(CalendarCategory.HEALTH)
                .allDay(false)
                .startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30));
    }

    @Test
    void createSpeichertUndLiefertResponse() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.create(validRequest().build());

        assertThat(response.getTitle()).isEqualTo("Zahnarzt");
        assertThat(response.isRecurring()).isFalse();
        verify(repository).save(any(CalendarEvent.class));
    }

    @Test
    void leererTitelWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(validRequest().title("  ").build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Titel");
        verify(repository, never()).save(any());
    }

    @Test
    void uhrzeitTerminOhneStartzeitWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(validRequest().startTime(null).build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void endeVorStartWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(
                validRequest().endTime(LocalTime.of(13, 0)).build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void enddatumVorStartdatumWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(
                validRequest().allDay(true).startTime(null)
                        .endDate(LocalDate.of(2026, 8, 1)).build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void ungueltigeRruleWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(validRequest().rrule("FREQ=BANANA").build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void ganztagsTerminVerliertUhrzeiten() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.create(
                validRequest().allDay(true).endTime(LocalTime.of(15, 0)).build());

        assertThat(response.getStartTime()).isNull();
        assertThat(response.getEndTime()).isNull();
    }

    @Test
    void deleteLoeschtAuchOverrides() {
        CalendarEvent event = CalendarEvent.builder().id(7L).title("Serie").build();
        when(repository.findById(7L)).thenReturn(Optional.of(event));

        service.delete(7L);

        verify(repository).deleteByRecurringParentId(7L);
        verify(repository).delete(event);
    }

    @Test
    void updateUnbekannterIdLiefert404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(99L, validRequest().build()))
                .isInstanceOf(ResponseStatusException.class);
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q "-Dtest=CalendarEventServiceTest"`
Expected: Kompilierfehler (Service existiert nicht)

- [ ] **Step 4: Service implementieren (CRUD-Teil; Occurrence-Methoden kommen in Task 5/6)**

```java
package com.household.manager.calendar;

import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.model.entity.CalendarEvent;
import com.household.manager.repository.CalendarEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * CRUD und Occurrence-Aufloesung des Haushaltskalenders. Die {@link Clock} ist
 * injiziert, damit "heute" in Tests deterministisch ist (Muster WasteCollectionService).
 */
@Service
@Slf4j
public class CalendarEventService {

    private final CalendarEventRepository repository;
    private final RecurrenceExpansionService expansionService;
    private final Clock clock;

    public CalendarEventService(CalendarEventRepository repository,
                                RecurrenceExpansionService expansionService,
                                Clock clock) {
        this.repository = repository;
        this.expansionService = expansionService;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    @Transactional(readOnly = true)
    public CalendarEventResponse getEvent(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public CalendarEventResponse create(CalendarEventRequest request) {
        validate(request);
        return toResponse(repository.save(applyRequest(request, new CalendarEvent())));
    }

    @Transactional
    public CalendarEventResponse update(Long id, CalendarEventRequest request) {
        validate(request);
        return toResponse(repository.save(applyRequest(request, findOrThrow(id))));
    }

    @Transactional
    public void delete(Long id) {
        CalendarEvent event = findOrThrow(id);
        repository.deleteByRecurringParentId(id);
        repository.delete(event);
    }

    private CalendarEvent findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Termin %d existiert nicht.".formatted(id)));
    }

    private CalendarEvent applyRequest(CalendarEventRequest request, CalendarEvent event) {
        event.setTitle(request.getTitle().trim());
        event.setNotes(request.getNotes());
        event.setCategory(request.getCategory());
        event.setAllDay(request.isAllDay());
        event.setStartDate(request.getStartDate());
        event.setStartTime(request.isAllDay() ? null : request.getStartTime());
        event.setEndTime(request.isAllDay() ? null : request.getEndTime());
        event.setEndDate(request.getEndDate());
        event.setRrule(request.getRrule() != null && !request.getRrule().isBlank()
                ? request.getRrule() : null);
        return event;
    }

    private void validate(CalendarEventRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Titel darf nicht leer sein.");
        }
        if (request.getCategory() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Kategorie fehlt.");
        }
        if (request.getStartDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Startdatum fehlt.");
        }
        if (!request.isAllDay() && request.getStartTime() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ein Termin mit Uhrzeit braucht eine Start-Uhrzeit.");
        }
        if (!request.isAllDay() && request.getStartTime() != null && request.getEndTime() != null
                && request.getEndTime().isBefore(request.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Ende darf nicht vor dem Start liegen.");
        }
        if (request.getEndDate() != null && request.getEndDate().isBefore(request.getStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Das Enddatum darf nicht vor dem Startdatum liegen.");
        }
        if (request.getRrule() != null && !request.getRrule().isBlank()) {
            expansionService.validate(request.getRrule());
        }
    }

    private CalendarEventResponse toResponse(CalendarEvent event) {
        return CalendarEventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .notes(event.getNotes())
                .category(event.getCategory())
                .allDay(event.isAllDay())
                .startDate(event.getStartDate())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .endDate(event.getEndDate())
                .rrule(event.getRrule())
                .recurring(event.isRecurring())
                .build();
    }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q "-Dtest=CalendarEventServiceTest"`
Expected: `Tests run: 9, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto backend/src/main/java/com/household/manager/calendar backend/src/test/java/com/household/manager/calendar
git commit -m "feat(calendar): DTOs und CalendarEventService mit CRUD und Validierung"
```

---

### Task 5: Occurrence-Expansion im Service (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarEventServiceTest.java` (Tests ergänzen)

- [ ] **Step 1: Failing Tests ergänzen** (in `CalendarEventServiceTest`)

```java
    private CalendarEvent series(Long id, String title, LocalDate start, String rrule) {
        return CalendarEvent.builder()
                .id(id).title(title).category(CalendarCategory.GENERAL)
                .allDay(true).startDate(start).rrule(rrule)
                .build();
    }

    @Test
    void einzelterminErscheintImFenster() {
        CalendarEvent single = CalendarEvent.builder()
                .id(1L).title("Zahnarzt").category(CalendarCategory.HEALTH)
                .allDay(false).startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30))
                .build();
        when(repository.findAll()).thenReturn(List.of(single));

        var result = service.getOccurrences(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo(1L);
        assertThat(result.get(0).getRecurrenceDate()).isNull();
        assertThat(result.get(0).getDaysUntil()).isEqualTo(9);
    }

    @Test
    void serieWirdExpandiertUndExdateGefiltert() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        weekly.addExdate(LocalDate.of(2026, 7, 13));
        when(repository.findAll()).thenReturn(List.of(weekly));

        var result = service.getOccurrences(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(result).extracting(o -> o.getOccurrenceDate()).containsExactly(
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 27));
        assertThat(result).allMatch(o -> o.isRecurring());
        assertThat(result.get(0).getRecurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 6));
    }

    @Test
    void overrideErsetztDasBerechneteVorkommen() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        CalendarEvent override = CalendarEvent.builder()
                .id(3L).title("Sport (verschoben)").category(CalendarCategory.GENERAL)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .recurringParentId(2L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .build();
        when(repository.findAll()).thenReturn(List.of(weekly, override));

        var result = service.getOccurrences(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 18));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Sport (verschoben)");
        assertThat(result.get(0).getOccurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        // eventId zeigt auf die Serie, recurrenceDate auf das ersetzte Vorkommen
        assertThat(result.get(0).getEventId()).isEqualTo(2L);
        assertThat(result.get(0).getRecurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @Test
    void upcomingFiltertHeuteBereitsVergangeneUhrzeitTermine() {
        // CLOCK steht auf 25.07.2026 12:00
        CalendarEvent past = CalendarEvent.builder()
                .id(4L).title("Vorbei").category(CalendarCategory.GENERAL)
                .allDay(false).startDate(LocalDate.of(2026, 7, 25))
                .startTime(LocalTime.of(9, 0))
                .build();
        CalendarEvent later = CalendarEvent.builder()
                .id(5L).title("Kommt noch").category(CalendarCategory.GENERAL)
                .allDay(false).startDate(LocalDate.of(2026, 7, 25))
                .startTime(LocalTime.of(18, 0))
                .build();
        when(repository.findAll()).thenReturn(List.of(past, later));

        var result = service.getUpcoming(3);

        assertThat(result).extracting(o -> o.getTitle()).containsExactly("Kommt noch");
    }

    @Test
    void upcomingRespektiertDasLimit() {
        CalendarEvent daily = series(6L, "Taeglich", LocalDate.of(2026, 7, 20), "FREQ=DAILY");
        when(repository.findAll()).thenReturn(List.of(daily));

        assertThat(service.getUpcoming(3)).hasSize(3);
    }

    @Test
    void fensterUeberEinemJahrWirdAbgelehnt() {
        assertThatThrownBy(() -> service.getOccurrences(
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 1)))
                .isInstanceOf(ResponseStatusException.class);
    }
```

- [ ] **Step 2: Test laufen lassen — die neuen Tests müssen fehlschlagen** (Kompilierfehler: `getOccurrences`/`getUpcoming` fehlen)

- [ ] **Step 3: Occurrence-Methoden implementieren** (in `CalendarEventService` ergänzen; zusätzliche Imports: `CalendarOccurrenceResponse`, `java.time.temporal.ChronoUnit`, `java.util.*`, `java.util.stream.Collectors`)

```java
    /** Maximale Fenstergroesse einer Occurrence-Abfrage (deckt sich mit der Expansions-Kappe). */
    private static final long MAX_WINDOW_DAYS = 366;

    /**
     * Alle Vorkommen im Fenster [from, to]: Serien expandiert, EXDATEs gefiltert,
     * Overrides eingerechnet. Mehrtaegige Termine erscheinen an ihrem Starttag
     * (endDate ist Metadatum, kein Spanning im Raster — bewusste v1-Entscheidung).
     */
    @Transactional(readOnly = true)
    public List<CalendarOccurrenceResponse> getOccurrences(LocalDate from, LocalDate to) {
        validateWindow(from, to);
        List<CalendarEvent> all = repository.findAll();
        Map<Long, Set<LocalDate>> overriddenDates = all.stream()
                .filter(CalendarEvent::isOverride)
                .collect(Collectors.groupingBy(CalendarEvent::getRecurringParentId,
                        Collectors.mapping(CalendarEvent::getRecurrenceDate, Collectors.toSet())));

        List<CalendarOccurrenceResponse> occurrences = new ArrayList<>();
        for (CalendarEvent event : all) {
            if (event.isOverride() || !event.isRecurring()) {
                if (!event.getStartDate().isBefore(from) && !event.getStartDate().isAfter(to)) {
                    occurrences.add(toOccurrence(event, event.getStartDate()));
                }
                continue;
            }
            Set<LocalDate> skip = new HashSet<>(event.exdateSet());
            skip.addAll(overriddenDates.getOrDefault(event.getId(), Set.of()));
            for (LocalDate date : expansionService.expand(
                    event.getRrule(), event.getStartDate(), from, to)) {
                if (!skip.contains(date)) {
                    occurrences.add(toOccurrence(event, date));
                }
            }
        }
        occurrences.sort(Comparator
                .comparing(CalendarOccurrenceResponse::getOccurrenceDate)
                .thenComparing(o -> o.getStartTime() != null ? o.getStartTime() : LocalTime.MIN));
        return occurrences;
    }

    /** Die naechsten Vorkommen ab jetzt; heute bereits beendete Uhrzeit-Termine fallen raus. */
    @Transactional(readOnly = true)
    public List<CalendarOccurrenceResponse> getUpcoming(int limit) {
        LocalDate today = today();
        LocalTime now = LocalTime.now(clock);
        return getOccurrences(today, today.plusDays(MAX_WINDOW_DAYS - 1)).stream()
                .filter(occ -> !isAlreadyOver(occ, today, now))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** Heutige Uhrzeit-Termine gelten ab ihrem Ende (bzw. Start, wenn kein Ende) als vorbei. */
    private boolean isAlreadyOver(CalendarOccurrenceResponse occ, LocalDate today, LocalTime now) {
        if (!occ.getOccurrenceDate().equals(today) || occ.isAllDay()) {
            return false;
        }
        LocalTime end = occ.getEndTime() != null ? occ.getEndTime() : occ.getStartTime();
        return end.isBefore(now);
    }

    private void validateWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Zeitraum ist ungueltig (from muss vor to liegen).");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Zeitraum darf hoechstens ein Jahr umfassen.");
        }
    }

    private CalendarOccurrenceResponse toOccurrence(CalendarEvent event, LocalDate date) {
        boolean override = event.isOverride();
        long durationDays = event.getEndDate() != null
                ? ChronoUnit.DAYS.between(event.getStartDate(), event.getEndDate()) : 0;
        return CalendarOccurrenceResponse.builder()
                .eventId(override ? event.getRecurringParentId() : event.getId())
                .occurrenceDate(date)
                .recurrenceDate(override ? event.getRecurrenceDate()
                        : (event.isRecurring() ? date : null))
                .title(event.getTitle())
                .notes(event.getNotes())
                .category(event.getCategory())
                .allDay(event.isAllDay())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .endDate(event.getEndDate() != null ? date.plusDays(durationDays) : null)
                .recurring(event.isRecurring() || override)
                .daysUntil(ChronoUnit.DAYS.between(today(), date))
                .build();
    }
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q "-Dtest=CalendarEventServiceTest"`
Expected: `Tests run: 15, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/calendar backend/src/test/java/com/household/manager/calendar
git commit -m "feat(calendar): Occurrence-Expansion mit EXDATE- und Override-Aufloesung"
```

---

### Task 6: Occurrence-Operationen (löschen = EXDATE, ändern = Override) (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarEventServiceTest.java` (Tests ergänzen)

- [ ] **Step 1: Failing Tests ergänzen**

```java
    @Test
    void deleteOccurrenceBeiEinzelterminLoeschtDenTermin() {
        CalendarEvent single = CalendarEvent.builder()
                .id(1L).title("Einmalig").category(CalendarCategory.GENERAL)
                .allDay(true).startDate(LocalDate.of(2026, 8, 3))
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(single));

        service.deleteOccurrence(1L, LocalDate.of(2026, 8, 3));

        verify(repository).delete(single);
    }

    @Test
    void deleteOccurrenceBeiSerieTraegtExdateEinUndLoeschtOverride() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        CalendarEvent override = CalendarEvent.builder()
                .id(3L).recurringParentId(2L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .title("Sport (verschoben)").category(CalendarCategory.GENERAL)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .build();
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.of(override));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteOccurrence(2L, LocalDate.of(2026, 7, 13));

        verify(repository).delete(override);
        assertThat(weekly.exdateSet()).contains(LocalDate.of(2026, 7, 13));
        verify(repository).save(weekly);
    }

    @Test
    void updateOccurrenceLegtOverrideAn() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventRequest request = CalendarEventRequest.builder()
                .title("Sport (verschoben)").category(CalendarCategory.GENERAL)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .rrule("FREQ=WEEKLY") // muss ignoriert werden — Overrides sind nie Serien
                .build();

        CalendarEventResponse response =
                service.updateOccurrence(2L, LocalDate.of(2026, 7, 13), request);

        assertThat(response.getTitle()).isEqualTo("Sport (verschoben)");
        assertThat(response.getRrule()).isNull();
    }

    @Test
    void updateOccurrenceAufEinzelterminWirdAbgelehnt() {
        CalendarEvent single = CalendarEvent.builder()
                .id(1L).title("Einmalig").category(CalendarCategory.GENERAL)
                .allDay(true).startDate(LocalDate.of(2026, 8, 3))
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(single));

        assertThatThrownBy(() -> service.updateOccurrence(1L, LocalDate.of(2026, 8, 3),
                validRequest().build()))
                .isInstanceOf(ResponseStatusException.class);
    }
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen** (Methoden fehlen)

- [ ] **Step 3: Implementieren** (in `CalendarEventService` ergänzen)

```java
    /**
     * Loescht nur dieses Vorkommen: bei Einzelterminen die ganze Zeile, bei Serien
     * EXDATE am Master plus Entfernen eines eventuellen Overrides.
     */
    @Transactional
    public void deleteOccurrence(Long id, LocalDate occurrenceDate) {
        CalendarEvent event = findOrThrow(id);
        if (!event.isRecurring()) {
            repository.delete(event);
            return;
        }
        repository.findByRecurringParentIdAndRecurrenceDate(id, occurrenceDate)
                .ifPresent(repository::delete);
        event.addExdate(occurrenceDate);
        repository.save(event);
    }

    /** Aendert nur dieses Vorkommen: legt eine Override-Zeile an bzw. aktualisiert sie. */
    @Transactional
    public CalendarEventResponse updateOccurrence(Long id, LocalDate occurrenceDate,
                                                  CalendarEventRequest request) {
        CalendarEvent master = findOrThrow(id);
        if (!master.isRecurring()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nur Serien haben einzelne Vorkommen — Einzeltermine direkt bearbeiten.");
        }
        validate(request);
        CalendarEvent override = repository
                .findByRecurringParentIdAndRecurrenceDate(id, occurrenceDate)
                .orElseGet(() -> CalendarEvent.builder()
                        .recurringParentId(id)
                        .recurrenceDate(occurrenceDate)
                        .build());
        applyRequest(request, override);
        override.setRrule(null); // Overrides sind nie selbst Serien
        return toResponse(repository.save(override));
    }
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q "-Dtest=CalendarEventServiceTest"`
Expected: `Tests run: 19, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/calendar backend/src/test/java/com/household/manager/calendar
git commit -m "feat(calendar): Einzelvorkommen loeschen (EXDATE) und aendern (Override)"
```

---

### Task 7: `CalendarEventController`

**Files:**
- Create: `backend/src/main/java/com/household/manager/calendar/CalendarEventController.java`

- [ ] **Step 1: Controller anlegen** (dünn — Logik liegt komplett im Service)

```java
package com.household.manager.calendar;

import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.dto.CalendarOccurrenceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Haushaltskalender: Termine, Serien und expandierte Vorkommen.
 * Basis-URL: /api/v1/calendar
 */
@RestController
@RequestMapping("/v1/calendar")
@RequiredArgsConstructor
public class CalendarEventController {

    private final CalendarEventService service;

    /** Expandierte Vorkommen im Zeitraum — die eine Abfrage der Monatsansicht. */
    @GetMapping("/events")
    public ResponseEntity<List<CalendarOccurrenceResponse>> getOccurrences(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getOccurrences(from, to));
    }

    /** Die naechsten Vorkommen ab jetzt (Intelligence Hub). */
    @GetMapping("/upcoming")
    public ResponseEntity<List<CalendarOccurrenceResponse>> getUpcoming(
            @RequestParam(defaultValue = "3") int limit) {
        return ResponseEntity.ok(service.getUpcoming(limit));
    }

    /** Stammdaten eines Termins/einer Serie (fuer den Bearbeiten-Dialog). */
    @GetMapping("/events/{id}")
    public ResponseEntity<CalendarEventResponse> getEvent(@PathVariable Long id) {
        return ResponseEntity.ok(service.getEvent(id));
    }

    @PostMapping("/events")
    public ResponseEntity<CalendarEventResponse> create(@RequestBody CalendarEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/events/{id}")
    public ResponseEntity<CalendarEventResponse> update(@PathVariable Long id,
            @RequestBody CalendarEventRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Nur dieses Vorkommen loeschen (EXDATE am Master). */
    @DeleteMapping("/events/{id}/occurrences/{date}")
    public ResponseEntity<Void> deleteOccurrence(@PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.deleteOccurrence(id, date);
        return ResponseEntity.noContent().build();
    }

    /** Nur dieses Vorkommen aendern (Override anlegen/aktualisieren). */
    @PutMapping("/events/{id}/occurrences/{date}")
    public ResponseEntity<CalendarEventResponse> updateOccurrence(@PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody CalendarEventRequest request) {
        return ResponseEntity.ok(service.updateOccurrence(id, date, request));
    }
}
```

- [ ] **Step 2: Kompilieren**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/calendar/CalendarEventController.java
git commit -m "feat(calendar): REST-API unter /v1/calendar"
```

---

### Task 8: `CalendarReminderScheduler` + `EntitySource.CALENDAR` (TDD)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`
- Create: `backend/src/main/java/com/household/manager/calendar/CalendarReminderScheduler.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarReminderSchedulerTest.java`

- [ ] **Step 1: `EntitySource` erweitern** (vor `MANUAL` einfügen)

```java
    /** Haushaltskalender (interne Termine, event.calendar_reminder). */
    CALENDAR,
```

- [ ] **Step 2: Failing Test schreiben**

```java
package com.household.manager.calendar;

import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.CalendarCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarReminderSchedulerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Mock
    private CalendarEventService calendarService;
    @Mock
    private EntityStateService entityStateService;

    private CalendarReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CalendarReminderScheduler(calendarService, entityStateService, CLOCK);
    }

    private CalendarOccurrenceResponse occurrence(boolean allDay, LocalTime startTime) {
        return CalendarOccurrenceResponse.builder()
                .eventId(1L).title("Zahnarzt").category(CalendarCategory.HEALTH)
                .allDay(allDay).occurrenceDate(LocalDate.of(2026, 7, 25))
                .startTime(startTime)
                .build();
    }

    @Test
    void feuertUhrzeitTerminImFensterMitKategorieAlsAction() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(false, LocalTime.of(14, 30))));

        scheduler.fireRemindersBetween(
                LocalDateTime.of(2026, 7, 25, 14, 29),
                LocalDateTime.of(2026, 7, 25, 14, 30));

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("event.calendar_reminder");
        assertThat(captor.getValue().state()).isEqualTo("health");
        assertThat(captor.getValue().attributes()).containsEntry("title", "Zahnarzt");
    }

    @Test
    void feuertGanztaegigenTerminUmAcht() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(true, null)));

        scheduler.fireRemindersBetween(
                LocalDateTime.of(2026, 7, 25, 7, 59),
                LocalDateTime.of(2026, 7, 25, 8, 0));

        verify(entityStateService).reportEvent(any());
    }

    @Test
    void feuertNichtAusserhalbDesFensters() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(false, LocalTime.of(14, 30))));

        scheduler.fireRemindersBetween(
                LocalDateTime.of(2026, 7, 25, 14, 30),
                LocalDateTime.of(2026, 7, 25, 14, 31));

        // Startzeit 14:30 lag am Fensteranfang (exklusiv) — bereits im vorigen Lauf gefeuert
        verify(entityStateService, never()).reportEvent(any());
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen** (Klasse existiert nicht)

- [ ] **Step 4: Scheduler implementieren**

```java
package com.household.manager.calendar;

import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Feuert fuer faellige Kalender-Vorkommen das Flow-Event {@code event.calendar_reminder}
 * (action = Kategorie kleingeschrieben) — Uhrzeit-Termine zum Start, ganztaegige um 08:00.
 */
@Service
@Slf4j
public class CalendarReminderScheduler {

    static final String ENTITY_ID = "event.calendar_reminder";
    /** Ganztaegige Termine erinnern morgens um diese Zeit (bewusst Konstante, keine Settings-UI in v1). */
    static final LocalTime ALL_DAY_REMINDER_TIME = LocalTime.of(8, 0);

    private final CalendarEventService calendarService;
    private final EntityStateService entityStateService;
    private final Clock clock;

    /**
     * Obergrenze des zuletzt geprueften Fensters. Startwert "jetzt": Nach einem Neustart
     * werden verpasste Erinnerungen bewusst NICHT nachgefeuert — eine verspaetete
     * Erinnerung waere irrefuehrender als keine. In-memory reicht damit aus.
     */
    private LocalDateTime lastChecked;

    public CalendarReminderScheduler(CalendarEventService calendarService,
                                     EntityStateService entityStateService,
                                     Clock clock) {
        this.calendarService = calendarService;
        this.entityStateService = entityStateService;
        this.clock = clock;
        this.lastChecked = LocalDateTime.now(clock);
    }

    @Scheduled(fixedDelayString = "${calendar.reminder.check-interval-ms:60000}")
    public void checkDueReminders() {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            fireRemindersBetween(lastChecked, now);
        } catch (Exception ex) {
            // Kalenderfehler duerfen den Scheduler-Thread nie reissen (Hook-Muster des Entity-Layers).
            log.error("Kalender-Erinnerungen konnten nicht geprueft werden", ex);
        }
        lastChecked = now;
    }

    /** Feuert alle Vorkommen, deren Erinnerungszeitpunkt in (since, until] liegt. */
    void fireRemindersBetween(LocalDateTime since, LocalDateTime until) {
        for (CalendarOccurrenceResponse occ :
                calendarService.getOccurrences(since.toLocalDate(), until.toLocalDate())) {
            LocalDateTime reminderAt = reminderTime(occ);
            if (reminderAt.isAfter(since) && !reminderAt.isAfter(until)) {
                fire(occ);
            }
        }
    }

    private LocalDateTime reminderTime(CalendarOccurrenceResponse occ) {
        LocalTime time = occ.isAllDay() ? ALL_DAY_REMINDER_TIME : occ.getStartTime();
        return occ.getOccurrenceDate().atTime(time);
    }

    private void fire(CalendarOccurrenceResponse occ) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("title", occ.getTitle());
        attributes.put("date", occ.getOccurrenceDate().toString());
        attributes.put("time", occ.getStartTime() != null ? occ.getStartTime().toString() : null);
        attributes.put("allDay", occ.isAllDay());
        attributes.put("eventId", occ.getEventId());
        entityStateService.reportEvent(EntityStateUpdate.builder()
                .entityId(ENTITY_ID)
                .domain(EntityDomain.EVENT)
                .source(EntitySource.CALENDAR)
                .sourceRef("calendar")
                .friendlyName("Kalender-Erinnerung")
                .state(occ.getCategory().name().toLowerCase(Locale.ROOT))
                .attributes(attributes)
                .build());
        log.info("Kalender-Erinnerung gefeuert: {} am {}", occ.getTitle(), occ.getOccurrenceDate());
    }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q "-Dtest=CalendarReminderSchedulerTest"`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: Gesamten Backend-Testlauf prüfen**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q`
Expected: Nur die zwei bekannten DB-Umgebungsfails (`contextLoads`, `HealthControllerTest`) — sonst grün.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager backend/src/test/java/com/household/manager
git commit -m "feat(calendar): Reminder-Scheduler feuert event.calendar_reminder fuer Flows"
```

---

### Task 9: Frontend-Model + `CalendarService`

**Files:**
- Create: `frontend/src/app/models/calendar-event.model.ts`
- Create: `frontend/src/app/services/calendar.service.ts`

- [ ] **Step 1: Model anlegen**

```ts
/** Feste Kategorienliste — Werte muessen dem Backend-Enum CalendarCategory entsprechen. */
export type CalendarCategory =
  'GENERAL' | 'FAMILY' | 'HEALTH' | 'HOUSEHOLD' | 'WORK' | 'BIRTHDAY';

/** Anzeige-Metadaten je Kategorie (Farbe fuer Chips und Dialog). */
export const CATEGORY_META: Record<CalendarCategory, { label: string; color: string }> = {
  GENERAL:   { label: 'Allgemein',  color: '#64b5f6' },
  FAMILY:    { label: 'Familie',    color: '#ba68c8' },
  HEALTH:    { label: 'Gesundheit', color: '#e57373' },
  HOUSEHOLD: { label: 'Haushalt',   color: '#81c784' },
  WORK:      { label: 'Arbeit',     color: '#ffb74d' },
  BIRTHDAY:  { label: 'Geburtstag', color: '#f06292' }
};

/** Anlege-/Aenderungsdaten eines Termins. */
export interface CalendarEventRequest {
  title: string;
  notes: string | null;
  category: CalendarCategory;
  allDay: boolean;
  /** ISO-Datum, z. B. "2026-08-03". */
  startDate: string;
  /** "HH:mm" oder null (ganztaegig). */
  startTime: string | null;
  endTime: string | null;
  endDate: string | null;
  /** iCal-RRULE; null = Einzeltermin. */
  rrule: string | null;
}

/** Stammdaten eines Termins/einer Serie (Bearbeiten-Dialog). */
export interface CalendarEvent extends CalendarEventRequest {
  id: number;
  recurring: boolean;
}

/** Ein expandiertes Vorkommen, wie es Monatsraster und Hub anzeigen. */
export interface CalendarOccurrence {
  /** Id der Master-Zeile (bei Overrides: der Serie). */
  eventId: number;
  occurrenceDate: string;
  /** Schluessel fuer die Occurrence-Endpoints; null bei Einzelterminen. */
  recurrenceDate: string | null;
  title: string;
  notes: string | null;
  category: CalendarCategory;
  allDay: boolean;
  startTime: string | null;
  endTime: string | null;
  endDate: string | null;
  recurring: boolean;
  /** 0 = heute, 1 = morgen. */
  daysUntil: number;
}
```

- [ ] **Step 2: Service anlegen** (Muster `WasteCollectionService`)

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  CalendarEvent, CalendarEventRequest, CalendarOccurrence
} from '../models/calendar-event.model';

/** Service fuer die Haushaltskalender-API. */
@Injectable({ providedIn: 'root' })
export class CalendarService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/calendar';

  /** Expandierte Vorkommen im Zeitraum [from, to] (ISO-Daten). */
  getOccurrences(from: string, to: string): Observable<CalendarOccurrence[]> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<CalendarOccurrence[]>(`${this.baseUrl}/events`, { params })
      .pipe(catchError(this.handleError));
  }

  getUpcoming(limit = 3): Observable<CalendarOccurrence[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<CalendarOccurrence[]>(`${this.baseUrl}/upcoming`, { params })
      .pipe(catchError(this.handleError));
  }

  getEvent(id: number): Observable<CalendarEvent> {
    return this.http.get<CalendarEvent>(`${this.baseUrl}/events/${id}`)
      .pipe(catchError(this.handleError));
  }

  create(request: CalendarEventRequest): Observable<CalendarEvent> {
    return this.http.post<CalendarEvent>(`${this.baseUrl}/events`, request)
      .pipe(catchError(this.handleError));
  }

  update(id: number, request: CalendarEventRequest): Observable<CalendarEvent> {
    return this.http.put<CalendarEvent>(`${this.baseUrl}/events/${id}`, request)
      .pipe(catchError(this.handleError));
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/events/${id}`)
      .pipe(catchError(this.handleError));
  }

  /** Nur dieses Vorkommen loeschen (EXDATE). */
  deleteOccurrence(eventId: number, recurrenceDate: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/events/${eventId}/occurrences/${recurrenceDate}`)
      .pipe(catchError(this.handleError));
  }

  /** Nur dieses Vorkommen aendern (Override). */
  updateOccurrence(eventId: number, recurrenceDate: string,
                   request: CalendarEventRequest): Observable<CalendarEvent> {
    return this.http.put<CalendarEvent>(
      `${this.baseUrl}/events/${eventId}/occurrences/${recurrenceDate}`, request)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Kalender-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Kalender-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
```

- [ ] **Step 3: Kompilieren**

Run (aus `frontend/`): `npx ng build --configuration development`
Expected: Build erfolgreich

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/models/calendar-event.model.ts frontend/src/app/services/calendar.service.ts
git commit -m "feat(calendar): Angular-Model und API-Service"
```

---

### Task 10: `HubInsight`-Refactor + `calendar-insight.util` (TDD)

**Files:**
- Create: `frontend/src/app/shared/hub-insight.model.ts`
- Create: `frontend/src/app/shared/relative-day.util.ts`
- Modify: `frontend/src/app/shared/waste-insight.util.ts`
- Create: `frontend/src/app/shared/calendar-insight.util.ts`
- Test: `frontend/src/app/shared/calendar-insight.util.spec.ts`

- [ ] **Step 1: Gemeinsames Insight-Interface extrahieren**

`hub-insight.model.ts`:

```ts
/**
 * Ein fertiger Hinweis fuer den Intelligence Hub. Strukturgleich zum dortigen
 * `IntelligenceItem`: Das Dashboard rendert die Meldung selbst, weil die Styles der
 * Hub-Eintraege in seinem eigenen SCSS liegen und Angulars Style-Kapselung sie nicht
 * an eine Kind-Komponente weiterreicht.
 */
export interface HubInsight {
  readonly icon: string;
  readonly tone: 'primary' | 'secondary' | 'muted' | 'tertiary' | 'error';
  readonly title: string;
  readonly text: string;
}
```

`relative-day.util.ts` (aus dem Waste-Util extrahiert, damit Kalender und Müll denselben Wortlaut nutzen):

```ts
/** "Heute"/"Morgen"/"Übermorgen", darueber hinaus der Wochentag. */
export function relativeDayLabel(daysUntil: number, isoDate: string): string {
  switch (daysUntil) {
    case 0: return 'Heute';
    case 1: return 'Morgen';
    case 2: return 'Übermorgen';
    default: return weekdayOf(isoDate);
  }
}

/**
 * Wochentag zu einem ISO-Datum. Bewusst aus den Datumsteilen gebaut statt via
 * `new Date('2026-07-20')`: Diese Kurzform parst als UTC-Mitternacht und wuerde bei
 * negativem UTC-Offset den Vortag anzeigen.
 */
export function weekdayOf(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  return new Date(year, month - 1, day)
    .toLocaleDateString('de-DE', { weekday: 'long' });
}
```

In `waste-insight.util.ts`: das lokale `WasteInsight`-Interface durch einen Re-Export ersetzen und die lokalen Funktionen `relativeDayLabel`/`weekdayOf` löschen; stattdessen importieren:

```ts
import { HubInsight } from './hub-insight.model';
import { relativeDayLabel } from './relative-day.util';

/** @deprecated Alias — neue Aufrufer nutzen HubInsight direkt. */
export type WasteInsight = HubInsight;
```

Die `describe`-Funktion dort wird zu:

```ts
function describe(event: WasteCollectionEvent): string {
  return `${relativeDayLabel(event.daysUntil, event.date)}: ${event.label}`;
}
```

- [ ] **Step 2: Failing Test für das Kalender-Util schreiben**

`calendar-insight.util.spec.ts`:

```ts
import { buildCalendarInsights } from './calendar-insight.util';
import { CalendarOccurrence } from '../models/calendar-event.model';

function occurrence(overrides: Partial<CalendarOccurrence>): CalendarOccurrence {
  return {
    eventId: 1, occurrenceDate: '2026-07-25', recurrenceDate: null,
    title: 'Zahnarzt', notes: null, category: 'HEALTH', allDay: false,
    startTime: '14:30', endTime: null, endDate: null, recurring: false, daysUntil: 0,
    ...overrides
  };
}

describe('buildCalendarInsights', () => {
  it('liefert hoechstens drei Eintraege', () => {
    const occurrences = [0, 1, 2, 3].map(i =>
      occurrence({ eventId: i, daysUntil: i }));
    expect(buildCalendarInsights(occurrences).length).toBe(3);
  });

  it('liefert eine leere Liste, wenn nichts ansteht', () => {
    expect(buildCalendarInsights([])).toEqual([]);
  });

  it('formatiert Uhrzeit-Termine mit Tag und Uhrzeit', () => {
    const [insight] = buildCalendarInsights([occurrence({ daysUntil: 1 })]);
    expect(insight.title).toBe('Zahnarzt');
    expect(insight.text).toBe('Morgen, 14:30 Uhr');
  });

  it('formatiert ganztaegige Termine ohne Uhrzeit', () => {
    const [insight] = buildCalendarInsights([
      occurrence({ allDay: true, startTime: null, daysUntil: 0 })]);
    expect(insight.text).toBe('Heute');
  });

  it('faerbt heute und morgen rot, uebermorgen gelb, spaeter blau', () => {
    const tones = [0, 1, 2, 3].map(daysUntil =>
      buildCalendarInsights([occurrence({ daysUntil })])[0].tone);
    expect(tones).toEqual(['error', 'error', 'tertiary', 'primary']);
  });

  it('nutzt den Wochentag fuer fernere Termine', () => {
    // 29.07.2026 ist ein Mittwoch
    const [insight] = buildCalendarInsights([
      occurrence({ occurrenceDate: '2026-07-29', daysUntil: 4 })]);
    expect(insight.text).toBe('Mittwoch, 14:30 Uhr');
  });
});
```

- [ ] **Step 3: Tests laufen lassen — müssen fehlschlagen**

Run (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Neue Specs schlagen fehl (Util existiert nicht); Baseline-Fails (4) unverändert.

- [ ] **Step 4: Util implementieren**

`calendar-insight.util.ts`:

```ts
import { CalendarOccurrence } from '../models/calendar-event.model';
import { HubInsight } from './hub-insight.model';
import { relativeDayLabel } from './relative-day.util';

/** Bis einschliesslich morgen ist der Termin dringlich — der Indikator wird rot. */
const URGENT_DAYS_UNTIL = 1;
/** Uebermorgen kuendigt sich der Termin an — der Indikator wird gelb. */
const SOON_DAYS_UNTIL = 2;

/**
 * Baut aus den naechsten Vorkommen bis zu `max` einzelne Hub-Eintraege
 * (Titel = Terminname, Text = "Morgen, 14:30 Uhr").
 */
export function buildCalendarInsights(
  occurrences: CalendarOccurrence[], max = 3): HubInsight[] {
  return occurrences.slice(0, max).map(occ => ({
    icon: 'event',
    tone: toneFor(occ),
    title: occ.title,
    text: describe(occ)
  }));
}

function toneFor(occ: CalendarOccurrence): HubInsight['tone'] {
  if (occ.daysUntil <= URGENT_DAYS_UNTIL) {
    return 'error';
  }
  return occ.daysUntil === SOON_DAYS_UNTIL ? 'tertiary' : 'primary';
}

function describe(occ: CalendarOccurrence): string {
  const day = relativeDayLabel(occ.daysUntil, occ.occurrenceDate);
  return occ.allDay || !occ.startTime ? day : `${day}, ${occ.startTime} Uhr`;
}
```

- [ ] **Step 5: Tests laufen lassen — neue Specs grün, Waste-Specs weiter grün**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Baseline „4 FAILED", alle Kalender- und Waste-Specs grün.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/shared
git commit -m "feat(calendar): Hub-Insights fuer Termine, gemeinsames HubInsight-Modell"
```

---

### Task 11: Dashboard-Hub-Integration

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`

- [ ] **Step 1: Imports und Felder ergänzen**

Zu den Imports:

```ts
import { CalendarService } from '../../services/calendar.service';
import { buildCalendarInsights } from '../../shared/calendar-insight.util';
import { HubInsight } from '../../shared/hub-insight.model';
```

Nach `private readonly wasteService = inject(WasteCollectionService);`:

```ts
  private readonly calendarService = inject(CalendarService);
```

Nach `private wasteSubscription?: Subscription;`:

```ts
  private calendarSubscription?: Subscription;
```

Bei den Konstanten (nach `WASTE_REFRESH_MS`):

```ts
  /** Kalender-Hub-Eintraege alle 5 Minuten auffrischen (Termine aendern sich haeufiger als Muell). */
  private static readonly CALENDAR_REFRESH_MS = 300000;
```

Nahe dem `insights`-Feld:

```ts
  /** Zuletzt gebaute Muell-Meldung; null = nichts ansteht. */
  private wasteInsight: HubInsight | null = null;
  /** Zuletzt gebaute Kalender-Eintraege (max. 3). */
  private calendarInsights: HubInsight[] = [];
```

- [ ] **Step 2: `startWasteRefresh` auf das Kompositionsmodell umstellen**

Den `subscribe`-Block in `startWasteRefresh()` ersetzen durch:

```ts
      .subscribe(events => {
        this.wasteInsight = buildWasteInsight(events);
        this.rebuildInsights();
      });
```

Direkt darunter neue Methoden:

```ts
  /** Haelt die Termin-Eintraege im Hub aktuell (gleiches Mitternachts-Muster wie der Muell). */
  private startCalendarRefresh(): void {
    this.calendarSubscription = merge(
      interval(DashboardComponent.CALENDAR_REFRESH_MS),
      timer(this.msUntilNextMidnight(), DashboardComponent.DAY_MS)
    )
      .pipe(
        startWith(0),
        switchMap(() => this.calendarService.getUpcoming(3).pipe(catchError(() => of([]))))
      )
      .subscribe(occurrences => {
        this.calendarInsights = buildCalendarInsights(occurrences);
        this.rebuildInsights();
      });
  }

  /** Komponiert den Hub: Muell voran, dann Termine, dahinter die Platzhalter. */
  private rebuildInsights(): void {
    this.insights = [
      ...(this.wasteInsight ? [this.wasteInsight] : []),
      ...this.calendarInsights,
      ...DashboardComponent.PLACEHOLDER_INSIGHTS
    ];
  }
```

- [ ] **Step 3: Lifecycle verdrahten**

In `ngOnInit()` nach `this.startWasteRefresh();`:

```ts
    this.startCalendarRefresh();
```

In `ngOnDestroy()` nach `this.wasteSubscription?.unsubscribe();`:

```ts
    this.calendarSubscription?.unsubscribe();
```

- [ ] **Step 4: Verifizieren**

Run: `npx ng build --configuration development` und `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Build ok; Baseline „4 FAILED" unverändert (Dashboard-Spec bleibt grün).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts
git commit -m "feat(calendar): naechste Termine im Intelligence Hub"
```

---

### Task 12: `rrule.util` — Wiederholungs-Builder (TDD)

**Files:**
- Create: `frontend/src/app/shared/rrule.util.ts`
- Test: `frontend/src/app/shared/rrule.util.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

```ts
import { RecurrenceOptions, buildRrule, parseRrule } from './rrule.util';

function options(overrides: Partial<RecurrenceOptions>): RecurrenceOptions {
  return {
    freq: 'WEEKLY', interval: 1, weekdays: [], monthlyMode: 'DAY_OF_MONTH',
    endType: 'NEVER', untilDate: null, count: null,
    ...overrides
  };
}

describe('buildRrule', () => {
  it('baut eine einfache woechentliche Regel', () => {
    expect(buildRrule(options({}), '2026-07-07')).toBe('FREQ=WEEKLY');
  });

  it('baut Intervall und Wochentage ein', () => {
    expect(buildRrule(options({ interval: 2, weekdays: ['MO', 'FR'] }), '2026-07-06'))
      .toBe('FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR');
  });

  it('baut monatlich am Monatstag', () => {
    expect(buildRrule(options({ freq: 'MONTHLY' }), '2026-07-14'))
      .toBe('FREQ=MONTHLY;BYMONTHDAY=14');
  });

  it('baut monatlich am n-ten Wochentag aus dem Startdatum', () => {
    // 14.07.2026 ist der zweite Dienstag des Monats
    expect(buildRrule(options({ freq: 'MONTHLY', monthlyMode: 'NTH_WEEKDAY' }), '2026-07-14'))
      .toBe('FREQ=MONTHLY;BYDAY=2TU');
  });

  it('baut UNTIL und COUNT', () => {
    expect(buildRrule(options({ endType: 'UNTIL', untilDate: '2026-12-31' }), '2026-07-07'))
      .toBe('FREQ=WEEKLY;UNTIL=20261231');
    expect(buildRrule(options({ endType: 'COUNT', count: 10 }), '2026-07-07'))
      .toBe('FREQ=WEEKLY;COUNT=10');
  });
});

describe('parseRrule', () => {
  it('liest eine Builder-Regel zurueck (Roundtrip)', () => {
    const original = options({ interval: 2, weekdays: ['MO', 'FR'], endType: 'COUNT', count: 5 });
    const parsed = parseRrule(buildRrule(original, '2026-07-06'));
    expect(parsed).toEqual(jasmine.objectContaining({
      freq: 'WEEKLY', interval: 2, weekdays: ['MO', 'FR'], endType: 'COUNT', count: 5
    }));
  });

  it('erkennt monatlich am n-ten Wochentag', () => {
    expect(parseRrule('FREQ=MONTHLY;BYDAY=2TU')).toEqual(jasmine.objectContaining({
      freq: 'MONTHLY', monthlyMode: 'NTH_WEEKDAY'
    }));
  });

  it('liefert null fuer Regeln, die der Builder nicht abbildet', () => {
    expect(parseRrule('FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=-1')).toBeNull();
    expect(parseRrule('FREQ=HOURLY')).toBeNull();
  });
});
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

- [ ] **Step 3: Implementieren**

```ts
/** Vom Builder abgedeckte Wochentage/Frequenzen (Teilmenge von RFC 5545). */
export type Weekday = 'MO' | 'TU' | 'WE' | 'TH' | 'FR' | 'SA' | 'SU';
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY';

/** Zustand des Wiederholungs-Builders im Termindialog. */
export interface RecurrenceOptions {
  freq: Frequency;
  /** >= 1; "alle 2 Wochen" = 2. */
  interval: number;
  /** Nur bei WEEKLY. */
  weekdays: Weekday[];
  /** Nur bei MONTHLY: am Monatstag des Starts oder am n-ten Wochentag des Starts. */
  monthlyMode: 'DAY_OF_MONTH' | 'NTH_WEEKDAY';
  endType: 'NEVER' | 'UNTIL' | 'COUNT';
  /** ISO-Datum, bei endType UNTIL. */
  untilDate: string | null;
  /** Bei endType COUNT. */
  count: number | null;
}

/** Index = Date.getDay() (0 = Sonntag). */
const WEEKDAY_CODES: Weekday[] = ['SU', 'MO', 'TU', 'WE', 'TH', 'FR', 'SA'];

/** Baut aus den Builder-Optionen die RRULE; NTH_WEEKDAY leitet sich vom Startdatum ab. */
export function buildRrule(options: RecurrenceOptions, startDate: string): string {
  const parts = [`FREQ=${options.freq}`];
  if (options.interval > 1) {
    parts.push(`INTERVAL=${options.interval}`);
  }
  if (options.freq === 'WEEKLY' && options.weekdays.length > 0) {
    parts.push(`BYDAY=${options.weekdays.join(',')}`);
  }
  if (options.freq === 'MONTHLY') {
    const [year, month, day] = startDate.split('-').map(Number);
    if (options.monthlyMode === 'DAY_OF_MONTH') {
      parts.push(`BYMONTHDAY=${day}`);
    } else {
      const nth = Math.floor((day - 1) / 7) + 1;
      const weekday = WEEKDAY_CODES[new Date(year, month - 1, day).getDay()];
      parts.push(`BYDAY=${nth}${weekday}`);
    }
  }
  if (options.endType === 'UNTIL' && options.untilDate) {
    parts.push(`UNTIL=${options.untilDate.replaceAll('-', '')}`);
  }
  if (options.endType === 'COUNT' && options.count) {
    parts.push(`COUNT=${options.count}`);
  }
  return parts.join(';');
}

/**
 * Uebersetzt eine RRULE zurueck in Builder-Optionen.
 *
 * @returns null, wenn die Regel Features nutzt, die der Builder nicht abbildet —
 *          der Dialog zeigt sie dann nur im "Erweitert"-Modus als Rohtext.
 */
export function parseRrule(rrule: string): RecurrenceOptions | null {
  const entries = new Map<string, string>();
  for (const part of rrule.split(';').filter(p => p.length > 0)) {
    const [key, value] = part.split('=');
    if (!key || value === undefined) {
      return null;
    }
    entries.set(key.toUpperCase(), value);
  }

  const freq = entries.get('FREQ') as Frequency | undefined;
  if (!freq || !['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'].includes(freq)) {
    return null;
  }
  const supported = new Set(['FREQ', 'INTERVAL', 'BYDAY', 'BYMONTHDAY', 'UNTIL', 'COUNT']);
  if ([...entries.keys()].some(key => !supported.has(key))) {
    return null;
  }

  const result: RecurrenceOptions = {
    freq,
    interval: entries.has('INTERVAL') ? Number(entries.get('INTERVAL')) : 1,
    weekdays: [],
    monthlyMode: 'DAY_OF_MONTH',
    endType: 'NEVER',
    untilDate: null,
    count: null
  };
  if (Number.isNaN(result.interval) || result.interval < 1) {
    return null;
  }

  const byday = entries.get('BYDAY');
  if (byday !== undefined) {
    if (freq === 'WEEKLY') {
      const days = byday.split(',');
      if (!days.every(d => WEEKDAY_CODES.includes(d as Weekday))) {
        return null;
      }
      result.weekdays = days as Weekday[];
    } else if (freq === 'MONTHLY' && /^[1-5](MO|TU|WE|TH|FR|SA|SU)$/.test(byday)) {
      result.monthlyMode = 'NTH_WEEKDAY';
    } else {
      return null;
    }
  }
  if (entries.has('BYMONTHDAY') && (freq !== 'MONTHLY' || byday !== undefined)) {
    return null;
  }

  const until = entries.get('UNTIL');
  const count = entries.get('COUNT');
  if (until !== undefined && count !== undefined) {
    return null;
  }
  if (until !== undefined) {
    if (!/^\d{8}$/.test(until)) {
      return null;
    }
    result.endType = 'UNTIL';
    result.untilDate = `${until.slice(0, 4)}-${until.slice(4, 6)}-${until.slice(6, 8)}`;
  }
  if (count !== undefined) {
    result.endType = 'COUNT';
    result.count = Number(count);
    if (Number.isNaN(result.count) || result.count < 1) {
      return null;
    }
  }
  return result;
}
```

- [ ] **Step 4: Tests laufen lassen — grün**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Baseline „4 FAILED", rrule-Specs grün.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/rrule.util.ts frontend/src/app/shared/rrule.util.spec.ts
git commit -m "feat(calendar): RRULE-Builder und -Parser fuer den Termindialog"
```

---

### Task 13: `month-grid.util` (TDD)

**Files:**
- Create: `frontend/src/app/shared/month-grid.util.ts`
- Test: `frontend/src/app/shared/month-grid.util.spec.ts`

- [ ] **Step 1: Failing Tests schreiben**

```ts
import { buildMonthGrid } from './month-grid.util';

describe('buildMonthGrid', () => {
  const today = new Date(2026, 6, 25); // 25.07.2026

  it('beginnt jede Woche am Montag', () => {
    // Juli 2026 beginnt an einem Mittwoch -> erste Zelle ist Montag, der 29.06.
    const grid = buildMonthGrid(2026, 7, today);
    expect(grid[0][0].isoDate).toBe('2026-06-29');
    expect(grid[0][0].inMonth).toBeFalse();
    expect(grid[0][2].isoDate).toBe('2026-07-01');
  });

  it('endet mit dem Sonntag der letzten Monatswoche', () => {
    const grid = buildMonthGrid(2026, 7, today);
    const lastWeek = grid[grid.length - 1];
    expect(lastWeek[6].isoDate).toBe('2026-08-02');
    expect(grid.flat().filter(d => d.inMonth).length).toBe(31);
  });

  it('markiert heute', () => {
    const grid = buildMonthGrid(2026, 7, today);
    const todayCell = grid.flat().find(d => d.isoDate === '2026-07-25');
    expect(todayCell?.isToday).toBeTrue();
  });

  it('hat immer Wochen mit sieben Tagen', () => {
    for (const month of [1, 2, 6, 12]) {
      for (const week of buildMonthGrid(2026, month, today)) {
        expect(week.length).toBe(7);
      }
    }
  });
});
```

Kontrolle der Erwartungen: 01.07.2026 ist ein Mittwoch, also Montag davor = 29.06.; 31.07.2026 ist ein Freitag, Sonntag danach = 02.08.

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

- [ ] **Step 3: Implementieren**

```ts
/** Eine Tageszelle des Monatsrasters. */
export interface MonthDay {
  /** ISO-Datum, z. B. "2026-07-25". */
  isoDate: string;
  dayOfMonth: number;
  /** false fuer Randtage der Nachbarmonate. */
  inMonth: boolean;
  isToday: boolean;
}

/**
 * Monatsraster als Wochen (Mo–So) inklusive Randtagen der Nachbarmonate.
 *
 * @param month 1-12
 */
export function buildMonthGrid(year: number, month: number, today: Date): MonthDay[][] {
  const firstOfMonth = new Date(year, month - 1, 1);
  const mondayOffset = (firstOfMonth.getDay() + 6) % 7;
  const cursor = new Date(year, month - 1, 1 - mondayOffset);

  const weeks: MonthDay[][] = [];
  do {
    const week: MonthDay[] = [];
    for (let i = 0; i < 7; i++) {
      week.push({
        isoDate: toIso(cursor),
        dayOfMonth: cursor.getDate(),
        inMonth: cursor.getMonth() === month - 1,
        isToday: sameDay(cursor, today)
      });
      cursor.setDate(cursor.getDate() + 1);
    }
    weeks.push(week);
  } while (cursor.getMonth() === month - 1);
  return weeks;
}

/** ISO-Datum aus den lokalen Datumsteilen (kein toISOString — das kippt per UTC den Tag). */
function toIso(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}
```

- [ ] **Step 4: Tests laufen lassen — grün**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/month-grid.util.ts frontend/src/app/shared/month-grid.util.spec.ts
git commit -m "feat(calendar): Monatsraster-Utility"
```

---

### Task 14: Kalenderseite — Route, Navigation, Monatsansicht (read-only)

**Files:**
- Create: `frontend/src/app/pages/calendar/calendar.component.ts` / `.html` / `.scss`
- Modify: `frontend/src/app/app.routes.ts` (vor der `'**'`-Route)
- Modify: `frontend/src/app/components/header/header.component.ts` (`navLinks`)

Der Dialog kommt in Task 15 — hier entsteht die Seite mit Grid, Monatsnavigation und Datenladung. Die in Task 15 benutzten Methoden `openCreate`/`openEdit` werden hier bereits als leere Hüllen angelegt, damit das Template stabil bleibt.

- [ ] **Step 1: Route ergänzen** (in `app.routes.ts` vor dem `'**'`-Eintrag)

```ts
  {
    path: 'calendar',
    loadComponent: () => import('./pages/calendar/calendar.component').then(m => m.CalendarComponent),
    title: 'Kalender - Household Manager'
  },
```

- [ ] **Step 2: Nav-Link ergänzen** (in `header.component.ts`, `navLinks`, nach dem `/consumption`-Eintrag)

```ts
    { path: '/calendar', label: 'Kalender' },
```

- [ ] **Step 3: Komponente anlegen** (`calendar.component.ts`)

```ts
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CalendarService } from '../../services/calendar.service';
import {
  CATEGORY_META, CalendarCategory, CalendarOccurrence
} from '../../models/calendar-event.model';
import { MonthDay, buildMonthGrid } from '../../shared/month-grid.util';

/** Wie viele Termin-Chips eine Tageszelle zeigt; der Rest wird "+n weitere". */
const DAY_CHIP_LIMIT = 3;

/**
 * Haushaltskalender: Monatsraster mit Termin-Chips; Anlegen/Bearbeiten laeuft
 * ueber den Termindialog (siehe openCreate/openEdit).
 */
@Component({
  selector: 'app-calendar',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './calendar.component.html',
  styleUrl: './calendar.component.scss'
})
export class CalendarComponent implements OnInit {
  private readonly calendarService = inject(CalendarService);

  readonly categoryMeta = CATEGORY_META;
  readonly categories = Object.keys(CATEGORY_META) as CalendarCategory[];
  readonly weekdayLabels = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
  readonly dayChipLimit = DAY_CHIP_LIMIT;

  /** Angezeigter Monat. */
  viewYear = new Date().getFullYear();
  viewMonth = new Date().getMonth() + 1;

  grid: MonthDay[][] = [];
  /** Vorkommen des sichtbaren Rasters, gruppiert nach ISO-Datum. */
  occurrencesByDate = new Map<string, CalendarOccurrence[]>();
  loadError: string | null = null;

  ngOnInit(): void {
    this.rebuildGrid();
  }

  get monthLabel(): string {
    return new Date(this.viewYear, this.viewMonth - 1, 1)
      .toLocaleDateString('de-DE', { month: 'long', year: 'numeric' });
  }

  previousMonth(): void {
    this.shiftMonth(-1);
  }

  nextMonth(): void {
    this.shiftMonth(1);
  }

  goToToday(): void {
    const now = new Date();
    this.viewYear = now.getFullYear();
    this.viewMonth = now.getMonth() + 1;
    this.rebuildGrid();
  }

  chipsFor(day: MonthDay): CalendarOccurrence[] {
    return this.occurrencesByDate.get(day.isoDate) ?? [];
  }

  overflowCount(day: MonthDay): number {
    return Math.max(0, this.chipsFor(day).length - DAY_CHIP_LIMIT);
  }

  colorFor(occurrence: CalendarOccurrence): string {
    return CATEGORY_META[occurrence.category].color;
  }

  /** Wird in Task 15 mit dem Termindialog gefuellt. */
  openCreate(day: MonthDay): void {
  }

  /** Wird in Task 15 mit dem Termindialog gefuellt. */
  openEdit(occurrence: CalendarOccurrence, clickEvent: Event): void {
    clickEvent.stopPropagation();
  }

  private shiftMonth(delta: number): void {
    const shifted = new Date(this.viewYear, this.viewMonth - 1 + delta, 1);
    this.viewYear = shifted.getFullYear();
    this.viewMonth = shifted.getMonth() + 1;
    this.rebuildGrid();
  }

  protected rebuildGrid(): void {
    this.grid = buildMonthGrid(this.viewYear, this.viewMonth, new Date());
    this.loadOccurrences();
  }

  private loadOccurrences(): void {
    const from = this.grid[0][0].isoDate;
    const lastWeek = this.grid[this.grid.length - 1];
    const to = lastWeek[6].isoDate;
    this.calendarService.getOccurrences(from, to).subscribe({
      next: occurrences => {
        this.loadError = null;
        this.occurrencesByDate = new Map();
        for (const occ of occurrences) {
          const list = this.occurrencesByDate.get(occ.occurrenceDate) ?? [];
          list.push(occ);
          this.occurrencesByDate.set(occ.occurrenceDate, list);
        }
      },
      error: (err: Error) => (this.loadError = err.message)
    });
  }
}
```

- [ ] **Step 4: Template anlegen** (`calendar.component.html`)

```html
<div class="calendar container">
  <header class="calendar__header">
    <h1 class="calendar__title">Kalender</h1>
    <div class="calendar__nav">
      <button type="button" class="calendar__nav-btn" (click)="previousMonth()"
              aria-label="Voriger Monat">‹</button>
      <span class="calendar__month">{{ monthLabel }}</span>
      <button type="button" class="calendar__nav-btn" (click)="nextMonth()"
              aria-label="Naechster Monat">›</button>
      <button type="button" class="calendar__today-btn" (click)="goToToday()">Heute</button>
    </div>
  </header>

  <p *ngIf="loadError" class="calendar__error">{{ loadError }}</p>

  <div class="calendar__grid" role="grid">
    <div class="calendar__weekday" *ngFor="let label of weekdayLabels">{{ label }}</div>

    <ng-container *ngFor="let week of grid">
      <div *ngFor="let day of week"
           class="calendar__day"
           [class.calendar__day--outside]="!day.inMonth"
           [class.calendar__day--today]="day.isToday"
           (click)="openCreate(day)">
        <span class="calendar__day-number">{{ day.dayOfMonth }}</span>
        <div class="calendar__chips">
          <button *ngFor="let occ of chipsFor(day) | slice:0:dayChipLimit"
                  type="button"
                  class="calendar__chip"
                  [style.--chip-color]="colorFor(occ)"
                  (click)="openEdit(occ, $event)">
            <span class="calendar__chip-time" *ngIf="!occ.allDay && occ.startTime">
              {{ occ.startTime }}
            </span>
            {{ occ.title }}
          </button>
          <span *ngIf="overflowCount(day) > 0" class="calendar__more">
            +{{ overflowCount(day) }} weitere
          </span>
        </div>
      </div>
    </ng-container>
  </div>
</div>
```

- [ ] **Step 5: Styles anlegen** (`calendar.component.scss`)

```scss
.calendar {
  padding: 1.5rem 0 3rem;

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 1rem;
    margin-bottom: 1.25rem;
  }

  &__title {
    margin: 0;
    font-size: 1.6rem;
  }

  &__nav {
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }

  &__month {
    min-width: 11rem;
    text-align: center;
    font-weight: 600;
  }

  &__nav-btn,
  &__today-btn {
    border: 1px solid rgba(0, 0, 0, 0.15);
    background: transparent;
    border-radius: 0.5rem;
    padding: 0.35rem 0.75rem;
    font-size: 1rem;
    cursor: pointer;

    &:hover {
      background: rgba(0, 0, 0, 0.05);
    }
  }

  &__error {
    color: #c62828;
    margin-bottom: 1rem;
  }

  &__grid {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 1px;
    background: rgba(0, 0, 0, 0.12);
    border: 1px solid rgba(0, 0, 0, 0.12);
    border-radius: 0.75rem;
    overflow: hidden;
  }

  &__weekday {
    background: rgba(0, 0, 0, 0.04);
    padding: 0.5rem;
    text-align: center;
    font-size: 0.8rem;
    font-weight: 600;
    text-transform: uppercase;
  }

  &__day {
    background: #fff;
    min-height: 7rem;
    padding: 0.35rem;
    cursor: pointer;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;

    &:hover {
      background: rgba(0, 0, 0, 0.03);
    }

    &--outside {
      background: rgba(0, 0, 0, 0.02);

      .calendar__day-number {
        opacity: 0.4;
      }
    }

    &--today .calendar__day-number {
      background: #1976d2;
      color: #fff;
      border-radius: 50%;
    }
  }

  &__day-number {
    width: 1.6rem;
    height: 1.6rem;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 0.85rem;
    font-weight: 600;
  }

  &__chips {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
    overflow: hidden;
  }

  &__chip {
    --chip-color: #64b5f6;
    display: flex;
    gap: 0.3rem;
    align-items: baseline;
    border: none;
    border-left: 3px solid var(--chip-color);
    background: color-mix(in srgb, var(--chip-color) 18%, transparent);
    border-radius: 0.3rem;
    padding: 0.15rem 0.4rem;
    font-size: 0.75rem;
    text-align: left;
    cursor: pointer;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__chip-time {
    font-weight: 600;
    flex-shrink: 0;
  }

  &__more {
    font-size: 0.7rem;
    opacity: 0.7;
    padding-left: 0.4rem;
  }
}
```

- [ ] **Step 6: Verifizieren**

Run: `npx ng build --configuration development`
Expected: Build ok. Optional manuell: Backend + `npm start`, `http://localhost:4200/calendar` zeigt das Monatsraster.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/calendar frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(calendar): Kalenderseite mit Monatsraster und Navigation"
```

---

### Task 15: Termindialog mit Wiederholungs-Builder und Serien-Scope

**Files:**
- Modify: `frontend/src/app/pages/calendar/calendar.component.ts` / `.html` / `.scss`

- [ ] **Step 1: Dialog-Zustand und Formularlogik ergänzen** (in `calendar.component.ts`)

Zusätzliche Imports:

```ts
import {
  CalendarEvent, CalendarEventRequest
} from '../../models/calendar-event.model';
import {
  Frequency, RecurrenceOptions, Weekday, buildRrule, parseRrule
} from '../../shared/rrule.util';
```

Neue Typen/Felder in der Klasse:

```ts
  /** Formularzustand des Termindialogs (Strings, wie die Inputs sie liefern). */
  form = this.emptyForm();
  /** Wiederholungs-Builder; freq 'NONE' = Einzeltermin. */
  recurrence = CalendarComponent.defaultRecurrence();
  /** Roh-RRULE im "Erweitert"-Modus; hat Vorrang vor dem Builder. */
  advancedMode = false;
  rawRrule = '';

  dialogOpen = false;
  /** Beim Bearbeiten gesetzt; null = Anlegen. */
  editing: { event: CalendarEvent; occurrence: CalendarOccurrence } | null = null;
  /** Offene Scope-Frage bei Serien ('save' | 'delete'); null = keine. */
  scopeQuestion: 'save' | 'delete' | null = null;
  dialogError: string | null = null;
  saving = false;

  readonly weekdayOptions: { code: Weekday; label: string }[] = [
    { code: 'MO', label: 'Mo' }, { code: 'TU', label: 'Di' }, { code: 'WE', label: 'Mi' },
    { code: 'TH', label: 'Do' }, { code: 'FR', label: 'Fr' }, { code: 'SA', label: 'Sa' },
    { code: 'SU', label: 'So' }
  ];
  readonly frequencyOptions: { code: 'NONE' | Frequency; label: string }[] = [
    { code: 'NONE', label: 'Keine' }, { code: 'DAILY', label: 'Täglich' },
    { code: 'WEEKLY', label: 'Wöchentlich' }, { code: 'MONTHLY', label: 'Monatlich' },
    { code: 'YEARLY', label: 'Jährlich' }
  ];

  private emptyForm() {
    return {
      title: '', notes: '', category: 'GENERAL' as CalendarCategory,
      allDay: true, startDate: '', startTime: '', endTime: '', endDate: ''
    };
  }

  private static defaultRecurrence(): RecurrenceOptions & { freq: 'NONE' | Frequency } {
    return {
      freq: 'NONE' as 'NONE' | Frequency, interval: 1, weekdays: [],
      monthlyMode: 'DAY_OF_MONTH', endType: 'NEVER', untilDate: null, count: null
    } as RecurrenceOptions & { freq: 'NONE' | Frequency };
  }
```

Die leeren Hüllen aus Task 14 füllen und die Dialog-Methoden ergänzen:

```ts
  openCreate(day: MonthDay): void {
    this.editing = null;
    this.form = this.emptyForm();
    this.form.startDate = day.isoDate;
    this.recurrence = CalendarComponent.defaultRecurrence();
    this.advancedMode = false;
    this.rawRrule = '';
    this.dialogError = null;
    this.dialogOpen = true;
  }

  /** Laedt die Stammdaten (bei Serien die Master-Zeile) und oeffnet den Dialog. */
  openEdit(occurrence: CalendarOccurrence, clickEvent: Event): void {
    clickEvent.stopPropagation();
    this.calendarService.getEvent(occurrence.eventId).subscribe({
      next: event => {
        this.editing = { event, occurrence };
        this.form = {
          title: event.title,
          notes: event.notes ?? '',
          category: event.category,
          allDay: event.allDay,
          startDate: event.startDate,
          startTime: event.startTime ?? '',
          endTime: event.endTime ?? '',
          endDate: event.endDate ?? ''
        };
        const parsed = event.rrule ? parseRrule(event.rrule) : null;
        if (event.rrule && !parsed) {
          // Regel jenseits des Builders -> nur als Rohtext editierbar
          this.advancedMode = true;
          this.rawRrule = event.rrule;
          this.recurrence = CalendarComponent.defaultRecurrence();
        } else {
          this.advancedMode = false;
          this.rawRrule = event.rrule ?? '';
          this.recurrence = parsed
            ? { ...parsed } as RecurrenceOptions & { freq: 'NONE' | Frequency }
            : CalendarComponent.defaultRecurrence();
        }
        this.dialogError = null;
        this.dialogOpen = true;
      },
      error: (err: Error) => (this.loadError = err.message)
    });
  }

  closeDialog(): void {
    this.dialogOpen = false;
    this.scopeQuestion = null;
    this.editing = null;
  }

  toggleWeekday(code: Weekday): void {
    const index = this.recurrence.weekdays.indexOf(code);
    if (index >= 0) {
      this.recurrence.weekdays.splice(index, 1);
    } else {
      this.recurrence.weekdays.push(code);
    }
  }

  /** Speichern-Klick: Serien fragen zuerst nach dem Geltungsbereich. */
  onSaveClicked(): void {
    if (this.editing && this.editing.event.recurring) {
      this.scopeQuestion = 'save';
      return;
    }
    this.save('series');
  }

  /** Loeschen-Klick: Serien fragen zuerst nach dem Geltungsbereich. */
  onDeleteClicked(): void {
    if (!this.editing) {
      return;
    }
    if (this.editing.event.recurring) {
      this.scopeQuestion = 'delete';
      return;
    }
    this.performDelete('series');
  }

  answerScope(scope: 'occurrence' | 'series'): void {
    const question = this.scopeQuestion;
    this.scopeQuestion = null;
    if (question === 'save') {
      this.save(scope);
    } else if (question === 'delete') {
      this.performDelete(scope);
    }
  }

  private save(scope: 'occurrence' | 'series'): void {
    const request = this.buildRequest(scope);
    if (!request) {
      return;
    }
    this.saving = true;
    const call = !this.editing
      ? this.calendarService.create(request)
      : scope === 'occurrence'
        ? this.calendarService.updateOccurrence(
            this.editing.event.id, this.editing.occurrence.recurrenceDate!, request)
        : this.calendarService.update(this.editing.event.id, request);
    call.subscribe({
      next: () => {
        this.saving = false;
        this.closeDialog();
        this.rebuildGrid();
      },
      error: (err: Error) => {
        this.saving = false;
        this.dialogError = err.message;
      }
    });
  }

  private performDelete(scope: 'occurrence' | 'series'): void {
    if (!this.editing) {
      return;
    }
    this.saving = true;
    const call = scope === 'occurrence'
      ? this.calendarService.deleteOccurrence(
          this.editing.event.id, this.editing.occurrence.recurrenceDate!)
      : this.calendarService.delete(this.editing.event.id);
    call.subscribe({
      next: () => {
        this.saving = false;
        this.closeDialog();
        this.rebuildGrid();
      },
      error: (err: Error) => {
        this.saving = false;
        this.dialogError = err.message;
      }
    });
  }

  /** Formular -> Request; einfache Client-Vorpruefung, die harte Prueft das Backend. */
  private buildRequest(scope: 'occurrence' | 'series'): CalendarEventRequest | null {
    if (!this.form.title.trim()) {
      this.dialogError = 'Der Titel darf nicht leer sein.';
      return null;
    }
    if (!this.form.startDate) {
      this.dialogError = 'Das Startdatum fehlt.';
      return null;
    }
    if (!this.form.allDay && !this.form.startTime) {
      this.dialogError = 'Ein Termin mit Uhrzeit braucht eine Start-Uhrzeit.';
      return null;
    }
    // "Nur dieser Termin" traegt nie eine RRULE — das Vorkommen bleibt Teil der Serie
    const rrule = scope === 'occurrence' ? null : this.currentRrule();
    return {
      title: this.form.title.trim(),
      notes: this.form.notes.trim() || null,
      category: this.form.category,
      allDay: this.form.allDay,
      startDate: this.form.startDate,
      startTime: this.form.allDay ? null : this.form.startTime || null,
      endTime: this.form.allDay ? null : this.form.endTime || null,
      endDate: this.form.endDate || null,
      rrule
    };
  }

  private currentRrule(): string | null {
    if (this.advancedMode) {
      return this.rawRrule.trim() || null;
    }
    if (this.recurrence.freq === 'NONE') {
      return null;
    }
    return buildRrule(this.recurrence as RecurrenceOptions, this.form.startDate);
  }
```

- [ ] **Step 2: Dialog-Template ergänzen** (in `calendar.component.html`, vor dem schließenden `</div>` des Containers)

```html
  <!-- Termindialog -->
  <div *ngIf="dialogOpen" class="calendar__overlay" (click)="closeDialog()">
    <div class="calendar__dialog" (click)="$event.stopPropagation()">
      <h2 class="calendar__dialog-title">
        {{ editing ? 'Termin bearbeiten' : 'Neuer Termin' }}
      </h2>

      <p *ngIf="dialogError" class="calendar__error">{{ dialogError }}</p>

      <label class="calendar__field">
        <span>Titel</span>
        <input type="text" [(ngModel)]="form.title" name="title" maxlength="200">
      </label>

      <label class="calendar__field">
        <span>Kategorie</span>
        <select [(ngModel)]="form.category" name="category">
          <option *ngFor="let cat of categories" [value]="cat">
            {{ categoryMeta[cat].label }}
          </option>
        </select>
      </label>

      <label class="calendar__field calendar__field--inline">
        <input type="checkbox" [(ngModel)]="form.allDay" name="allDay">
        <span>Ganztägig</span>
      </label>

      <div class="calendar__field-row">
        <label class="calendar__field">
          <span>Datum</span>
          <input type="date" [(ngModel)]="form.startDate" name="startDate">
        </label>
        <label class="calendar__field" *ngIf="form.allDay">
          <span>Enddatum (optional)</span>
          <input type="date" [(ngModel)]="form.endDate" name="endDate">
        </label>
        <label class="calendar__field" *ngIf="!form.allDay">
          <span>Von</span>
          <input type="time" [(ngModel)]="form.startTime" name="startTime">
        </label>
        <label class="calendar__field" *ngIf="!form.allDay">
          <span>Bis (optional)</span>
          <input type="time" [(ngModel)]="form.endTime" name="endTime">
        </label>
      </div>

      <label class="calendar__field">
        <span>Notizen</span>
        <textarea [(ngModel)]="form.notes" name="notes" rows="2"></textarea>
      </label>

      <!-- Wiederholung -->
      <fieldset class="calendar__recurrence">
        <legend>Wiederholung</legend>

        <div *ngIf="!advancedMode">
          <label class="calendar__field">
            <span>Häufigkeit</span>
            <select [(ngModel)]="recurrence.freq" name="freq">
              <option *ngFor="let opt of frequencyOptions" [value]="opt.code">
                {{ opt.label }}
              </option>
            </select>
          </label>

          <ng-container *ngIf="recurrence.freq !== 'NONE'">
            <label class="calendar__field">
              <span>Intervall (alle n)</span>
              <input type="number" min="1" [(ngModel)]="recurrence.interval" name="interval">
            </label>

            <div class="calendar__weekday-picker" *ngIf="recurrence.freq === 'WEEKLY'">
              <button *ngFor="let day of weekdayOptions" type="button"
                      class="calendar__weekday-btn"
                      [class.calendar__weekday-btn--active]="recurrence.weekdays.includes(day.code)"
                      (click)="toggleWeekday(day.code)">
                {{ day.label }}
              </button>
            </div>

            <label class="calendar__field" *ngIf="recurrence.freq === 'MONTHLY'">
              <span>Monatlich am</span>
              <select [(ngModel)]="recurrence.monthlyMode" name="monthlyMode">
                <option value="DAY_OF_MONTH">gleichen Monatstag</option>
                <option value="NTH_WEEKDAY">n-ten Wochentag (aus Startdatum)</option>
              </select>
            </label>

            <label class="calendar__field">
              <span>Ende</span>
              <select [(ngModel)]="recurrence.endType" name="endType">
                <option value="NEVER">Nie</option>
                <option value="UNTIL">Am Datum</option>
                <option value="COUNT">Nach Anzahl</option>
              </select>
            </label>
            <label class="calendar__field" *ngIf="recurrence.endType === 'UNTIL'">
              <span>Bis einschließlich</span>
              <input type="date" [(ngModel)]="recurrence.untilDate" name="untilDate">
            </label>
            <label class="calendar__field" *ngIf="recurrence.endType === 'COUNT'">
              <span>Anzahl Termine</span>
              <input type="number" min="1" [(ngModel)]="recurrence.count" name="count">
            </label>
          </ng-container>
        </div>

        <label class="calendar__field" *ngIf="advancedMode">
          <span>RRULE (Expertenmodus)</span>
          <input type="text" [(ngModel)]="rawRrule" name="rawRrule"
                 placeholder="z. B. FREQ=MONTHLY;BYDAY=-1FR">
        </label>

        <button type="button" class="calendar__link-btn" (click)="advancedMode = !advancedMode">
          {{ advancedMode ? 'Zum einfachen Modus' : 'Erweitert (RRULE direkt eingeben)' }}
        </button>
      </fieldset>

      <div class="calendar__dialog-actions">
        <button type="button" class="calendar__btn calendar__btn--danger"
                *ngIf="editing" [disabled]="saving" (click)="onDeleteClicked()">
          Löschen
        </button>
        <span class="calendar__spacer"></span>
        <button type="button" class="calendar__btn" (click)="closeDialog()">Abbrechen</button>
        <button type="button" class="calendar__btn calendar__btn--primary"
                [disabled]="saving" (click)="onSaveClicked()">
          Speichern
        </button>
      </div>
    </div>
  </div>

  <!-- Scope-Frage bei Serien -->
  <div *ngIf="scopeQuestion" class="calendar__overlay calendar__overlay--top"
       (click)="scopeQuestion = null">
    <div class="calendar__dialog calendar__dialog--small" (click)="$event.stopPropagation()">
      <h3 class="calendar__dialog-title">
        {{ scopeQuestion === 'delete' ? 'Serientermin löschen' : 'Serientermin ändern' }}
      </h3>
      <p>Soll die Änderung nur dieses Vorkommen oder die ganze Serie betreffen?</p>
      <div class="calendar__dialog-actions">
        <button type="button" class="calendar__btn" (click)="answerScope('occurrence')">
          Nur diesen Termin
        </button>
        <button type="button" class="calendar__btn calendar__btn--primary"
                (click)="answerScope('series')">
          Ganze Serie
        </button>
      </div>
    </div>
  </div>
```

- [ ] **Step 3: Dialog-Styles ergänzen** (in `calendar.component.scss` anhängen)

```scss
.calendar {
  &__overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 100;

    &--top {
      z-index: 110;
    }
  }

  &__dialog {
    background: #fff;
    border-radius: 0.75rem;
    padding: 1.5rem;
    width: min(34rem, calc(100vw - 2rem));
    max-height: calc(100vh - 4rem);
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 0.75rem;

    &--small {
      width: min(26rem, calc(100vw - 2rem));
    }
  }

  &__dialog-title {
    margin: 0;
    font-size: 1.2rem;
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
    font-size: 0.85rem;

    input,
    select,
    textarea {
      padding: 0.4rem 0.5rem;
      border: 1px solid rgba(0, 0, 0, 0.25);
      border-radius: 0.4rem;
      font: inherit;
    }

    &--inline {
      flex-direction: row;
      align-items: center;
      gap: 0.5rem;
    }
  }

  &__field-row {
    display: flex;
    gap: 0.75rem;

    .calendar__field {
      flex: 1;
    }
  }

  &__recurrence {
    border: 1px solid rgba(0, 0, 0, 0.15);
    border-radius: 0.5rem;
    padding: 0.75rem;
    display: flex;
    flex-direction: column;
    gap: 0.6rem;

    legend {
      font-size: 0.85rem;
      font-weight: 600;
      padding: 0 0.3rem;
    }
  }

  &__weekday-picker {
    display: flex;
    gap: 0.3rem;
  }

  &__weekday-btn {
    border: 1px solid rgba(0, 0, 0, 0.25);
    background: transparent;
    border-radius: 0.4rem;
    padding: 0.3rem 0.5rem;
    cursor: pointer;
    font-size: 0.8rem;

    &--active {
      background: #1976d2;
      border-color: #1976d2;
      color: #fff;
    }
  }

  &__link-btn {
    align-self: flex-start;
    border: none;
    background: none;
    color: #1976d2;
    cursor: pointer;
    padding: 0;
    font-size: 0.8rem;
    text-decoration: underline;
  }

  &__dialog-actions {
    display: flex;
    gap: 0.5rem;
    margin-top: 0.5rem;
  }

  &__spacer {
    flex: 1;
  }

  &__btn {
    border: 1px solid rgba(0, 0, 0, 0.25);
    background: transparent;
    border-radius: 0.5rem;
    padding: 0.45rem 0.9rem;
    cursor: pointer;
    font: inherit;

    &--primary {
      background: #1976d2;
      border-color: #1976d2;
      color: #fff;
    }

    &--danger {
      border-color: #c62828;
      color: #c62828;
    }

    &:disabled {
      opacity: 0.5;
      cursor: default;
    }
  }
}
```

- [ ] **Step 4: Verifizieren**

Run: `npx ng build --configuration development` und `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: Build ok; Baseline „4 FAILED" unverändert.

Manueller Smoke-Test (Backend läuft, `npm start`):
1. `http://localhost:4200/calendar` → Tag anklicken → Termin „Test" anlegen → Chip erscheint.
2. Termin mit „Wöchentlich" anlegen → Chips in Folgewochen sichtbar.
3. Ein Vorkommen anklicken → „Löschen" → „Nur diesen Termin" → nur dieses Vorkommen verschwindet.
4. Dashboard öffnen → Termin erscheint im Intelligence Hub.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/pages/calendar
git commit -m "feat(calendar): Termindialog mit Wiederholungs-Builder und Serien-Scope"
```

---

### Task 16: Abschlussverifikation & Doku

**Files:**
- Modify: `CLAUDE.md` (Abschnitt bei den Features/Integrationen)

- [ ] **Step 1: Voller Backend-Testlauf**

Run: `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'; mvn test -q` (aus `backend/`)
Expected: Nur die zwei bekannten DB-Umgebungsfails.

- [ ] **Step 2: Voller Frontend-Testlauf + Produktions-Build**

Run (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless` und `npx ng build --configuration production`
Expected: Baseline „4 FAILED"; Build erfolgreich.

- [ ] **Step 3: CLAUDE.md ergänzen** (neuer Unterabschnitt bei den Integrationen)

```markdown
### Haushaltskalender
- Pflegbarer Kalender (Seite `pages/calendar/`, Route `calendar`): Monatsraster + Termindialog mit Wiederholungs-Builder; volle RRULE-Mächtigkeit über den „Erweitert"-Modus (Roh-RRULE)
- Eine DB-Zeile pro Termin/Serie (`calendar_events`); Serien werden on-the-fly expandiert (`RecurrenceExpansionService`, einzige lib-recur-Stelle; Achtung: dmfs-`DateTime` zählt Monate 0-basiert). Einzelvorkommen löschen = EXDATE, ändern = Override-Zeile (`recurring_parent_id` + `recurrence_date`)
- API `/api/v1/calendar`: `events?from&to` (expandierte Vorkommen), `upcoming?limit`, CRUD unter `events/{id}`, Occurrence-Endpoints `events/{id}/occurrences/{date}`; Fenster ≤ 1 Jahr, Expansion ≤ 1000 Vorkommen
- Intelligence Hub zeigt die nächsten bis zu 3 Termine als eigene Einträge (Muster Müllabfuhr; `calendar-insight.util.ts`)
- **Bewusste v1-Kompromisse:** `getOccurrences` lädt per `findAll()` alle Kalenderzeilen und filtert in Java — eine repository-seitige Einschränkung ginge bei Serien nicht zuverlässig, weil deren `startDate` beliebig weit in der Vergangenheit liegen kann und trotzdem Vorkommen im Fenster erzeugt. `getUpcoming` expandiert dafür ein Jahresfenster und kürzt erst danach auf `limit`. Bei Haushaltsgrößen unkritisch; wird die Tabelle je groß, ist das die erste Stelle zum Nachziehen (es gibt keinen Aufräumjob für alte Termine). Für `lib-recur` statt des bereits vorhandenen `biweekly`: Letzteres verankert Ganztagestermine beim Parsen fest in der JVM-Zeitzone und ist auf `VEvent`-Objekte zugeschnitten — für reine `LocalDate`-Arithmetik müssten wir synthetische Events bauen und uns diese Fragilität einhandeln
- Ergänze denselben Absatz (gekürzt) im Abschnitt „Bewusst NICHT im Scope" der Spec `docs/superpowers/specs/2026-07-25-calendar-design.md`, damit die Entscheidung dort dokumentiert ist
- Flow-Anbindung: `CalendarReminderScheduler` (minütlich) feuert `event.calendar_reminder` — Uhrzeit-Termine zum Start, ganztägige um 08:00 (Konstante); `action` = Kategorie kleingeschrieben, Attribute `title`/`date`/`time`/`allDay`/`eventId`. Nach Neustart werden verpasste Erinnerungen bewusst nicht nachgefeuert
- Zeiten sind lokale Haushaltszeit (Europe/Berlin), kein TZID-Handling
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Haushaltskalender in CLAUDE.md dokumentiert"
```
