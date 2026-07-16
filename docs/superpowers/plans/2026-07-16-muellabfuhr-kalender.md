# Müllabfuhr-Kalender Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Müllabfuhr-Termine aus einer Google-Kalender-ICS-URL einlesen, auf dem Dashboard anzeigen und am Vorabend per Alexa ansagen.

**Architecture:** Ein täglicher `@Scheduled`-Abruf lädt die ICS-URL, parst sie und spiegelt die Termine in die Tabelle `waste_collection_events` (Resync: alle Zeilen ab heute löschen, neu einfügen). Ein minütlicher Prüf-Scheduler löst im Zeitfenster nach der konfigurierten Uhrzeit eine Alexa-Durchsage aus, dedupliziert über einen Datums-Merker in `application_settings`. Das Frontend bekommt eine Dashboard-Kachel und eine Einstellungsseite.

**Tech Stack:** Spring Boot 3.4.1, Java 21, Lombok, Liquibase, MariaDB, biweekly 0.6.8 (iCalendar), JUnit 5 + Mockito + AssertJ, Angular 19 (standalone), Karma/Jasmine.

**Design-Dokument:** `docs/superpowers/specs/2026-07-16-muellabfuhr-kalender-design.md`

---

## Vorbemerkungen für die Umsetzung

**Build:** `JAVA_HOME` muss auf das JDK 21 zeigen, sonst schlägt der Backend-Build fehl
(Default ist JDK 17). Alle `mvn`-Kommandos aus `backend/` heraus ausführen.

**Konventionen, an die sich dieser Plan hält:**
- Entities in `model/entity`, Repositories in `repository` (zwingend — `JpaConfig` scannt nur
  dieses Paket), DTOs in `dto`, Services in `service`, Controller in `controller`.
- `server.servlet.context-path=/api`, Controller mappen daher auf `/v1/...`.
- Tests: JUnit 5, `@ExtendWith(MockitoExtension.class)`, AssertJ, Konstruktor-Injektion,
  kein `@SpringBootTest` (die lokalen DB-Tests laufen ohne Datenbank nicht).
- Java-Kommentare und Log-Meldungen auf Deutsch, wie im Bestand.

**Paralleles Arbeiten:** In diesem Repo wird parallel entwickelt. Vor Task 1 prüfen, ob
`20260716-0034` noch frei ist:
`ls backend/src/main/resources/db/changelog/changes/ | tail -3`.
Falls belegt, für dieses Changeset auf die nächste freie Nummer hochzählen und die Nummer
in Task 1 konsistent anpassen.

---

### Task 1: Datenbank-Schema

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260716-0034-create-waste-collection-events-table.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`

- [ ] **Step 1: Changeset anlegen**

Create `backend/src/main/resources/db/changelog/changes/20260716-0034-create-waste-collection-events-table.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260716-0034" author="household-manager">
        <comment>Create waste_collection_events table and seed WASTE_COLLECTION settings.</comment>

        <createTable tableName="waste_collection_events">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="collection_date" type="DATE">
                <constraints nullable="false"/>
            </column>
            <column name="label" type="VARCHAR(255)">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <addUniqueConstraint
                tableName="waste_collection_events"
                columnNames="collection_date, label"
                constraintName="uq_waste_collection_date_label"/>

        <createIndex indexName="idx_waste_collection_events_date" tableName="waste_collection_events">
            <column name="collection_date"/>
        </createIndex>

        <insert tableName="application_settings">
            <column name="category" value="WASTE_COLLECTION"/>
            <column name="setting_key" value="enabled"/>
            <column name="setting_value" value="false"/>
        </insert>
        <insert tableName="application_settings">
            <column name="category" value="WASTE_COLLECTION"/>
            <column name="setting_key" value="ics_url"/>
            <column name="setting_value" value=""/>
        </insert>
        <insert tableName="application_settings">
            <column name="category" value="WASTE_COLLECTION"/>
            <column name="setting_key" value="lookahead_days"/>
            <column name="setting_value" value="3"/>
        </insert>
        <insert tableName="application_settings">
            <column name="category" value="WASTE_COLLECTION"/>
            <column name="setting_key" value="reminder_enabled"/>
            <column name="setting_value" value="true"/>
        </insert>
        <insert tableName="application_settings">
            <column name="category" value="WASTE_COLLECTION"/>
            <column name="setting_key" value="reminder_time"/>
            <column name="setting_value" value="19:00"/>
        </insert>
        <insert tableName="application_settings">
            <column name="category" value="WASTE_COLLECTION"/>
            <column name="setting_key" value="reminder_alexa_serials"/>
            <column name="setting_value" value=""/>
        </insert>
        <insert tableName="application_settings">
            <column name="category" value="WASTE_COLLECTION"/>
            <column name="setting_key" value="last_announced_date"/>
            <column name="setting_value" value=""/>
        </insert>

        <rollback>
            <delete tableName="application_settings">
                <where>category = 'WASTE_COLLECTION'</where>
            </delete>
            <dropTable tableName="waste_collection_events"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Changeset im Master einbinden**

In `backend/src/main/resources/db/changelog/db.changelog-master.xml` vor `</databaseChangeLog>` einfügen:

```xml
    <!-- Muellabfuhr-Termine aus dem Google-Kalender -->
    <include file="db/changelog/changes/20260716-0034-create-waste-collection-events-table.xml"/>
```

- [ ] **Step 3: XML-Wohlgeformtheit prüfen**

Run: `cd backend && mvn -q validate`
Expected: BUILD SUCCESS (kein Parse-Fehler in den Changelogs).

Hinweis: Liquibase läuft erst beim Anwendungsstart gegen die DB; `validate` prüft hier nur
den Build. Der Changeset wird beim ersten Start der Anwendung angewandt.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat(waste): Schema fuer Muellabfuhr-Termine und Settings"
```

---

### Task 2: Entity und Repository

**Files:**
- Create: `backend/src/main/java/com/household/manager/model/entity/WasteCollectionEvent.java`
- Create: `backend/src/main/java/com/household/manager/repository/WasteCollectionEventRepository.java`

- [ ] **Step 1: Entity anlegen**

Create `backend/src/main/java/com/household/manager/model/entity/WasteCollectionEvent.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Ein aus dem Kalender gespiegelter Abholtermin, z. B. "Biotonne" am 17.07.2026. */
@Entity
@Table(name = "waste_collection_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_waste_collection_date_label",
                columnNames = {"collection_date", "label"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WasteCollectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "collection_date", nullable = false)
    private LocalDate collectionDate;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

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

- [ ] **Step 2: Repository anlegen**

Create `backend/src/main/java/com/household/manager/repository/WasteCollectionEventRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.WasteCollectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** Repository für {@link WasteCollectionEvent}. */
@Repository
public interface WasteCollectionEventRepository extends JpaRepository<WasteCollectionEvent, Long> {

    List<WasteCollectionEvent> findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
            LocalDate from, LocalDate to);

    List<WasteCollectionEvent> findByCollectionDateOrderByLabelAsc(LocalDate date);

    /** Zählt Termine ab einschließlich {@code from} — für die Status-Anzeige. */
    long countByCollectionDateGreaterThanEqual(LocalDate from);

    /** Räumt das Zukunftsfenster für den Resync. */
    void deleteByCollectionDateGreaterThanEqual(LocalDate from);
}
```

- [ ] **Step 3: Kompilieren**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/model/entity/WasteCollectionEvent.java backend/src/main/java/com/household/manager/repository/WasteCollectionEventRepository.java
git commit -m "feat(waste): Entity und Repository fuer Abholtermine"
```

---

### Task 3: ICS-Parser

Der Parser ist das Herz des Features und rein funktional — deshalb zuerst und per TDD.

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/household/manager/service/ParsedWasteEvent.java`
- Create: `backend/src/main/java/com/household/manager/service/WasteCalendarIcsParser.java`
- Test: `backend/src/test/java/com/household/manager/service/WasteCalendarIcsParserTest.java`

- [ ] **Step 1: Abhängigkeit ergänzen**

In `backend/pom.xml` vor `</dependencies>` einfügen:

```xml
        <!-- iCalendar-Parsing fuer den Muellabfuhr-Kalender -->
        <dependency>
            <groupId>net.sf.biweekly</groupId>
            <artifactId>biweekly</artifactId>
            <version>0.6.8</version>
        </dependency>
```

Run: `cd backend && mvn -q dependency:resolve`
Expected: BUILD SUCCESS, biweekly wird geladen.

- [ ] **Step 2: Test-Fixtures anlegen**

Create `backend/src/test/resources/waste/single-event.ics`:

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:single-1@test
DTSTART;VALUE=DATE:20260720
DTEND;VALUE=DATE:20260721
SUMMARY:Biotonne
END:VEVENT
END:VCALENDAR
```

Create `backend/src/test/resources/waste/recurring-event.ics` (14-tägig ab 20.07.2026):

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:recur-1@test
DTSTART;VALUE=DATE:20260720
DTEND;VALUE=DATE:20260721
RRULE:FREQ=WEEKLY;INTERVAL=2
SUMMARY:Restmuell
END:VEVENT
END:VCALENDAR
```

Create `backend/src/test/resources/waste/same-day-events.ics`:

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:same-1@test
DTSTART;VALUE=DATE:20260720
SUMMARY:Biotonne
END:VEVENT
BEGIN:VEVENT
UID:same-2@test
DTSTART;VALUE=DATE:20260720
SUMMARY:Gelber Sack
END:VEVENT
END:VCALENDAR
```

Create `backend/src/test/resources/waste/past-event.ics`:

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Test//Test//EN
BEGIN:VEVENT
UID:past-1@test
DTSTART;VALUE=DATE:20260101
SUMMARY:Papiertonne
END:VEVENT
END:VCALENDAR
```

- [ ] **Step 3: Failing test schreiben**

Create `backend/src/test/java/com/household/manager/service/WasteCalendarIcsParserTest.java`:

```java
package com.household.manager.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WasteCalendarIcsParserTest {

    private final WasteCalendarIcsParser parser = new WasteCalendarIcsParser();

    private static final LocalDate FROM = LocalDate.of(2026, 7, 16);
    private static final LocalDate TO = LocalDate.of(2027, 7, 16);

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/waste/" + name)) {
            assertThat(in).as("Fixture /waste/%s muss existieren", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void liestEinzelterminMitDatumUndBezeichnung() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(fixture("single-event.ics"), FROM, TO);

        assertThat(events).containsExactly(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 20), "Biotonne"));
    }

    @Test
    void loestSerienterminUeberDasFensterAuf() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(
                fixture("recurring-event.ics"), FROM, LocalDate.of(2026, 8, 31));

        assertThat(events).extracting(ParsedWasteEvent::date).containsExactly(
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31));
        assertThat(events).extracting(ParsedWasteEvent::label).containsOnly("Restmuell");
    }

    @Test
    void liefertMehrereTermineAmSelbenTag() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(fixture("same-day-events.ics"), FROM, TO);

        assertThat(events).containsExactlyInAnyOrder(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 20), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 20), "Gelber Sack"));
    }

    @Test
    void filtertTermineVorDemFenster() throws IOException {
        List<ParsedWasteEvent> events = parser.parse(fixture("past-event.ics"), FROM, TO);

        assertThat(events).isEmpty();
    }

    @Test
    void wirftBeiInhaltDerKeinIcsIst() {
        assertThatThrownBy(() -> parser.parse("<html>Fehlerseite</html>", FROM, TO))
                .isInstanceOf(WasteCalendarException.class)
                .hasMessageContaining("Kalender");
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn test -Dtest=WasteCalendarIcsParserTest`
Expected: FAIL — Kompilierfehler, `WasteCalendarIcsParser`/`ParsedWasteEvent`/`WasteCalendarException` existieren nicht.

- [ ] **Step 5: Exception-Typ anlegen**

Create `backend/src/main/java/com/household/manager/service/WasteCalendarException.java`:

```java
package com.household.manager.service;

/** Fehler beim Abrufen oder Parsen des Müllabfuhr-Kalenders. */
public class WasteCalendarException extends RuntimeException {

    public WasteCalendarException(String message) {
        super(message);
    }

    public WasteCalendarException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 6: Record anlegen**

Create `backend/src/main/java/com/household/manager/service/ParsedWasteEvent.java`:

```java
package com.household.manager.service;

import java.time.LocalDate;

/** Ein aus dem ICS gelesener Abholtermin, noch ohne Datenbankbezug. */
public record ParsedWasteEvent(LocalDate date, String label) {
}
```

- [ ] **Step 7: Parser implementieren**

Create `backend/src/main/java/com/household/manager/service/WasteCalendarIcsParser.java`:

```java
package com.household.manager.service;

import biweekly.Biweekly;
import biweekly.ICalendar;
import biweekly.component.VEvent;
import biweekly.io.TimezoneAssignment;
import biweekly.io.TimezoneInfo;
import biweekly.property.DateStart;
import biweekly.util.com.google.ical.compat.javautil.DateIterator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Parst ICS-Text zu Abholterminen. Rein funktional: kein Netz, keine Datenbank.
 *
 * <p>Serientermine werden über {@link VEvent#getDateIterator(TimeZone)} aufgelöst; für
 * Einzeltermine liefert derselbe Iterator genau ein Datum, sodass beide Fälle einen Codepfad
 * teilen.
 */
@Component
@Slf4j
public class WasteCalendarIcsParser {

    /**
     * Obergrenze für Iterationen je Termin. Eine Serie ohne UNTIL/COUNT ist unendlich; das
     * Fensterende bricht regulär ab, diese Grenze ist der Notausstieg gegen fehlerhafte Regeln.
     */
    private static final int MAX_OCCURRENCES_PER_EVENT = 1000;

    /**
     * @param icsContent Roher ICS-Text
     * @param from       erster Tag des Fensters (einschließlich)
     * @param to         letzter Tag des Fensters (einschließlich)
     * @return Termine im Fenster, Duplikate möglich (mehrere Tonnen an einem Tag)
     * @throws WasteCalendarException wenn der Text kein verwertbarer Kalender ist
     */
    public List<ParsedWasteEvent> parse(String icsContent, LocalDate from, LocalDate to) {
        ICalendar ical = parseCalendar(icsContent);

        List<ParsedWasteEvent> result = new ArrayList<>();
        for (VEvent event : ical.getEvents()) {
            collectOccurrences(ical, event, from, to, result);
        }
        log.debug("ICS geparst: {} Termine im Fenster {} bis {}", result.size(), from, to);
        return result;
    }

    private ICalendar parseCalendar(String icsContent) {
        ICalendar ical;
        try {
            ical = Biweekly.parse(icsContent).first();
        } catch (Exception ex) {
            throw new WasteCalendarException("Kalender konnte nicht gelesen werden.", ex);
        }
        if (ical == null) {
            throw new WasteCalendarException(
                    "Kalender konnte nicht gelesen werden: kein VCALENDAR im Inhalt gefunden.");
        }
        return ical;
    }

    private void collectOccurrences(ICalendar ical, VEvent event,
                                    LocalDate from, LocalDate to,
                                    List<ParsedWasteEvent> result) {
        String label = readLabel(event);
        if (label == null) {
            log.warn("Termin ohne SUMMARY wird uebersprungen");
            return;
        }
        DateStart dtstart = event.getDateStart();
        if (dtstart == null || dtstart.getValue() == null) {
            log.warn("Termin '{}' ohne DTSTART wird uebersprungen", label);
            return;
        }

        TimeZone timezone = resolveTimezone(ical, dtstart);
        ZoneId zoneId = timezone.toZoneId();

        DateIterator it = event.getDateIterator(timezone);
        it.advanceTo(Date.from(from.atStartOfDay(zoneId).toInstant()));

        int guard = 0;
        while (it.hasNext() && guard++ < MAX_OCCURRENCES_PER_EVENT) {
            LocalDate occurrence = it.next().toInstant().atZone(zoneId).toLocalDate();
            if (occurrence.isAfter(to)) {
                return;
            }
            if (!occurrence.isBefore(from)) {
                result.add(new ParsedWasteEvent(occurrence, label));
            }
        }
        if (guard >= MAX_OCCURRENCES_PER_EVENT) {
            log.warn("Termin '{}' hat die Iterationsgrenze erreicht; Serie wird abgeschnitten", label);
        }
    }

    private String readLabel(VEvent event) {
        if (event.getSummary() == null) {
            return null;
        }
        String value = event.getSummary().getValue();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Ganztagestermine sind "floating" und werden in der lokalen Zone interpretiert. */
    private TimeZone resolveTimezone(ICalendar ical, DateStart dtstart) {
        TimezoneInfo tzinfo = ical.getTimezoneInfo();
        if (tzinfo.isFloating(dtstart)) {
            return TimeZone.getDefault();
        }
        TimezoneAssignment assignment = tzinfo.getTimezone(dtstart);
        return assignment == null ? TimeZone.getTimeZone("UTC") : assignment.getTimeZone();
    }
}
```

- [ ] **Step 8: Test laufen lassen — muss grün sein**

Run: `cd backend && mvn test -Dtest=WasteCalendarIcsParserTest`
Expected: PASS, 5 Tests.

Falls die biweekly-API abweicht (z. B. anderer Import-Pfad für `DateIterator`): Die Tests
sind hier der Schiedsrichter — Signatur gegen die Javadocs von biweekly 0.6.8 prüfen und den
Parser anpassen, nicht die Tests aufweichen.

- [ ] **Step 9: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/household/manager/service/ParsedWasteEvent.java backend/src/main/java/com/household/manager/service/WasteCalendarIcsParser.java backend/src/main/java/com/household/manager/service/WasteCalendarException.java backend/src/test/java/com/household/manager/service/WasteCalendarIcsParserTest.java backend/src/test/resources/waste/
git commit -m "feat(waste): ICS-Parser mit Aufloesung von Serienterminen"
```

---

### Task 4: ICS-Client

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/WasteCalendarIcsClient.java`

- [ ] **Step 1: Client implementieren**

Reiner HTTP-Abruf, kein Parsing. Der JDK-`HttpClient` genügt und vermeidet eine weitere
Abhängigkeit.

Create `backend/src/main/java/com/household/manager/service/WasteCalendarIcsClient.java`:

```java
package com.household.manager.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Laedt den ICS-Text von der konfigurierten Kalender-URL. Sonst nichts. */
@Component
@Slf4j
public class WasteCalendarIcsClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * @param icsUrl vollstaendige http(s)-URL des Kalenders
     * @return roher ICS-Text
     * @throws WasteCalendarException bei ungueltiger URL, Netzfehler, Timeout oder Status != 2xx
     */
    public String fetch(String icsUrl) {
        HttpResponse<String> response;
        try {
            // Der Request-Bau steht bewusst im try: URI.create wirft bei einer vertippten
            // URL eine IllegalArgumentException, die sonst am Vertrag dieser Klasse
            // vorbeilaufen wuerde ("jeder Fehler wird zur WasteCalendarException").
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(icsUrl))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new WasteCalendarException("Ungueltige Kalender-URL: " + icsUrl, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new WasteCalendarException("Kalender-Abruf wurde unterbrochen.", ex);
        } catch (Exception ex) {
            throw new WasteCalendarException("Kalender ist nicht erreichbar: " + ex.getMessage(), ex);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WasteCalendarException(
                    "Kalender antwortete mit HTTP " + response.statusCode() + ".");
        }
        log.debug("ICS geladen: {} Zeichen", response.body().length());
        return response.body();
    }
}
```

- [ ] **Step 2: Kompilieren**

Run: `cd backend && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/WasteCalendarIcsClient.java
git commit -m "feat(waste): HTTP-Client fuer die ICS-URL"
```

---

### Task 5: Settings-Zugriff

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/ApplicationSettingsService.java`
- Create: `backend/src/main/java/com/household/manager/dto/WasteCollectionSettings.java`
- Create: `backend/src/main/java/com/household/manager/service/WasteCollectionSettingsService.java`
- Test: `backend/src/test/java/com/household/manager/service/WasteCollectionSettingsServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/service/ApplicationSettingsServiceTest.java` — deckt **nur** `getString` ab

Der Test für `getString` ist nicht optional: Auf seinem Verhalten (fehlender Schlüssel →
Default des Aufrufers) ruht die gesamte Seed-Entscheidung aus Task 1, nach der `ics_url`,
`reminder_alexa_serials` und `last_announced_date` bewusst **nicht** in der Datenbank stehen.
Diese Logik nur durch einen Mock ihrer selbst zu prüfen, beweist nichts.

- [ ] **Step 1: `getString` im Bestand ergänzen**

In `backend/src/main/java/com/household/manager/service/ApplicationSettingsService.java` nach
der Methode `getBoolean` einfügen:

```java
    /**
     * Returns a string setting with a default fallback. Blank values count as absent.
     */
    @Transactional(readOnly = true)
    public String getString(String category, String key, String defaultValue) {
        return repository.findByCategoryAndSettingKey(category, key)
                .map(ApplicationSetting::getSettingValue)
                .filter(value -> !value.isBlank())
                .orElse(defaultValue);
    }
```

- [ ] **Step 2: Settings-DTO anlegen**

Create `backend/src/main/java/com/household/manager/dto/WasteCollectionSettings.java`:

```java
package com.household.manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Konfiguration des Muellabfuhr-Kalenders, wie sie die Einstellungsseite sieht. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WasteCollectionSettings {

    private boolean enabled;

    /** Geheime iCal-URL des Kalenders; leer erlaubt (Feature dann inaktiv). */
    private String icsUrl;

    /** Vorschau-Fenster in Tagen, einschliesslich heute; mindestens 1. */
    private int lookaheadDays;

    private boolean reminderEnabled;

    /** Uhrzeit der Durchsage im Format HH:mm. */
    private String reminderTime;

    /** Seriennummern der Ziel-Alexa-Geraete. */
    private List<String> reminderAlexaSerials;
}
```

- [ ] **Step 3: Failing test schreiben**

Create `backend/src/test/java/com/household/manager/service/WasteCollectionSettingsServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.WasteCollectionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCollectionSettingsServiceTest {

    private static final String CATEGORY = "WASTE_COLLECTION";

    @Mock
    private ApplicationSettingsService settingsService;

    private WasteCollectionSettingsService service;

    @BeforeEach
    void setUp() {
        service = new WasteCollectionSettingsService(settingsService);
    }

    @Test
    void liestSettingsAlsDto() {
        when(settingsService.getBoolean(CATEGORY, "enabled", false)).thenReturn(true);
        when(settingsService.getString(CATEGORY, "ics_url", "")).thenReturn("https://x/cal.ics");
        when(settingsService.getInt(CATEGORY, "lookahead_days", 3)).thenReturn(5);
        when(settingsService.getBoolean(CATEGORY, "reminder_enabled", true)).thenReturn(true);
        when(settingsService.getString(CATEGORY, "reminder_time", "19:00")).thenReturn("18:30");
        when(settingsService.getString(CATEGORY, "reminder_alexa_serials", ""))
                .thenReturn("DSN1,DSN2");

        WasteCollectionSettings settings = service.getSettings();

        assertThat(settings.isEnabled()).isTrue();
        assertThat(settings.getIcsUrl()).isEqualTo("https://x/cal.ics");
        assertThat(settings.getLookaheadDays()).isEqualTo(5);
        assertThat(settings.getReminderTime()).isEqualTo("18:30");
        assertThat(settings.getReminderAlexaSerials()).containsExactly("DSN1", "DSN2");
    }

    @Test
    void leereSerienlisteErgibtLeereListeStattEinesLeerenEintrags() {
        when(settingsService.getBoolean(CATEGORY, "enabled", false)).thenReturn(false);
        when(settingsService.getString(CATEGORY, "ics_url", "")).thenReturn("");
        when(settingsService.getInt(CATEGORY, "lookahead_days", 3)).thenReturn(3);
        when(settingsService.getBoolean(CATEGORY, "reminder_enabled", true)).thenReturn(true);
        when(settingsService.getString(CATEGORY, "reminder_time", "19:00")).thenReturn("19:00");
        when(settingsService.getString(CATEGORY, "reminder_alexa_serials", "")).thenReturn("");

        assertThat(service.getSettings().getReminderAlexaSerials()).isEmpty();
    }

    @Test
    void schreibtSettingsOhneDenInternenMerkerAnzutasten() {
        service.saveSettings(WasteCollectionSettings.builder()
                .enabled(true)
                .icsUrl("https://x/cal.ics")
                .lookaheadDays(4)
                .reminderEnabled(false)
                .reminderTime("20:15")
                .reminderAlexaSerials(List.of("DSN1", "DSN2"))
                .build());

        verify(settingsService).saveSetting(CATEGORY, "enabled", "true");
        verify(settingsService).saveSetting(CATEGORY, "ics_url", "https://x/cal.ics");
        verify(settingsService).saveSetting(CATEGORY, "lookahead_days", "4");
        verify(settingsService).saveSetting(CATEGORY, "reminder_enabled", "false");
        verify(settingsService).saveSetting(CATEGORY, "reminder_time", "20:15");
        verify(settingsService).saveSetting(CATEGORY, "reminder_alexa_serials", "DSN1,DSN2");
        verify(settingsService, org.mockito.Mockito.never())
                .saveSetting(eq(CATEGORY), eq("last_announced_date"), anyString());
    }

    @Test
    void faelltBeiUnparsbarerUhrzeitAufDenDefaultZurueck() {
        when(settingsService.getString(CATEGORY, "reminder_time", "19:00")).thenReturn("abends");

        assertThat(service.getReminderTime()).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    void hebtZuKleinesVorschaufensterAufEinsAn() {
        when(settingsService.getInt(CATEGORY, "lookahead_days", 3)).thenReturn(0);

        assertThat(service.getLookaheadDays()).isEqualTo(1);
    }

    @Test
    void merktSichDasAnsagedatum() {
        service.markAnnounced(LocalDate.of(2026, 7, 16));

        verify(settingsService).saveSetting(CATEGORY, "last_announced_date", "2026-07-16");
    }

    @Test
    void erkenntObHeuteBereitsAngesagtWurde() {
        when(settingsService.getString(CATEGORY, "last_announced_date", ""))
                .thenReturn("2026-07-16");

        assertThat(service.wasAnnouncedOn(LocalDate.of(2026, 7, 16))).isTrue();
        assertThat(service.wasAnnouncedOn(LocalDate.of(2026, 7, 17))).isFalse();
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn test -Dtest=WasteCollectionSettingsServiceTest`
Expected: FAIL — `WasteCollectionSettingsService` existiert nicht.

- [ ] **Step 5: Service implementieren**

Create `backend/src/main/java/com/household/manager/service/WasteCollectionSettingsService.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.WasteCollectionSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uebersetzt zwischen dem typisierten {@link WasteCollectionSettings} und den String-Werten
 * in {@code application_settings}. Kapselt zugleich die defensive Auslegung fehlerhafter
 * Werte, damit ein Tippfehler in der DB die Scheduler nicht lahmlegt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WasteCollectionSettingsService {

    static final String CATEGORY = "WASTE_COLLECTION";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ICS_URL = "ics_url";
    private static final String KEY_LOOKAHEAD_DAYS = "lookahead_days";
    private static final String KEY_REMINDER_ENABLED = "reminder_enabled";
    private static final String KEY_REMINDER_TIME = "reminder_time";
    private static final String KEY_REMINDER_SERIALS = "reminder_alexa_serials";
    private static final String KEY_LAST_ANNOUNCED = "last_announced_date";

    private static final LocalTime DEFAULT_REMINDER_TIME = LocalTime.of(19, 0);
    private static final String DEFAULT_REMINDER_TIME_TEXT = "19:00";
    private static final int DEFAULT_LOOKAHEAD_DAYS = 3;

    private final ApplicationSettingsService settingsService;

    public WasteCollectionSettings getSettings() {
        return WasteCollectionSettings.builder()
                .enabled(isEnabled())
                .icsUrl(getIcsUrl())
                .lookaheadDays(getLookaheadDays())
                .reminderEnabled(isReminderEnabled())
                .reminderTime(settingsService.getString(
                        CATEGORY, KEY_REMINDER_TIME, DEFAULT_REMINDER_TIME_TEXT))
                .reminderAlexaSerials(getReminderAlexaSerials())
                .build();
    }

    /**
     * Schreibt die vom Nutzer pflegbaren Werte; {@code last_announced_date} bleibt unberuehrt.
     *
     * <p>Bewusst ein einziger Aufruf der Sammel-Methode statt sechs Einzelaufrufe: Nur so
     * laufen alle Schluessel in einer Transaktion. Sechs Einzelaufrufe waeren sechs
     * Transaktionen, und ein Fehler beim vierten liesse die Konfiguration halb geschrieben
     * zurueck. `AnkerSolixAutoControlService` haelt es genauso.
     */
    public void saveSettings(WasteCollectionSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KEY_ENABLED, String.valueOf(settings.isEnabled()));
        values.put(KEY_ICS_URL, nullToEmpty(settings.getIcsUrl()));
        values.put(KEY_LOOKAHEAD_DAYS, String.valueOf(settings.getLookaheadDays()));
        values.put(KEY_REMINDER_ENABLED, String.valueOf(settings.isReminderEnabled()));
        values.put(KEY_REMINDER_TIME, nullToEmpty(settings.getReminderTime()));
        values.put(KEY_REMINDER_SERIALS, settings.getReminderAlexaSerials() == null
                ? "" : String.join(",", settings.getReminderAlexaSerials()));

        settingsService.saveSettings(CATEGORY, values);
        log.info("Muellabfuhr-Einstellungen gespeichert");
    }

    public boolean isEnabled() {
        return settingsService.getBoolean(CATEGORY, KEY_ENABLED, false);
    }

    public boolean isReminderEnabled() {
        return settingsService.getBoolean(CATEGORY, KEY_REMINDER_ENABLED, true);
    }

    public String getIcsUrl() {
        return settingsService.getString(CATEGORY, KEY_ICS_URL, "");
    }

    /**
     * Nie kleiner als 1 — ein Fenster von 0 Tagen wuerde die Kachel dauerhaft leeren.
     *
     * <p>Der try/catch ist kein Zierrat: {@code getInt} ruft ungeschuetzt
     * {@code Integer.parseInt}, ein nicht-numerischer Wert in der DB flaege also als
     * {@link NumberFormatException} bis in den minuetlichen Scheduler durch. Bewusst hier
     * abgefangen und nicht in {@code ApplicationSettingsService.getInt} — dessen Verhalten
     * fuer alle Bestandsaufrufer zu aendern waere eine eigene Entscheidung.
     */
    public int getLookaheadDays() {
        try {
            return Math.max(1, settingsService.getInt(
                    CATEGORY, KEY_LOOKAHEAD_DAYS, DEFAULT_LOOKAHEAD_DAYS));
        } catch (NumberFormatException ex) {
            log.warn("Ungueltiges Vorschaufenster, nutze {} Tage", DEFAULT_LOOKAHEAD_DAYS);
            return DEFAULT_LOOKAHEAD_DAYS;
        }
    }

    /** Faellt bei unparsbarem Wert auf 19:00 zurueck, statt den Scheduler scheitern zu lassen. */
    public LocalTime getReminderTime() {
        String raw = settingsService.getString(CATEGORY, KEY_REMINDER_TIME, DEFAULT_REMINDER_TIME_TEXT);
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException ex) {
            log.warn("Ungueltige Ansagezeit '{}', nutze {}", raw, DEFAULT_REMINDER_TIME);
            return DEFAULT_REMINDER_TIME;
        }
    }

    public List<String> getReminderAlexaSerials() {
        String raw = settingsService.getString(CATEGORY, KEY_REMINDER_SERIALS, "");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void markAnnounced(LocalDate date) {
        settingsService.saveSetting(CATEGORY, KEY_LAST_ANNOUNCED, date.toString());
    }

    public boolean wasAnnouncedOn(LocalDate date) {
        return date.toString().equals(settingsService.getString(CATEGORY, KEY_LAST_ANNOUNCED, ""));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 6: Test laufen lassen — muss grün sein**

Run: `cd backend && mvn test -Dtest=WasteCollectionSettingsServiceTest`
Expected: PASS, 7 Tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/ApplicationSettingsService.java backend/src/main/java/com/household/manager/dto/WasteCollectionSettings.java backend/src/main/java/com/household/manager/service/WasteCollectionSettingsService.java backend/src/test/java/com/household/manager/service/WasteCollectionSettingsServiceTest.java
git commit -m "feat(waste): typisierter Settings-Zugriff mit defensiven Defaults"
```

---

### Task 6: Leseseite (WasteCollectionService)

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/WasteCollectionEventResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/WasteCollectionService.java`
- Test: `backend/src/test/java/com/household/manager/service/WasteCollectionServiceTest.java`

- [ ] **Step 1: Response-DTO anlegen**

Create `backend/src/main/java/com/household/manager/dto/WasteCollectionEventResponse.java`:

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Ein Abholtermin, wie ihn Kachel und Einstellungsseite anzeigen. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WasteCollectionEventResponse {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    private String label;

    /** 0 = heute, 1 = morgen. Serverseitig berechnet, damit die Kachel nicht rechnen muss. */
    private long daysUntil;
}
```

- [ ] **Step 2: Failing test schreiben**

Create `backend/src/test/java/com/household/manager/service/WasteCollectionServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCollectionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Fixer "Jetzt"-Zeitpunkt: 16.07.2026, 10:00 Uhr. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZONE);

    @Mock
    private WasteCollectionEventRepository repository;

    private WasteCollectionService service;

    @BeforeEach
    void setUp() {
        service = new WasteCollectionService(repository, CLOCK);
    }

    private WasteCollectionEvent event(LocalDate date, String label) {
        return WasteCollectionEvent.builder().collectionDate(date).label(label).build();
    }

    @Test
    void fensterVonDreiTagenReichtBisUebermorgen() {
        when(repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 18)))
                .thenReturn(List.of(event(LocalDate.of(2026, 7, 17), "Biotonne")));

        List<WasteCollectionEventResponse> result = service.getUpcoming(3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLabel()).isEqualTo("Biotonne");
        assertThat(result.get(0).getDaysUntil()).isEqualTo(1);
    }

    @Test
    void berechnetDaysUntilRelativZurUhr() {
        when(repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 16)))
                .thenReturn(List.of(event(LocalDate.of(2026, 7, 16), "Restmuell")));

        assertThat(service.getUpcoming(1).get(0).getDaysUntil()).isZero();
    }

    @Test
    void liefertTermineFuerMorgen() {
        when(repository.findByCollectionDateOrderByLabelAsc(LocalDate.of(2026, 7, 17)))
                .thenReturn(List.of(
                        event(LocalDate.of(2026, 7, 17), "Biotonne"),
                        event(LocalDate.of(2026, 7, 17), "Restmuell")));

        assertThat(service.getLabelsForTomorrow()).containsExactly("Biotonne", "Restmuell");
    }

    @Test
    void heuteIstDerErsteTagDesFensters() {
        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 7, 16));
    }
}
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn test -Dtest=WasteCollectionServiceTest`
Expected: FAIL — `WasteCollectionService` existiert nicht.

- [ ] **Step 4: Service implementieren**

Create `backend/src/main/java/com/household/manager/service/WasteCollectionService.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Leseseite der Abholtermine. Die {@link Clock} wird injiziert, damit "heute" und "morgen"
 * in Tests deterministisch sind.
 */
@Service
@Slf4j
public class WasteCollectionService {

    private final WasteCollectionEventRepository repository;
    private final Clock clock;

    public WasteCollectionService(WasteCollectionEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * @param lookaheadDays Fenstergroesse in Tagen, einschliesslich heute (mindestens 1)
     */
    @Transactional(readOnly = true)
    public List<WasteCollectionEventResponse> getUpcoming(int lookaheadDays) {
        LocalDate from = today();
        LocalDate to = from.plusDays(Math.max(1, lookaheadDays) - 1L);
        return repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(from, to)
                .stream()
                .map(event -> toResponse(event, from))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> getLabelsForTomorrow() {
        return repository.findByCollectionDateOrderByLabelAsc(today().plusDays(1))
                .stream()
                .map(WasteCollectionEvent::getLabel)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUpcoming() {
        return repository.countByCollectionDateGreaterThanEqual(today());
    }

    private WasteCollectionEventResponse toResponse(WasteCollectionEvent event, LocalDate from) {
        return WasteCollectionEventResponse.builder()
                .date(event.getCollectionDate())
                .label(event.getLabel())
                .daysUntil(ChronoUnit.DAYS.between(from, event.getCollectionDate()))
                .build();
    }
}
```

- [ ] **Step 5: `Clock`-Bean bereitstellen**

`Clock` ist kein Standard-Bean in Spring Boot und muss registriert werden. Prüfen, ob bereits
eines existiert:

Run: `cd backend && grep -rn "Clock" src/main/java/com/household/manager/config/`

Falls keine Treffer, create `backend/src/main/java/com/household/manager/config/ClockConfig.java`:

```java
package com.household.manager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Stellt eine {@link Clock} als Bean bereit, damit zeitabhaengige Services testbar sind,
 * ohne auf statische Aufrufe wie {@code LocalDate.now()} angewiesen zu sein.
 */
@Configuration
public class ClockConfig {

    /**
     * Zone bewusst festgenagelt statt {@code systemDefaultZone()}: Das Backend-Image
     * (eclipse-temurin:21-jre) setzt kein TZ, und docker-compose gibt es nur zigbee2mqtt
     * mit, nicht dem Backend — im Container liefe die Uhr also auf UTC. Fuer einen Haushalt
     * in Deutschland heisst das: "heute" kippt zwei Stunden zu frueh, und die Abend-Durchsage
     * um 19:00 kaeme erst um 21:00 Ortszeit.
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Europe/Berlin"));
    }
}
```

Falls bereits ein `Clock`-Bean existiert, diesen Schritt überspringen.

**Warum nicht stattdessen `TZ: Europe/Berlin` in die docker-compose eintragen?** Das wäre der
größere Hebel — und genau deshalb hier falsch. Es änderte die Default-Zone der *gesamten*
Anwendung; jedes bestehende `LocalDateTime.now()` (Wetter-, Tasmota-, Shelly-,
Luftqualitäts-Messwerte, alle `@PrePersist`-Zeitstempel) schriebe ab dann zwei Stunden
versetzt zu allem, was schon in der Datenbank steht, und verböge stillschweigend die
Zeitachse bestehender Diagramme. Das mag mittelfristig richtig sein, ist aber eine bewusste
Migrationsentscheidung des Projekteigners — kein Nebeneffekt dieses Tasks. Das `Clock`-Bean
festzunageln betrifft dagegen nur Code, der `Clock` injiziert: heute exakt dieses Feature.

- [ ] **Step 6: Test laufen lassen — muss grün sein**

Run: `cd backend && mvn test -Dtest=WasteCollectionServiceTest`
Expected: PASS, 4 Tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/WasteCollectionEventResponse.java backend/src/main/java/com/household/manager/service/WasteCollectionService.java backend/src/main/java/com/household/manager/config/ClockConfig.java backend/src/test/java/com/household/manager/service/WasteCollectionServiceTest.java
git commit -m "feat(waste): Leseseite der Abholtermine mit injizierter Clock"
```

---

### Task 7: Polling-Service

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java`
- Create: `backend/src/main/java/com/household/manager/dto/WastePollingStatusResponse.java`
- Create: `backend/src/main/java/com/household/manager/service/WasteCollectionResyncService.java`
- Create: `backend/src/main/java/com/household/manager/service/WasteCalendarPollingService.java`
- Test: `backend/src/test/java/com/household/manager/service/WasteCollectionResyncServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/service/WasteCalendarPollingServiceTest.java`

**Warum der Resync eine eigene Bean ist — bitte nicht „vereinfachen":**

`deleteByCollectionDateGreaterThanEqual` ist eine abgeleitete Delete-Query und braucht zur
Laufzeit zwingend eine aktive Transaktion. `@Transactional` wirkt aber nur über den
Spring-Proxy — bei einem Selbstaufruf innerhalb derselben Bean (`this.resync(...)`) greift
die Annotation **nicht**, und der Delete scheitert zur Laufzeit. Deshalb liegt der Resync in
einer eigenen Bean, die der Polling-Service injiziert aufruft: So geht der Aufruf über den
Proxy und die Transaktion existiert wirklich.

Der zweite Grund ist genauso wichtig: Die Transaktion umschließt so **nur** Delete und
Insert. Läge sie auf `scheduledPoll`, hielte der bis zu 10 Sekunden dauernde HTTP-Abruf eine
Datenbankverbindung offen.

- [ ] **Step 1: EntitySource erweitern**

In `backend/src/main/java/com/household/manager/entitystate/EntitySource.java` den Wert
`WASTE` ergänzen — nach `ANKER_SOLIX`, vor `MANUAL`:

```java
    ANKER_SOLIX,
    /** Muellabfuhr-Termine aus dem Kalender-Abo. */
    WASTE,
    /** Vom Benutzer im UI angelegte Entität (kein externes Quellsystem). */
    MANUAL
```

- [ ] **Step 2: Status-DTO anlegen**

Create `backend/src/main/java/com/household/manager/dto/WastePollingStatusResponse.java`:

```java
package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Status des Muellabfuhr-Kalenderabrufs. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WastePollingStatusResponse {

    private String schedule;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastPollTime;

    private String lastError;

    /** Anzahl bekannter Termine ab heute. */
    private long knownEventCount;
}
```

- [ ] **Step 3: Resync-Bean per TDD — Test zuerst**

Create `backend/src/test/java/com/household/manager/service/WasteCollectionResyncServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WasteCollectionResyncServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);

    @Mock
    private WasteCollectionEventRepository repository;

    private WasteCollectionResyncService service;

    @BeforeEach
    void setUp() {
        service = new WasteCollectionResyncService(repository);
    }

    @Test
    void loeschtDasZukunftsfensterUndSchreibtDieNeuenTermine() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 24), "Restmuell")));

        // Reihenfolge ist wesentlich: erst raeumen, dann schreiben.
        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).deleteByCollectionDateGreaterThanEqual(TODAY);
        inOrder.verify(repository).saveAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bildetDieTermineKorrektAufEntitaetenAb() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne")));

        ArgumentCaptor<List<WasteCollectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());

        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getCollectionDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(captor.getValue().get(0).getLabel()).isEqualTo("Biotonne");
    }

    @Test
    void entferntDuplikateAusDemIcs() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne")));

        // Der Unique-Constraint (collection_date, label) wuerde bei Dubletten brechen.
        ArgumentCaptor<List<WasteCollectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void behaeltMehrereTonnenAmSelbenTag() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Restmuell")));

        ArgumentCaptor<List<WasteCollectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }
}
```

Run: `cd backend && mvn test -Dtest=WasteCollectionResyncServiceTest`
Expected: FAIL — `WasteCollectionResyncService` existiert nicht.

- [ ] **Step 4: Resync-Bean implementieren**

Create `backend/src/main/java/com/household/manager/service/WasteCollectionResyncService.java`:

```java
package com.household.manager.service;

import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Ersetzt das Zukunftsfenster der Abholtermine in einer Transaktion.
 *
 * <p>Bewusst eine eigene Bean und nicht eine Methode des Polling-Service: Der abgeleitete
 * Delete braucht eine aktive Transaktion, und {@code @Transactional} wirkt nur ueber den
 * Spring-Proxy — bei einem Selbstaufruf innerhalb derselben Bean bliebe die Annotation
 * wirkungslos. Zudem umschliesst die Transaktion so nur Delete und Insert, waehrend der
 * HTTP-Abruf ausserhalb bleibt und keine Verbindung blockiert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WasteCollectionResyncService {

    private final WasteCollectionEventRepository repository;

    /**
     * Loescht alle Termine ab {@code from} und schreibt {@code parsed} neu. Vergangenes bleibt
     * als Historie stehen.
     */
    @Transactional
    public void resync(LocalDate from, List<ParsedWasteEvent> parsed) {
        repository.deleteByCollectionDateGreaterThanEqual(from);
        List<WasteCollectionEvent> entities = deduplicate(parsed).stream()
                .map(event -> WasteCollectionEvent.builder()
                        .collectionDate(event.date())
                        .label(event.label())
                        .build())
                .toList();
        repository.saveAll(entities);
        log.debug("Resync ab {}: {} Termine geschrieben", from, entities.size());
    }

    /** Der Unique-Constraint (collection_date, label) duldet keine Dubletten aus dem ICS. */
    private List<ParsedWasteEvent> deduplicate(List<ParsedWasteEvent> parsed) {
        return List.copyOf(new LinkedHashSet<>(parsed));
    }
}
```

Run: `cd backend && mvn test -Dtest=WasteCollectionResyncServiceTest`
Expected: PASS, 4 Tests.

- [ ] **Step 5: Failing test für den Polling-Service schreiben**

Create `backend/src/test/java/com/household/manager/service/WasteCalendarPollingServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.entitystate.EntityStateService;
import com.household.manager.repository.WasteCollectionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCalendarPollingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Mock
    private WasteCalendarIcsClient icsClient;
    @Mock
    private WasteCalendarIcsParser icsParser;
    @Mock
    private WasteCollectionResyncService resyncService;
    @Mock
    private WasteCollectionEventRepository repository;
    @Mock
    private WasteCollectionSettingsService settingsService;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private TaskScheduler taskScheduler;

    private WasteCalendarPollingService service;

    @BeforeEach
    void setUp() {
        service = new WasteCalendarPollingService(
                icsClient, icsParser, resyncService, repository, settingsService,
                entityStateService, taskScheduler, CLOCK);
    }

    @Test
    void ruftNichtAbWennDasFeatureAusgeschaltetIst() {
        when(settingsService.isEnabled()).thenReturn(false);

        service.scheduledPoll();

        verifyNoInteractions(icsClient);
        verifyNoInteractions(resyncService);
    }

    @Test
    void ruftNichtAbWennKeineUrlHinterlegtIst() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("");

        service.scheduledPoll();

        verifyNoInteractions(icsClient);
        verifyNoInteractions(resyncService);
    }

    @Test
    void setztLastErrorUndResynchedNichtWennDerAbrufScheitert() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        when(icsClient.fetch(anyString()))
                .thenThrow(new WasteCalendarException("Kalender ist nicht erreichbar: timeout"));

        service.scheduledPoll();

        assertThat(service.getStatus().getLastError()).contains("nicht erreichbar");
        // Entscheidend: Ein Ausfall der Quelle darf die Tabelle nicht anfassen.
        verifyNoInteractions(resyncService);
    }

    @Test
    void resynchedNichtWennDasParsenKeineTermineLiefert() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        when(icsClient.fetch(anyString())).thenReturn("BEGIN:VCALENDAR\nEND:VCALENDAR");
        when(icsParser.parse(anyString(), any(), any())).thenReturn(List.of());

        service.scheduledPoll();

        verifyNoInteractions(resyncService);
        assertThat(service.getStatus().getLastError()).isNull();
    }

    @Test
    void resynchedBeiErfolgreichemAbruf() {
        List<ParsedWasteEvent> parsed = List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Restmuell"));
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.getIcsUrl()).thenReturn("https://x/cal.ics");
        when(icsClient.fetch(anyString())).thenReturn("ics");
        when(icsParser.parse(anyString(), any(), any())).thenReturn(parsed);

        service.scheduledPoll();

        verify(resyncService).resync(TODAY, parsed);
        assertThat(service.getStatus().getLastError()).isNull();
        assertThat(service.getStatus().getLastPollTime()).isNotNull();
    }
}
```

- [ ] **Step 6: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn test -Dtest=WasteCalendarPollingServiceTest`
Expected: FAIL — `WasteCalendarPollingService` existiert nicht.

- [ ] **Step 7: Polling-Service implementieren**

Create `backend/src/main/java/com/household/manager/service/WasteCalendarPollingService.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.WastePollingStatusResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Spiegelt den ICS-Kalender taeglich in die Tabelle {@code waste_collection_events}.
 *
 * <p>Resync-Strategie: Bei Erfolg wird das Zukunftsfenster (ab heute) geloescht und neu
 * geschrieben — das uebernimmt {@link WasteCollectionResyncService} in einer eigenen
 * Transaktion. Verschobene und abgesagte Termine bilden sich dadurch korrekt ab, ohne sich
 * auf instabile ICS-UIDs zu stuetzen. Bei jedem Fehler bleibt die Tabelle unangetastet, damit
 * ein Ausfall der Quelle nicht die Dashboard-Kachel leert.
 */
@Service
@Slf4j
public class WasteCalendarPollingService {

    private static final String SCHEDULE = "Taeglich";
    /** Wie weit in die Zukunft der Kalender gespiegelt wird. */
    private static final int SYNC_WINDOW_MONTHS = 12;

    private final WasteCalendarIcsClient icsClient;
    private final WasteCalendarIcsParser icsParser;
    private final WasteCollectionResyncService resyncService;
    private final WasteCollectionEventRepository repository;
    private final WasteCollectionSettingsService settingsService;
    private final EntityStateService entityStateService;
    private final TaskScheduler taskScheduler;
    private final Clock clock;

    public WasteCalendarPollingService(WasteCalendarIcsClient icsClient,
                                       WasteCalendarIcsParser icsParser,
                                       WasteCollectionResyncService resyncService,
                                       WasteCollectionEventRepository repository,
                                       WasteCollectionSettingsService settingsService,
                                       EntityStateService entityStateService,
                                       TaskScheduler taskScheduler,
                                       Clock clock) {
        this.icsClient = icsClient;
        this.icsParser = icsParser;
        this.resyncService = resyncService;
        this.repository = repository;
        this.settingsService = settingsService;
        this.entityStateService = entityStateService;
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;

    public WastePollingStatusResponse getStatus() {
        return WastePollingStatusResponse.builder()
                .schedule(SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .knownEventCount(countKnown())
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::scheduledPoll, Instant.now());
    }

    @Scheduled(
            fixedDelayString = "${waste.polling.interval-ms:86400000}",
            initialDelayString = "${waste.polling.initial-delay-ms:30000}"
    )
    public void scheduledPoll() {
        if (!settingsService.isEnabled()) {
            log.debug("Muellabfuhr-Abruf uebersprungen: Feature ist deaktiviert");
            return;
        }
        String icsUrl = settingsService.getIcsUrl();
        if (icsUrl == null || icsUrl.isBlank()) {
            log.info("Muellabfuhr-Abruf uebersprungen: keine Kalender-URL hinterlegt");
            return;
        }
        safePoll(icsUrl);
    }

    private void safePoll(String icsUrl) {
        try {
            lastPollTime = LocalDateTime.now(clock);
            LocalDate from = LocalDate.now(clock);
            LocalDate to = from.plusMonths(SYNC_WINDOW_MONTHS);

            String ics = icsClient.fetch(icsUrl);
            List<ParsedWasteEvent> parsed = icsParser.parse(ics, from, to);

            if (parsed.isEmpty()) {
                // Bewusst kein Resync: ein leeres Ergebnis ist mehrdeutig (echt leerer Kalender
                // oder stiller Fehler). Alte Termine stehen zu lassen ist das harmlosere Risiko.
                log.warn("Kalender lieferte keine Termine; bestehende Daten bleiben unveraendert");
                lastError = null;
                return;
            }

            resyncService.resync(from, parsed);
            reportNextCollection();
            lastError = null;
            log.info("Muellabfuhr-Kalender aktualisiert: {} Termine", parsed.size());
        } catch (Exception ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("Muellabfuhr-Kalender konnte nicht abgerufen werden", ex);
        }
    }

    private void reportNextCollection() {
        try {
            LocalDate today = LocalDate.now(clock);
            List<WasteCollectionEvent> upcoming =
                    repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
                            today, today.plusMonths(SYNC_WINDOW_MONTHS));

            String state = "unknown";
            Map<String, Object> attributes = Map.of();
            if (!upcoming.isEmpty()) {
                WasteCollectionEvent next = upcoming.get(0);
                state = next.getCollectionDate().toString();
                attributes = Map.of(
                        "label", next.getLabel(),
                        "daysUntil", ChronoUnit.DAYS.between(today, next.getCollectionDate()));
            }

            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.WASTE,
                            "calendar", "next_collection"))
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.WASTE)
                    .sourceRef("calendar")
                    .friendlyName("Naechste Muellabfuhr")
                    .state(state)
                    .attributes(attributes)
                    .build());
        } catch (Exception ex) {
            // Die Entity-Schicht darf den Abruf nicht scheitern lassen (Muster wie beim Wetter).
            log.warn("Entity-State der Muellabfuhr konnte nicht gemeldet werden: {}", ex.getMessage());
        }
    }

    private long countKnown() {
        try {
            return repository.countByCollectionDateGreaterThanEqual(LocalDate.now(clock));
        } catch (Exception ex) {
            log.warn("Anzahl bekannter Termine nicht ermittelbar: {}", ex.getMessage());
            return 0L;
        }
    }
}
```

- [ ] **Step 8: Beide Tests laufen lassen — müssen grün sein**

Run: `cd backend && mvn test -Dtest='WasteCollectionResyncServiceTest,WasteCalendarPollingServiceTest'`
Expected: PASS, 4 + 5 = 9 Tests.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java backend/src/main/java/com/household/manager/dto/WastePollingStatusResponse.java backend/src/main/java/com/household/manager/service/WasteCollectionResyncService.java backend/src/main/java/com/household/manager/service/WasteCalendarPollingService.java backend/src/test/java/com/household/manager/service/WasteCollectionResyncServiceTest.java backend/src/test/java/com/household/manager/service/WasteCalendarPollingServiceTest.java
git commit -m "feat(waste): taeglicher Kalenderabruf mit Resync und Entity-State"
```

---

### Task 8: Ansagetext

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/WasteAnnouncementTextBuilder.java`
- Test: `backend/src/test/java/com/household/manager/service/WasteAnnouncementTextBuilderTest.java`

- [ ] **Step 1: Failing test schreiben**

Create `backend/src/test/java/com/household/manager/service/WasteAnnouncementTextBuilderTest.java`:

```java
package com.household.manager.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WasteAnnouncementTextBuilderTest {

    private final WasteAnnouncementTextBuilder builder = new WasteAnnouncementTextBuilder();

    @Test
    void einLabel() {
        assertThat(builder.buildTomorrowText(List.of("Biotonne")))
                .isEqualTo("Erinnerung: Morgen wird abgeholt: Biotonne.");
    }

    @Test
    void zweiLabelsWerdenMitUndVerbunden() {
        assertThat(builder.buildTomorrowText(List.of("Biotonne", "Restmuell")))
                .isEqualTo("Erinnerung: Morgen wird abgeholt: Biotonne und Restmuell.");
    }

    @Test
    void dreiLabelsNutzenKommaUndAmEndeUnd() {
        assertThat(builder.buildTomorrowText(List.of("Biotonne", "Restmuell", "Gelber Sack")))
                .isEqualTo("Erinnerung: Morgen wird abgeholt: Biotonne, Restmuell und Gelber Sack.");
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn test -Dtest=WasteAnnouncementTextBuilderTest`
Expected: FAIL — `WasteAnnouncementTextBuilder` existiert nicht.

- [ ] **Step 3: Implementieren**

Create `backend/src/main/java/com/household/manager/service/WasteAnnouncementTextBuilder.java`:

```java
package com.household.manager.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Baut den Ansagetext fuer die Abend-Erinnerung.
 *
 * <p>Bewusst neutral formuliert ("wird abgeholt: X") statt "die Biotonne wird geleert": Die
 * Bezeichnungen stammen woertlich aus dem Kalender, Genus und Artikel sind unbekannt
 * ("der Restmuell", "die Biotonne", "der Gelbe Sack").
 */
@Component
public class WasteAnnouncementTextBuilder {

    public String buildTomorrowText(List<String> labels) {
        return "Erinnerung: Morgen wird abgeholt: " + joinNaturally(labels) + ".";
    }

    private String joinNaturally(List<String> labels) {
        if (labels.size() == 1) {
            return labels.get(0);
        }
        String head = String.join(", ", labels.subList(0, labels.size() - 1));
        return head + " und " + labels.get(labels.size() - 1);
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `cd backend && mvn test -Dtest=WasteAnnouncementTextBuilderTest`
Expected: PASS, 3 Tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/WasteAnnouncementTextBuilder.java backend/src/test/java/com/household/manager/service/WasteAnnouncementTextBuilderTest.java
git commit -m "feat(waste): Ansagetext fuer die Abend-Erinnerung"
```

---

### Task 9: Erinnerungs-Scheduler

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/WasteReminderService.java`
- Test: `backend/src/test/java/com/household/manager/service/WasteReminderServiceTest.java`

- [ ] **Step 1: Failing test schreiben**

Create `backend/src/test/java/com/household/manager/service/WasteReminderServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.alexa.AlexaException;
import com.household.manager.alexa.AlexaTtsMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WasteReminderServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);

    @Mock
    private WasteCollectionService collectionService;
    @Mock
    private WasteCollectionSettingsService settingsService;
    @Mock
    private AlexaAnnouncementService announcementService;

    private final WasteAnnouncementTextBuilder textBuilder = new WasteAnnouncementTextBuilder();

    /** Baut den Service mit einer auf {@code time} fixierten Uhr. */
    private WasteReminderService serviceAt(LocalTime time) {
        Clock clock = Clock.fixed(TODAY.atTime(time).atZone(ZONE).toInstant(), ZONE);
        return new WasteReminderService(
                collectionService, settingsService, announcementService, textBuilder, clock);
    }

    /** Standardfall: alles aktiv, 19:00 konfiguriert, morgen steht die Biotonne an. */
    private void givenReadyToAnnounce() {
        when(settingsService.isEnabled()).thenReturn(true);
        when(settingsService.isReminderEnabled()).thenReturn(true);
        when(settingsService.getReminderTime()).thenReturn(LocalTime.of(19, 0));
        when(settingsService.getReminderAlexaSerials()).thenReturn(List.of("DSN1"));
        when(settingsService.wasAnnouncedOn(TODAY)).thenReturn(false);
        when(collectionService.today()).thenReturn(TODAY);
        when(collectionService.getLabelsForTomorrow()).thenReturn(List.of("Biotonne"));
    }

    @Test
    void sagtAnWennAlleBedingungenErfuelltSind() {
        givenReadyToAnnounce();

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verify(announcementService).announce(
                "Erinnerung: Morgen wird abgeholt: Biotonne.",
                List.of("DSN1"),
                AlexaTtsMode.ANNOUNCE);
        verify(settingsService).markAnnounced(TODAY);
    }

    @Test
    void sagtNichtAnVorDerKonfiguriertenUhrzeit() {
        givenReadyToAnnounce();

        serviceAt(LocalTime.of(18, 59)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtNichtAnNachDemEinstuendigenFenster() {
        givenReadyToAnnounce();

        // Ein Neustart um 23:00 Uhr darf nicht nachtraeglich eine Durchsage ausloesen.
        serviceAt(LocalTime.of(23, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtInnerhalbDesFenstersNochAn() {
        givenReadyToAnnounce();

        serviceAt(LocalTime.of(19, 59)).checkAndAnnounce();

        verify(announcementService).announce(anyString(), anyList(), any());
    }

    @Test
    void sagtNichtZweimalAmSelbenTagAn() {
        givenReadyToAnnounce();
        when(settingsService.wasAnnouncedOn(TODAY)).thenReturn(true);

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtNichtAnWennMorgenNichtsAnsteht() {
        givenReadyToAnnounce();
        when(collectionService.getLabelsForTomorrow()).thenReturn(List.of());

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
        verify(settingsService, never()).markAnnounced(any());
    }

    @Test
    void sagtNichtAnWennDieErinnerungAbgeschaltetIst() {
        givenReadyToAnnounce();
        when(settingsService.isReminderEnabled()).thenReturn(false);

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void sagtNichtAnWennKeinZielgeraetKonfiguriertIst() {
        givenReadyToAnnounce();
        when(settingsService.getReminderAlexaSerials()).thenReturn(List.of());

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        verifyNoInteractions(announcementService);
    }

    @Test
    void merktSichNichtsWennDieAnsageScheitert() {
        givenReadyToAnnounce();
        doThrow(new AlexaException("Sidecar offline"))
                .when(announcementService).announce(anyString(), anyList(), any());

        serviceAt(LocalTime.of(19, 0)).checkAndAnnounce();

        // Ohne Merker versucht es der naechste Lauf innerhalb des Fensters erneut.
        verify(settingsService, never()).markAnnounced(any());
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn test -Dtest=WasteReminderServiceTest`
Expected: FAIL — `WasteReminderService` existiert nicht.

- [ ] **Step 3: Implementieren**

Create `backend/src/main/java/com/household/manager/service/WasteReminderService.java`:

```java
package com.household.manager.service;

import com.household.manager.alexa.AlexaTtsMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Loest am Vorabend die Alexa-Durchsage aus.
 *
 * <p>Geprueft wird minuetlich statt per Cron-Ausdruck, weil die Ansagezeit zur Laufzeit in den
 * Settings aenderbar ist und ein statisches {@code @Scheduled(cron=...)} sie nicht lesen kann.
 * Die Pruefung kostet einen Settings-Read und eine indizierte Datumsabfrage.
 */
@Service
@Slf4j
public class WasteReminderService {

    /**
     * Laenge des Ansage-Fensters ab der konfigurierten Uhrzeit. Ohne diese Obergrenze wuerde
     * ein Neustart spaet am Abend noch eine Durchsage ausloesen.
     */
    private static final Duration ANNOUNCE_WINDOW = Duration.ofMinutes(60);

    private final WasteCollectionService collectionService;
    private final WasteCollectionSettingsService settingsService;
    private final AlexaAnnouncementService announcementService;
    private final WasteAnnouncementTextBuilder textBuilder;
    private final Clock clock;

    public WasteReminderService(WasteCollectionService collectionService,
                                WasteCollectionSettingsService settingsService,
                                AlexaAnnouncementService announcementService,
                                WasteAnnouncementTextBuilder textBuilder,
                                Clock clock) {
        this.collectionService = collectionService;
        this.settingsService = settingsService;
        this.announcementService = announcementService;
        this.textBuilder = textBuilder;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${waste.reminder.check-interval-ms:60000}")
    public void checkAndAnnounce() {
        try {
            if (!shouldAnnounceNow()) {
                return;
            }
            List<String> labels = collectionService.getLabelsForTomorrow();
            if (labels.isEmpty()) {
                return;
            }
            List<String> serials = settingsService.getReminderAlexaSerials();

            announcementService.announce(
                    textBuilder.buildTomorrowText(labels), serials, AlexaTtsMode.ANNOUNCE);

            // Erst nach erfolgreicher Ansage merken: schlaegt sie fehl, versucht es der
            // naechste Lauf im Fenster erneut.
            settingsService.markAnnounced(collectionService.today());
            log.info("Muellabfuhr-Erinnerung angesagt: {}", labels);
        } catch (Exception ex) {
            log.error("Muellabfuhr-Erinnerung konnte nicht angesagt werden", ex);
        }
    }

    private boolean shouldAnnounceNow() {
        if (!settingsService.isEnabled() || !settingsService.isReminderEnabled()) {
            return false;
        }
        if (settingsService.getReminderAlexaSerials().isEmpty()) {
            return false;
        }
        if (!isWithinAnnounceWindow()) {
            return false;
        }
        return !settingsService.wasAnnouncedOn(collectionService.today());
    }

    private boolean isWithinAnnounceWindow() {
        LocalTime now = LocalTime.now(clock);
        LocalTime start = settingsService.getReminderTime();
        LocalTime end = start.plus(ANNOUNCE_WINDOW);

        if (end.isAfter(start)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // Fenster laeuft ueber Mitternacht (z. B. Ansagezeit 23:30).
        return !now.isBefore(start) || now.isBefore(end);
    }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `cd backend && mvn test -Dtest=WasteReminderServiceTest`
Expected: PASS, 9 Tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/service/WasteReminderService.java backend/src/test/java/com/household/manager/service/WasteReminderServiceTest.java
git commit -m "feat(waste): Abend-Erinnerung mit Zeitfenster und Dedup"
```

---

### Task 10: REST-Controller

**Files:**
- Create: `backend/src/main/java/com/household/manager/controller/WasteCollectionController.java`
- Create: `backend/src/main/java/com/household/manager/controller/WastePollingAdminController.java`
- Test: `backend/src/test/java/com/household/manager/controller/WasteCollectionControllerTest.java`

- [ ] **Step 1: Failing test schreiben**

Reiner Unit-Test der Controller-Klasse (kein `@WebMvcTest`, da die Tests ohne Spring-Kontext
laufen sollen).

Create `backend/src/test/java/com/household/manager/controller/WasteCollectionControllerTest.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.dto.WasteCollectionSettings;
import com.household.manager.service.WasteCollectionService;
import com.household.manager.service.WasteCollectionSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCollectionControllerTest {

    @Mock
    private WasteCollectionService collectionService;
    @Mock
    private WasteCollectionSettingsService settingsService;

    private WasteCollectionController controller;

    @BeforeEach
    void setUp() {
        controller = new WasteCollectionController(collectionService, settingsService);
    }

    private WasteCollectionSettings validSettings() {
        return WasteCollectionSettings.builder()
                .enabled(true)
                .icsUrl("https://calendar.google.com/x/basic.ics")
                .lookaheadDays(3)
                .reminderEnabled(true)
                .reminderTime("19:00")
                .reminderAlexaSerials(List.of("DSN1"))
                .build();
    }

    @Test
    void upcomingOhneDaysNutztDasKonfigurierteFenster() {
        when(settingsService.getLookaheadDays()).thenReturn(5);
        when(collectionService.getUpcoming(5)).thenReturn(List.of(
                WasteCollectionEventResponse.builder().label("Biotonne").daysUntil(1).build()));

        ResponseEntity<List<WasteCollectionEventResponse>> response = controller.getUpcoming(null);

        assertThat(response.getBody()).hasSize(1);
        verify(collectionService).getUpcoming(5);
    }

    @Test
    void upcomingMitDaysNutztDenParameter() {
        when(collectionService.getUpcoming(60)).thenReturn(List.of());

        controller.getUpcoming(60);

        verify(collectionService).getUpcoming(60);
        verify(settingsService, never()).getLookaheadDays();
    }

    @Test
    void speichertGueltigeSettings() {
        WasteCollectionSettings settings = validSettings();
        when(settingsService.getSettings()).thenReturn(settings);

        ResponseEntity<WasteCollectionSettings> response = controller.updateSettings(settings);

        verify(settingsService).saveSettings(settings);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void lehntUngueltigeUrlAb() {
        WasteCollectionSettings settings = validSettings();
        settings.setIcsUrl("nicht-mal-eine-url");

        assertThatThrownBy(() -> controller.updateSettings(settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("URL");
        verify(settingsService, never()).saveSettings(settings);
    }

    @Test
    void erlaubtLeereUrl() {
        WasteCollectionSettings settings = validSettings();
        settings.setIcsUrl("");
        when(settingsService.getSettings()).thenReturn(settings);

        controller.updateSettings(settings);

        verify(settingsService).saveSettings(settings);
    }

    @Test
    void lehntZuKleinesVorschaufensterAb() {
        WasteCollectionSettings settings = validSettings();
        settings.setLookaheadDays(0);

        assertThatThrownBy(() -> controller.updateSettings(settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Vorschau");
        verify(settingsService, never()).saveSettings(settings);
    }

    @Test
    void lehntUnparsbareUhrzeitAb() {
        WasteCollectionSettings settings = validSettings();
        settings.setReminderTime("abends");

        assertThatThrownBy(() -> controller.updateSettings(settings))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Uhrzeit");
        verify(settingsService, never()).saveSettings(settings);
    }
}
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd backend && mvn test -Dtest=WasteCollectionControllerTest`
Expected: FAIL — `WasteCollectionController` existiert nicht.

- [ ] **Step 3: Controller implementieren**

Create `backend/src/main/java/com/household/manager/controller/WasteCollectionController.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.dto.WasteCollectionSettings;
import com.household.manager.service.WasteCollectionService;
import com.household.manager.service.WasteCollectionSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Termine und Konfiguration der Muellabfuhr.
 * Basis-URL: /api/v1/waste-collection
 */
@RestController
@RequestMapping("/v1/waste-collection")
@RequiredArgsConstructor
@Slf4j
public class WasteCollectionController {

    private final WasteCollectionService collectionService;
    private final WasteCollectionSettingsService settingsService;

    /**
     * @param days optionales Fenster; ohne Angabe gilt {@code lookahead_days} aus den Settings
     */
    @GetMapping("/upcoming")
    public ResponseEntity<List<WasteCollectionEventResponse>> getUpcoming(
            @RequestParam(name = "days", required = false) Integer days) {
        int window = days != null ? days : settingsService.getLookaheadDays();
        return ResponseEntity.ok(collectionService.getUpcoming(window));
    }

    @GetMapping("/settings")
    public ResponseEntity<WasteCollectionSettings> getSettings() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping("/settings")
    public ResponseEntity<WasteCollectionSettings> updateSettings(
            @RequestBody WasteCollectionSettings settings) {
        validate(settings);
        settingsService.saveSettings(settings);
        return ResponseEntity.ok(settingsService.getSettings());
    }

    private void validate(WasteCollectionSettings settings) {
        validateIcsUrl(settings.getIcsUrl());

        if (settings.getLookaheadDays() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Vorschau muss mindestens 1 Tag umfassen.");
        }

        try {
            LocalTime.parse(settings.getReminderTime());
        } catch (NullPointerException | DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Uhrzeit muss im Format HH:mm angegeben werden.");
        }
    }

    /** Leer ist erlaubt (Feature dann inaktiv); sonst muss es eine http(s)-URL sein. */
    private void validateIcsUrl(String icsUrl) {
        if (icsUrl == null || icsUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(icsUrl);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Kalender-URL ist keine gueltige URL.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Kalender-URL muss mit http:// oder https:// beginnen.");
        }
    }
}
```

- [ ] **Step 4: Admin-Controller implementieren**

Create `backend/src/main/java/com/household/manager/controller/WastePollingAdminController.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.WastePollingStatusResponse;
import com.household.manager.service.WasteCalendarPollingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-Endpunkte zur Steuerung des Muellabfuhr-Kalenderabrufs.
 * Basis-URL: /api/v1/admin/waste-polling
 */
@RestController
@RequestMapping("/v1/admin/waste-polling")
@RequiredArgsConstructor
@Slf4j
public class WastePollingAdminController {

    private final WasteCalendarPollingService pollingService;

    @GetMapping
    public ResponseEntity<WastePollingStatusResponse> getStatus() {
        return ResponseEntity.ok(pollingService.getStatus());
    }

    @PostMapping("/trigger")
    public ResponseEntity<Void> trigger() {
        log.info("Muellabfuhr-Kalenderabruf wird manuell ausgeloest");
        pollingService.triggerOnce();
        return ResponseEntity.accepted().build();
    }
}
```

- [ ] **Step 5: Tests laufen lassen — müssen grün sein**

Run: `cd backend && mvn test -Dtest=WasteCollectionControllerTest`
Expected: PASS, 7 Tests.

- [ ] **Step 6: Gesamten Backend-Testlauf prüfen**

Run: `cd backend && mvn test -Dtest='Waste*'`
Expected: PASS — alle Waste-Tests (Parser, Settings, Service, Polling, TextBuilder, Reminder, Controller).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/controller/WasteCollectionController.java backend/src/main/java/com/household/manager/controller/WastePollingAdminController.java backend/src/test/java/com/household/manager/controller/WasteCollectionControllerTest.java
git commit -m "feat(waste): REST-API fuer Termine, Settings und Abruf-Steuerung"
```

---

### Task 11: Frontend-Modelle und Service

**Files:**
- Create: `frontend/src/app/models/waste-collection.model.ts`
- Create: `frontend/src/app/services/waste-collection.service.ts`
- Test: `frontend/src/app/services/waste-collection.service.spec.ts`

- [ ] **Step 1: Modelle anlegen**

Create `frontend/src/app/models/waste-collection.model.ts`:

```typescript
/** Ein Abholtermin, wie ihn das Backend liefert. */
export interface WasteCollectionEvent {
  /** ISO-Datum, z. B. "2026-07-17". */
  date: string;
  /** Bezeichnung aus dem Kalender, z. B. "Biotonne". */
  label: string;
  /** 0 = heute, 1 = morgen. */
  daysUntil: number;
}

/** Konfiguration des Muellabfuhr-Kalenders. */
export interface WasteCollectionSettings {
  enabled: boolean;
  icsUrl: string;
  lookaheadDays: number;
  reminderEnabled: boolean;
  /** Format "HH:mm". */
  reminderTime: string;
  reminderAlexaSerials: string[];
}

/** Status des Kalenderabrufs. */
export interface WasteCollectionPollingStatus {
  schedule: string;
  lastPollTime: string | null;
  lastError: string | null;
  knownEventCount: number;
}
```

- [ ] **Step 2: Failing test schreiben**

Create `frontend/src/app/services/waste-collection.service.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { WasteCollectionService } from './waste-collection.service';

describe('WasteCollectionService', () => {
  let service: WasteCollectionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [WasteCollectionService]
    });
    service = TestBed.inject(WasteCollectionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fragt Termine ohne days-Parameter ab', () => {
    service.getUpcoming().subscribe();
    httpMock.expectOne('/api/v1/waste-collection/upcoming').flush([]);
  });

  it('reicht days als Query-Parameter durch', () => {
    service.getUpcoming(60).subscribe();
    httpMock.expectOne('/api/v1/waste-collection/upcoming?days=60').flush([]);
  });

  it('speichert Settings per PUT', () => {
    const settings = {
      enabled: true,
      icsUrl: 'https://x/cal.ics',
      lookaheadDays: 3,
      reminderEnabled: true,
      reminderTime: '19:00',
      reminderAlexaSerials: ['DSN1']
    };

    service.updateSettings(settings).subscribe();

    const req = httpMock.expectOne('/api/v1/waste-collection/settings');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(settings);
    req.flush(settings);
  });

  it('loest den Abruf per POST aus', () => {
    service.triggerPoll().subscribe();
    const req = httpMock.expectOne('/api/v1/admin/waste-polling/trigger');
    expect(req.request.method).toBe('POST');
    req.flush(null);
  });
});
```

- [ ] **Step 3: Test laufen lassen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: FAIL — `waste-collection.service` existiert nicht.

- [ ] **Step 4: Service implementieren**

Create `frontend/src/app/services/waste-collection.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  WasteCollectionEvent, WasteCollectionPollingStatus, WasteCollectionSettings
} from '../models/waste-collection.model';

/** Service fuer die Muellabfuhr-API. */
@Injectable({ providedIn: 'root' })
export class WasteCollectionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/waste-collection';
  private readonly adminUrl = '/api/v1/admin/waste-polling';

  /** Ohne days gilt das konfigurierte Vorschau-Fenster. */
  getUpcoming(days?: number): Observable<WasteCollectionEvent[]> {
    const options = days === undefined
      ? {}
      : { params: new HttpParams().set('days', days) };
    return this.http.get<WasteCollectionEvent[]>(`${this.baseUrl}/upcoming`, options)
      .pipe(catchError(this.handleError));
  }

  getSettings(): Observable<WasteCollectionSettings> {
    return this.http.get<WasteCollectionSettings>(`${this.baseUrl}/settings`)
      .pipe(catchError(this.handleError));
  }

  updateSettings(settings: WasteCollectionSettings): Observable<WasteCollectionSettings> {
    return this.http.put<WasteCollectionSettings>(`${this.baseUrl}/settings`, settings)
      .pipe(catchError(this.handleError));
  }

  getPollingStatus(): Observable<WasteCollectionPollingStatus> {
    return this.http.get<WasteCollectionPollingStatus>(this.adminUrl)
      .pipe(catchError(this.handleError));
  }

  triggerPoll(): Observable<void> {
    return this.http.post<void>(`${this.adminUrl}/trigger`, {})
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Muellabfuhr-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Muellabfuhr-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
```

- [ ] **Step 5: Test laufen lassen — muss grün sein**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/waste-collection.service.spec.ts'`
Expected: PASS, 4 Tests.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models/waste-collection.model.ts frontend/src/app/services/waste-collection.service.ts frontend/src/app/services/waste-collection.service.spec.ts
git commit -m "feat(waste): Frontend-Modelle und API-Service"
```

---

### Task 12: Dashboard-Kachel

**Files:**
- Create: `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.ts`
- Create: `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.html`
- Create: `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.scss`
- Test: `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.spec.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html`

- [ ] **Step 1: Failing test schreiben**

Create `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { WasteCollectionTileComponent } from './waste-collection-tile.component';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { WasteCollectionEvent } from '../../models/waste-collection.model';

describe('WasteCollectionTileComponent', () => {
  let fixture: ComponentFixture<WasteCollectionTileComponent>;
  let component: WasteCollectionTileComponent;
  let serviceSpy: jasmine.SpyObj<WasteCollectionService>;

  const event = (daysUntil: number, label: string): WasteCollectionEvent =>
    ({ date: '2026-07-17', label, daysUntil });

  async function setup(events: WasteCollectionEvent[]): Promise<void> {
    serviceSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    serviceSpy.getUpcoming.and.returnValue(of(events));

    await TestBed.configureTestingModule({
      imports: [WasteCollectionTileComponent],
      providers: [{ provide: WasteCollectionService, useValue: serviceSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(WasteCollectionTileComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('rendert keine Kachel, wenn nichts ansteht', async () => {
    await setup([]);
    expect(fixture.nativeElement.querySelector('.waste-tile')).toBeNull();
  });

  it('rendert eine Zeile je Termin', async () => {
    await setup([event(1, 'Biotonne'), event(2, 'Restmuell')]);
    expect(fixture.nativeElement.querySelectorAll('.waste-tile__row').length).toBe(2);
  });

  it('hebt die Morgen-Zeile hervor', async () => {
    await setup([event(0, 'Papier'), event(1, 'Biotonne')]);
    const rows = fixture.nativeElement.querySelectorAll('.waste-tile__row');
    expect(rows[0].classList).not.toContain('waste-tile__row--tomorrow');
    expect(rows[1].classList).toContain('waste-tile__row--tomorrow');
  });

  it('uebersetzt daysUntil in relative Tageslabels', async () => {
    await setup([]);
    expect(component.relativeDayLabel({ date: '2026-07-16', label: 'x', daysUntil: 0 })).toBe('Heute');
    expect(component.relativeDayLabel({ date: '2026-07-17', label: 'x', daysUntil: 1 })).toBe('Morgen');
    expect(component.relativeDayLabel({ date: '2026-07-18', label: 'x', daysUntil: 2 })).toBe('Übermorgen');
    // 2026-07-20 ist ein Montag
    expect(component.relativeDayLabel({ date: '2026-07-20', label: 'x', daysUntil: 4 })).toBe('Montag');
  });

  it('blendet sich bei einem API-Fehler aus, statt zu stoeren', async () => {
    serviceSpy = jasmine.createSpyObj('WasteCollectionService', ['getUpcoming']);
    serviceSpy.getUpcoming.and.returnValue(throwError(() => new Error('kaputt')));

    await TestBed.configureTestingModule({
      imports: [WasteCollectionTileComponent],
      providers: [{ provide: WasteCollectionService, useValue: serviceSpy }]
    }).compileComponents();

    fixture = TestBed.createComponent(WasteCollectionTileComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.waste-tile')).toBeNull();
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/waste-collection-tile.component.spec.ts'`
Expected: FAIL — Komponente existiert nicht.

- [ ] **Step 3: Komponente implementieren**

Create `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.ts`:

```typescript
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, interval, of, startWith, switchMap } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { WasteCollectionEvent } from '../../models/waste-collection.model';

/**
 * Dashboard-Kachel mit den anstehenden Muellabfuhr-Terminen.
 * Rendert sich nur, wenn im konfigurierten Vorschau-Fenster etwas ansteht.
 */
@Component({
  selector: 'app-waste-collection-tile',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './waste-collection-tile.component.html',
  styleUrl: './waste-collection-tile.component.scss'
})
export class WasteCollectionTileComponent implements OnInit, OnDestroy {
  private readonly wasteService = inject(WasteCollectionService);

  /** Die Termine aendern sich hoechstens taeglich; stuendlich nachladen genuegt. */
  private static readonly REFRESH_MS = 3600000;

  private subscription?: Subscription;

  events: WasteCollectionEvent[] = [];

  ngOnInit(): void {
    this.subscription = interval(WasteCollectionTileComponent.REFRESH_MS)
      .pipe(
        startWith(0),
        switchMap(() => this.wasteService.getUpcoming().pipe(catchError(() => of([]))))
      )
      .subscribe(events => this.events = events);
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  /** "Heute"/"Morgen"/"Übermorgen", darueber hinaus der Wochentag. */
  relativeDayLabel(event: WasteCollectionEvent): string {
    switch (event.daysUntil) {
      case 0: return 'Heute';
      case 1: return 'Morgen';
      case 2: return 'Übermorgen';
      default: return this.weekdayOf(event.date);
    }
  }

  /**
   * Wochentag zu einem ISO-Datum. Bewusst aus den Datumsteilen gebaut statt via
   * `new Date('2026-07-20')`: Diese Kurzform parst als UTC-Mitternacht und wuerde bei
   * negativem UTC-Offset den Vortag anzeigen.
   */
  private weekdayOf(isoDate: string): string {
    const [year, month, day] = isoDate.split('-').map(Number);
    return new Date(year, month - 1, day)
      .toLocaleDateString('de-DE', { weekday: 'long' });
  }

  isTomorrow(event: WasteCollectionEvent): boolean {
    return event.daysUntil === 1;
  }

  trackByEvent(_index: number, event: WasteCollectionEvent): string {
    return `${event.date}|${event.label}`;
  }
}
```

Create `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.html`:

```html
<div *ngIf="events.length > 0" class="lumina-card lumina__room waste-tile lumina__fade">
  <div class="lumina__room-top">
    <div class="lumina__room-icon">
      <span class="material-symbols-outlined">delete</span>
    </div>
  </div>
  <div class="lumina__room-body">
    <h3 class="lumina__room-name">Müllabfuhr</h3>
    <div class="waste-tile__rows">
      <div
        *ngFor="let event of events; trackBy: trackByEvent"
        class="waste-tile__row"
        [class.waste-tile__row--tomorrow]="isTomorrow(event)"
      >
        <span class="waste-tile__day">{{ relativeDayLabel(event) }}</span>
        <span class="waste-tile__label">{{ event.label }}</span>
      </div>
    </div>
  </div>
</div>
```

Create `frontend/src/app/components/waste-collection-tile/waste-collection-tile.component.scss`:

```scss
.waste-tile {
  &__rows {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    margin-top: 0.75rem;
  }

  &__row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.75rem;
    padding: 0.35rem 0.5rem;
    border-radius: 0.5rem;
    opacity: 0.85;

    // Was heute Abend angesagt wird, sticht auch visuell heraus.
    &--tomorrow {
      background: rgba(255, 255, 255, 0.08);
      opacity: 1;
      font-weight: 600;
    }
  }

  &__day {
    font-size: 0.85rem;
    letter-spacing: 0.02em;
    text-transform: uppercase;
    opacity: 0.7;
  }

  &__label {
    font-size: 0.95rem;
    text-align: right;
  }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/waste-collection-tile.component.spec.ts'`
Expected: PASS, 5 Tests.

- [ ] **Step 5: Kachel ins Dashboard einhängen**

In `frontend/src/app/pages/dashboard/dashboard.component.ts` den Import ergänzen:

```typescript
import { WasteCollectionTileComponent } from '../../components/waste-collection-tile/waste-collection-tile.component';
```

und in den `imports` des `@Component`-Dekorators aufnehmen:

```typescript
  imports: [CommonModule, RouterLink, EnergyFlowComponent, WasteCollectionTileComponent],
```

In `frontend/src/app/pages/dashboard/dashboard.component.html` die Kachel im
`lumina__rooms`-Grid direkt nach der schließenden `</a>` der Klima-Kachel und vor der
`<!-- Raum-Kacheln (Platzhalter) -->`-Schleife einfügen:

```html
        <!-- Muellabfuhr: blendet sich aus, wenn nichts ansteht -->
        <app-waste-collection-tile></app-waste-collection-tile>
```

- [ ] **Step 6: Dashboard baut noch**

Für das Dashboard existiert kein Spec, der Build ist hier also die Absicherung.

Run: `cd frontend && npx ng build`
Expected: BUILD SUCCESS — die Kachel ist aufgeloest und das Template kompiliert.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/components/waste-collection-tile/ frontend/src/app/pages/dashboard/
git commit -m "feat(waste): Dashboard-Kachel mit hervorgehobenem Morgen-Termin"
```

---

### Task 13: Alexa-Geräte-Picker wiederverwendbar machen

Die Komponente ist bereits generisch (haengt nur an `AlexaService`/`AlexaDevice`), liegt aber
unter `pages/flows/pickers/`. Mit der Einstellungsseite bekommt sie ihren zweiten Konsumenten
ausserhalb der Flows — deshalb der Umzug. Reiner Move, kein Verhaltenswechsel.

**Files:**
- Create: `frontend/src/app/components/alexa-device-picker/alexa-device-picker.component.ts`
- Create: `frontend/src/app/components/alexa-device-picker/alexa-device-picker.component.html`
- Delete: `frontend/src/app/pages/flows/pickers/alexa-device-picker.component.ts`
- Delete: `frontend/src/app/pages/flows/pickers/alexa-device-picker.component.html`
- Modify: die Datei, die den Picker importiert (in Step 1 ermittelt)

- [ ] **Step 1: Konsumenten ermitteln**

Run: `cd frontend && grep -rn "alexa-device-picker\|AlexaDevicePickerComponent" src/ --include=*.ts`
Expected: Treffer in der Picker-Datei selbst plus mindestens ein Importeur im Flow-Editor.
Die Importeur-Pfade notieren — sie werden in Step 4 angepasst.

- [ ] **Step 2: Dateien verschieben**

```bash
cd frontend
mkdir -p src/app/components/alexa-device-picker
git mv src/app/pages/flows/pickers/alexa-device-picker.component.ts src/app/components/alexa-device-picker/alexa-device-picker.component.ts
git mv src/app/pages/flows/pickers/alexa-device-picker.component.html src/app/components/alexa-device-picker/alexa-device-picker.component.html
```

- [ ] **Step 3: Relative Importe und Doc-Kommentar anpassen**

In `frontend/src/app/components/alexa-device-picker/alexa-device-picker.component.ts` sind die
Service-Importe eine Ebene zu tief (`../../../services` → `../../services`). Die Datei muss
danach so aussehen:

```typescript
import { Component, EventEmitter, OnInit, Output, computed, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AlexaService } from '../../services/alexa.service';
import { AlexaDevice } from '../../models/alexa.model';

/** Mehrfachauswahl fuer Alexa-Geraete. Wert = string[] von serialNumbers. */
@Component({
  selector: 'app-alexa-device-picker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './alexa-device-picker.component.html'
})
export class AlexaDevicePickerComponent implements OnInit {
  private readonly alexaService = inject(AlexaService);

  readonly value = input<string[]>([]);
  @Output() valueChange = new EventEmitter<string[]>();

  readonly options = signal<AlexaDevice[]>([]);

  readonly selected = computed(() => new Set(this.value() ?? []));

  ngOnInit(): void {
    this.alexaService.getDevices().subscribe(list => this.options.set(list));
  }

  isSelected(serial: string): boolean {
    return this.selected().has(serial);
  }

  toggle(serial: string, checked: boolean): void {
    const next = new Set(this.value() ?? []);
    if (checked) { next.add(serial); } else { next.delete(serial); }
    this.valueChange.emit([...next]);
  }
}
```

Der Doc-Kommentar verliert den Bezug auf den Flow-Feldtyp `ALEXA_DEVICE_LIST`, weil die
Komponente nun auch ausserhalb der Flows genutzt wird.

Die HTML-Datei bleibt unveraendert — sie referenziert keine Pfade.

- [ ] **Step 4: Importe beim Flow-Editor korrigieren**

In jeder in Step 1 gefundenen Importeur-Datei den Pfad umbiegen, z. B.:

```typescript
// vorher
import { AlexaDevicePickerComponent } from './pickers/alexa-device-picker.component';
// nachher
import { AlexaDevicePickerComponent } from '../../components/alexa-device-picker/alexa-device-picker.component';
```

Den korrekten relativen Pfad aus der Lage der jeweiligen Importeur-Datei ableiten.

- [ ] **Step 5: Build prüfen**

Run: `cd frontend && npx ng build`
Expected: BUILD SUCCESS — kein unaufgeloester Import.

- [ ] **Step 6: Commit**

```bash
git add -A frontend/src/app/components/alexa-device-picker/ frontend/src/app/pages/flows/
git commit -m "refactor(alexa): Geraete-Picker nach components/ fuer Wiederverwendung"
```

---

### Task 14: Einstellungsseite

**Files:**
- Create: `frontend/src/app/pages/waste-collection/waste-collection.component.ts`
- Create: `frontend/src/app/pages/waste-collection/waste-collection.component.html`
- Create: `frontend/src/app/pages/waste-collection/waste-collection.component.scss`
- Test: `frontend/src/app/pages/waste-collection/waste-collection.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts`

- [ ] **Step 1: Failing test schreiben**

Create `frontend/src/app/pages/waste-collection/waste-collection.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { WasteCollectionComponent } from './waste-collection.component';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { AlexaService } from '../../services/alexa.service';
import { WasteCollectionSettings } from '../../models/waste-collection.model';

describe('WasteCollectionComponent', () => {
  let fixture: ComponentFixture<WasteCollectionComponent>;
  let component: WasteCollectionComponent;
  let wasteSpy: jasmine.SpyObj<WasteCollectionService>;

  const settings: WasteCollectionSettings = {
    enabled: true,
    icsUrl: 'https://x/cal.ics',
    lookaheadDays: 3,
    reminderEnabled: true,
    reminderTime: '19:00',
    reminderAlexaSerials: ['DSN1']
  };

  async function setup(): Promise<void> {
    wasteSpy = jasmine.createSpyObj('WasteCollectionService',
      ['getUpcoming', 'getSettings', 'updateSettings', 'getPollingStatus', 'triggerPoll']);
    wasteSpy.getUpcoming.and.returnValue(of([]));
    wasteSpy.getSettings.and.returnValue(of({ ...settings }));
    wasteSpy.updateSettings.and.returnValue(of({ ...settings }));
    wasteSpy.getPollingStatus.and.returnValue(of({
      schedule: 'Taeglich', lastPollTime: null, lastError: null, knownEventCount: 0
    }));
    wasteSpy.triggerPoll.and.returnValue(of(undefined));

    const alexaSpy = jasmine.createSpyObj('AlexaService', ['getDevices']);
    alexaSpy.getDevices.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [WasteCollectionComponent],
      providers: [
        { provide: WasteCollectionService, useValue: wasteSpy },
        { provide: AlexaService, useValue: alexaSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(WasteCollectionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('laedt Settings, Status und Termine beim Start', async () => {
    await setup();
    expect(wasteSpy.getSettings).toHaveBeenCalled();
    expect(wasteSpy.getPollingStatus).toHaveBeenCalled();
    expect(wasteSpy.getUpcoming).toHaveBeenCalledWith(60);
    expect(component.settings?.icsUrl).toBe('https://x/cal.ics');
  });

  it('sendet die Settings beim Speichern', async () => {
    await setup();
    component.settings!.lookaheadDays = 5;

    component.saveSettings();

    expect(wasteSpy.updateSettings).toHaveBeenCalledWith(
      jasmine.objectContaining({ lookaheadDays: 5 }));
    expect(component.saveSuccess).toBeTrue();
    expect(component.saveError).toBe('');
  });

  it('meldet einen Fehler beim Speichern', async () => {
    await setup();
    wasteSpy.updateSettings.and.returnValue(throwError(() => new Error('Ungueltige URL')));

    component.saveSettings();

    expect(component.saveError).toBe('Ungueltige URL');
    expect(component.saveSuccess).toBeFalse();
  });

  it('laedt nach dem manuellen Abruf Status und Termine neu', async () => {
    await setup();
    wasteSpy.getPollingStatus.calls.reset();
    wasteSpy.getUpcoming.calls.reset();

    component.triggerPoll();

    expect(wasteSpy.triggerPoll).toHaveBeenCalled();
    expect(wasteSpy.getPollingStatus).toHaveBeenCalled();
    expect(wasteSpy.getUpcoming).toHaveBeenCalledWith(60);
  });

  it('uebernimmt die Geraeteauswahl aus dem Picker', async () => {
    await setup();

    component.onSerialsChange(['DSN1', 'DSN2']);

    expect(component.settings!.reminderAlexaSerials).toEqual(['DSN1', 'DSN2']);
  });
});
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/waste-collection.component.spec.ts'`
Expected: FAIL — Komponente existiert nicht.

- [ ] **Step 3: Komponente implementieren**

Create `frontend/src/app/pages/waste-collection/waste-collection.component.ts`:

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { AlexaDevicePickerComponent } from '../../components/alexa-device-picker/alexa-device-picker.component';
import {
  WasteCollectionEvent, WasteCollectionPollingStatus, WasteCollectionSettings
} from '../../models/waste-collection.model';

/** Einstellungsseite fuer den Muellabfuhr-Kalender: Termine, Konfiguration, Abruf-Status. */
@Component({
  selector: 'app-waste-collection',
  standalone: true,
  imports: [CommonModule, FormsModule, AlexaDevicePickerComponent],
  templateUrl: './waste-collection.component.html',
  styleUrl: './waste-collection.component.scss'
})
export class WasteCollectionComponent implements OnInit {
  private readonly wasteService = inject(WasteCollectionService);

  /** Ausblick der Terminliste auf dieser Seite — unabhaengig vom Dashboard-Fenster. */
  private static readonly PREVIEW_DAYS = 60;

  settings: WasteCollectionSettings | null = null;
  status: WasteCollectionPollingStatus | null = null;
  events: WasteCollectionEvent[] = [];

  isSaving = false;
  saveSuccess = false;
  saveError = '';

  isTriggering = false;
  triggerError = '';

  ngOnInit(): void {
    this.loadSettings();
    this.loadStatus();
    this.loadEvents();
  }

  loadSettings(): void {
    this.wasteService.getSettings().subscribe({
      next: settings => this.settings = settings,
      error: err => console.error('Muellabfuhr-Einstellungen nicht ladbar:', err)
    });
  }

  loadStatus(): void {
    this.wasteService.getPollingStatus().subscribe({
      next: status => this.status = status,
      error: err => console.error('Abruf-Status nicht ladbar:', err)
    });
  }

  loadEvents(): void {
    this.wasteService.getUpcoming(WasteCollectionComponent.PREVIEW_DAYS).subscribe({
      next: events => this.events = events,
      error: err => console.error('Termine nicht ladbar:', err)
    });
  }

  saveSettings(): void {
    if (!this.settings) {
      return;
    }
    this.isSaving = true;
    this.saveSuccess = false;
    this.saveError = '';

    this.wasteService.updateSettings(this.settings).subscribe({
      next: updated => {
        this.settings = updated;
        this.isSaving = false;
        this.saveSuccess = true;
        setTimeout(() => { this.saveSuccess = false; }, 3000);
      },
      error: err => {
        this.isSaving = false;
        this.saveError = err.message || 'Fehler beim Speichern der Einstellungen';
      }
    });
  }

  /** Stoesst den Abruf an und laedt danach Status und Termine neu. */
  triggerPoll(): void {
    this.isTriggering = true;
    this.triggerError = '';

    this.wasteService.triggerPoll().subscribe({
      next: () => {
        this.isTriggering = false;
        this.loadStatus();
        this.loadEvents();
      },
      error: err => {
        this.isTriggering = false;
        this.triggerError = err.message || 'Abruf konnte nicht ausgeloest werden';
      }
    });
  }

  onSerialsChange(serials: string[]): void {
    if (this.settings) {
      this.settings.reminderAlexaSerials = serials;
    }
  }

  trackByEvent(_index: number, event: WasteCollectionEvent): string {
    return `${event.date}|${event.label}`;
  }
}
```

Hinweis zum Testfall „laedt nach dem manuellen Abruf Status und Termine neu": Der Abruf im
Backend laeuft asynchron ueber den `TaskScheduler`. Die Seite laedt unmittelbar danach neu —
bei einem langsamen Kalender kann der Status daher noch den vorherigen Stand zeigen. Das ist
akzeptabel: Der naechste Seitenaufruf oder ein zweiter Klick zeigt das Ergebnis.

Create `frontend/src/app/pages/waste-collection/waste-collection.component.html`:

```html
<div class="waste-page">
  <div class="container">

    <header class="waste-page__header">
      <div>
        <p class="waste-page__eyebrow">Kalender-Abo</p>
        <h1 class="waste-page__title">Müllabfuhr</h1>
        <p class="waste-page__subtitle">
          Liest die Abholtermine aus einem Google Kalender, zeigt sie auf dem Dashboard an
          und sagt sie am Vorabend über Alexa an.
        </p>
      </div>
    </header>

    <!-- Naechste Termine: die Erfolgskontrolle nach dem Eintragen der URL -->
    <section class="waste-section">
      <div class="waste-section__card">
        <h2 class="waste-section__title">Nächste Termine</h2>

        @if (events.length === 0) {
          <p class="waste-section__empty">
            Keine Termine bekannt. Trage unten die Kalender-URL ein und starte einen Abruf.
          </p>
        } @else {
          <ul class="waste-events">
            @for (event of events; track trackByEvent($index, event)) {
              <li class="waste-events__row">
                <span class="waste-events__date">{{ event.date | date:'EEEE, d. MMMM y':'':'de-DE' }}</span>
                <span class="waste-events__label">{{ event.label }}</span>
              </li>
            }
          </ul>
        }
      </div>
    </section>

    <!-- Einstellungen -->
    @if (settings) {
      <section class="waste-section">
        <div class="waste-section__card">
          <h2 class="waste-section__title">Einstellungen</h2>

          <div class="waste-form">
            <label class="waste-form__check">
              <input type="checkbox" [(ngModel)]="settings.enabled" name="enabled">
              <span>Müllabfuhr-Kalender aktiv</span>
            </label>

            <label class="waste-form__field">
              <span class="waste-form__label">Kalender-URL (iCal-Format)</span>
              <input
                type="text"
                class="waste-form__input"
                [(ngModel)]="settings.icsUrl"
                name="icsUrl"
                placeholder="https://calendar.google.com/calendar/ical/.../basic.ics">
              <small class="waste-form__hint">
                In Google Kalender unter „Einstellungen → Privatadresse im iCal-Format".
              </small>
            </label>

            <label class="waste-form__field">
              <span class="waste-form__label">Vorschau auf dem Dashboard (Tage, inkl. heute)</span>
              <input
                type="number"
                min="1"
                class="waste-form__input waste-form__input--narrow"
                [(ngModel)]="settings.lookaheadDays"
                name="lookaheadDays">
            </label>

            <label class="waste-form__check">
              <input type="checkbox" [(ngModel)]="settings.reminderEnabled" name="reminderEnabled">
              <span>Am Vorabend per Alexa ansagen</span>
            </label>

            <label class="waste-form__field">
              <span class="waste-form__label">Uhrzeit der Ansage</span>
              <input
                type="time"
                class="waste-form__input waste-form__input--narrow"
                [(ngModel)]="settings.reminderTime"
                name="reminderTime">
            </label>

            <div class="waste-form__field">
              <span class="waste-form__label">Ziel-Geräte</span>
              <app-alexa-device-picker
                [value]="settings.reminderAlexaSerials"
                (valueChange)="onSerialsChange($event)">
              </app-alexa-device-picker>
            </div>

            <div class="waste-form__actions">
              <button
                type="button"
                class="waste-form__button"
                [disabled]="isSaving"
                (click)="saveSettings()">
                {{ isSaving ? 'Speichert…' : 'Speichern' }}
              </button>
              @if (saveSuccess) {
                <span class="waste-form__success">Gespeichert.</span>
              }
              @if (saveError) {
                <span class="waste-form__error">{{ saveError }}</span>
              }
            </div>
          </div>
        </div>
      </section>
    }

    <!-- Abruf-Status -->
    @if (status) {
      <section class="waste-section">
        <div class="waste-section__card">
          <h2 class="waste-section__title">Abruf</h2>

          <div class="waste-status">
            <div class="waste-status__stat">
              <span class="waste-status__label">Zeitplan</span>
              <span class="waste-status__value">{{ status.schedule }}</span>
            </div>
            <div class="waste-status__stat">
              <span class="waste-status__label">Letzter Abruf</span>
              <span class="waste-status__value">
                {{ status.lastPollTime ? (status.lastPollTime | date:'dd.MM.yyyy HH:mm') : 'noch nie' }}
              </span>
            </div>
            <div class="waste-status__stat">
              <span class="waste-status__label">Bekannte Termine</span>
              <span class="waste-status__value">{{ status.knownEventCount }}</span>
            </div>
          </div>

          @if (status.lastError) {
            <p class="waste-status__error">Letzter Fehler: {{ status.lastError }}</p>
          }

          <div class="waste-form__actions">
            <button
              type="button"
              class="waste-form__button"
              [disabled]="isTriggering"
              (click)="triggerPoll()">
              {{ isTriggering ? 'Ruft ab…' : 'Jetzt abrufen' }}
            </button>
            @if (triggerError) {
              <span class="waste-form__error">{{ triggerError }}</span>
            }
          </div>
        </div>
      </section>
    }

  </div>
</div>
```

Create `frontend/src/app/pages/waste-collection/waste-collection.component.scss`:

```scss
.waste-page {
  padding: 2rem 0;

  &__header {
    margin-bottom: 2rem;
  }

  &__eyebrow {
    text-transform: uppercase;
    letter-spacing: 0.08em;
    font-size: 0.75rem;
    opacity: 0.6;
    margin: 0 0 0.25rem;
  }

  &__title {
    margin: 0 0 0.5rem;
  }

  &__subtitle {
    margin: 0;
    max-width: 60ch;
    opacity: 0.8;
  }
}

.waste-section {
  margin-bottom: 1.5rem;

  &__card {
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 0.75rem;
    padding: 1.25rem 1.5rem;
  }

  &__title {
    margin: 0 0 1rem;
    font-size: 1.1rem;
  }

  &__empty {
    margin: 0;
    opacity: 0.7;
  }
}

.waste-events {
  list-style: none;
  margin: 0;
  padding: 0;

  &__row {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    padding: 0.5rem 0;
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);

    &:last-child {
      border-bottom: none;
    }
  }

  &__label {
    font-weight: 600;
  }
}

.waste-form {
  display: flex;
  flex-direction: column;
  gap: 1rem;

  &__field {
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
  }

  &__check {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    cursor: pointer;
  }

  &__label {
    font-size: 0.9rem;
    opacity: 0.8;
  }

  &__hint {
    font-size: 0.8rem;
    opacity: 0.6;
  }

  &__input {
    padding: 0.5rem 0.65rem;
    border-radius: 0.5rem;
    border: 1px solid rgba(255, 255, 255, 0.15);
    background: rgba(0, 0, 0, 0.2);
    color: inherit;
    font: inherit;

    &--narrow {
      max-width: 12rem;
    }
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin-top: 0.5rem;
  }

  &__button {
    padding: 0.5rem 1.1rem;
    border-radius: 0.5rem;
    border: none;
    cursor: pointer;
    font: inherit;
    font-weight: 600;

    &:disabled {
      opacity: 0.6;
      cursor: default;
    }
  }

  &__success {
    color: #4ade80;
    font-size: 0.9rem;
  }

  &__error {
    color: #f87171;
    font-size: 0.9rem;
  }
}

.waste-status {
  display: flex;
  flex-wrap: wrap;
  gap: 1.5rem;

  &__stat {
    display: flex;
    flex-direction: column;
    gap: 0.15rem;
  }

  &__label {
    font-size: 0.8rem;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    opacity: 0.6;
  }

  &__value {
    font-weight: 600;
  }

  &__error {
    color: #f87171;
    margin: 1rem 0 0;
    font-size: 0.9rem;
  }
}
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/waste-collection.component.spec.ts'`
Expected: PASS, 5 Tests.

- [ ] **Step 5: Route registrieren**

In `frontend/src/app/app.routes.ts` vor der `'**'`-Route einfügen:

```typescript
  {
    path: 'waste-collection',
    loadComponent: () => import('./pages/waste-collection/waste-collection.component').then(m => m.WasteCollectionComponent),
    title: 'Muellabfuhr - Household Manager'
  },
```

- [ ] **Step 6: Navigationseintrag ergänzen**

In `frontend/src/app/components/header/header.component.ts` die Gruppe „Umwelt" um einen
Eintrag erweitern:

```typescript
    {
      path: '/environment',
      label: 'Umwelt',
      children: [
        { path: '/air-quality', label: 'Luftqualitaet' },
        { path: '/weather', label: 'Wetter' },
        { path: '/temperatures', label: 'Temperaturen' },
        { path: '/waste-collection', label: 'Muellabfuhr' }
      ]
    },
```

- [ ] **Step 7: Build und Gesamt-Testlauf**

Run: `cd frontend && npx ng build`
Expected: BUILD SUCCESS

Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: PASS — alle Frontend-Tests, keine Regression.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/pages/waste-collection/ frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(waste): Einstellungsseite mit Terminliste, Konfiguration und Abruf"
```

---

### Task 15: Abschluss-Verifikation

**Files:** keine Änderungen — reine Prüfung.

- [ ] **Step 1: Backend vollständig bauen und testen**

Run: `cd backend && mvn clean test`
Expected: BUILD SUCCESS. Falls DB-abhängige Bestandstests fehlschlagen: Das ist in dieser
Umgebung bekannt und erwartet — entscheidend ist, dass **kein `Waste*`-Test** fehlschlägt.

- [ ] **Step 2: Frontend bauen und testen**

Run: `cd frontend && npx ng build && npm test -- --watch=false --browsers=ChromeHeadless`
Expected: BUILD SUCCESS, alle Tests grün.

- [ ] **Step 3: Manueller Rauchtest**

Voraussetzung: MariaDB läuft, `JAVA_HOME` zeigt auf JDK 21.

1. Run: `cd backend && mvn spring-boot:run`
   Erwartung: Liquibase legt `waste_collection_events` an und seedet die
   `WASTE_COLLECTION`-Settings; kein Fehler im Log. Der Abruf überspringt sich mit
   „keine Kalender-URL hinterlegt".
2. Run: `cd frontend && npm start`
3. Im Browser `http://localhost:4200/waste-collection` öffnen.
4. Die geheime iCal-URL aus Google Kalender eintragen, „Müllabfuhr-Kalender aktiv" anhaken,
   Ziel-Geräte auswählen, speichern.
5. „Jetzt abrufen" klicken, Seite neu laden.
   Erwartung: Die Terminliste füllt sich, „Bekannte Termine" ist > 0, kein „Letzter Fehler".
6. Dashboard öffnen (`http://localhost:4200/`).
   Erwartung: Steht in den nächsten 3 Tagen etwas an, erscheint die Müllabfuhr-Kachel;
   ein Termin morgen ist hervorgehoben. Steht nichts an, ist keine Kachel zu sehen.

- [ ] **Step 4: Fehlerfall prüfen**

Auf der Einstellungsseite die URL auf `https://example.com/gibtsnicht.ics` ändern, speichern,
„Jetzt abrufen" klicken, neu laden.
Erwartung: „Letzter Fehler" zeigt eine Meldung, **die Terminliste bleibt aber gefüllt** — der
Ausfall der Quelle leert die Daten nicht. Danach die korrekte URL wiederherstellen.

- [ ] **Step 5: Abschluss-Commit (falls Nacharbeiten nötig waren)**

```bash
git status
# Nur committen, wenn die Verifikation Korrekturen erforderte.
```

---

## Offene Punkte für die Umsetzung

- **biweekly-API:** Der Parser in Task 3 nutzt `VEvent.getDateIterator(TimeZone)` und
  `biweekly.util.com.google.ical.compat.javautil.DateIterator`. Weicht die API von 0.6.8 ab,
  ist die Signatur gegen die Javadocs zu prüfen — die Tests aus Task 3 sind der Schiedsrichter,
  sie dürfen nicht aufgeweicht werden.
- **Transaktion des Resyncs — erledigt, nicht mehr offen.** Ein früherer Entwurf hatte
  `@Transactional` auf einer `protected` Methode des Polling-Service. Das wäre wirkungslos
  gewesen (Spring-AOP greift bei Selbstaufrufen nicht) und hätte den abgeleiteten Delete zur
  Laufzeit scheitern lassen — er benötigt zwingend eine aktive Transaktion. Task 7 zieht den
  Resync deshalb in die eigene Bean `WasteCollectionResyncService`. Diese Aufteilung bitte
  nicht wieder zusammenführen.
