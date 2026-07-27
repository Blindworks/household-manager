# Kalender: Personen-Zuordnung und konfigurierbare Kategorien — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kalendertermine lassen sich einer oder mehreren Personen (App-Nutzern) zuordnen, und die Kategorienliste wird zu gepflegten Stammdaten mit eigener Admin-Seite.

**Architecture:** Die Kategorie wandert vom Java-Enum in die Tabelle `calendar_category` mit einem stabilen, unveränderlichen Schlüssel, auf den Flows filtern; `calendar_events.category` wird zu `category_id` mit Fremdschlüssel. Personen liegen in der Zuordnungstabelle `calendar_event_person` (keine Zeile = Haushaltstermin). Beziehungen werden wie überall im Projekt über nackte Id-Spalten geführt, nicht über JPA-Relationen.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Liquibase / MariaDB; Angular 19 standalone / SCSS; JUnit 5 + Mockito + AssertJ; Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-07-27-kalender-personen-und-kategorien-design.md`

---

## Vorbedingungen für jede Task

**Backend-Kommandos** (aus `backend/`):

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Ohne diese Zeile scheitert Maven, weil der Rechner standardmäßig auf JDK 17 zeigt. Es gibt keinen `mvnw`-Wrapper.

Die Tests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern auf diesem Rechner mit „Access denied for user 'root'@'localhost'" — die Test-Datenbank ist lokal nicht erreichbar. **Diese beiden Fehlschläge sind vorbestehend und zählen nicht als Regression.**

**Frontend-Kommandos** (aus `frontend/`):

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartete Baseline: **3 vorbestehende Fehlschläge** (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`). Gelegentlich kommt ein vierter dazu: `SmartDeviceListComponent` wirft in `afterAll` „Cannot read properties of undefined (reading 'subscribe')" — das ist ein bekannter Karma-Flake, bei Verdacht einfach erneut laufen lassen. Nur *zusätzliche* Fehlschläge sind echte Regressionen.

**Wichtig zur Reihenfolge:** `spring.jpa.hibernate.ddl-auto=validate` prüft beim Start, ob die Entities exakt zum Schema passen. Migration und Entity-Änderung müssen deshalb im selben Commit liegen — ein Commit dazwischen ließe die Anwendung nicht mehr starten.

---

## Datei-Übersicht

**Neu (Backend)**

| Datei | Verantwortung |
|---|---|
| `backend/src/main/resources/db/changelog/changes/20260727-0044-calendar-categories.xml` | Tabelle `calendar_category`, Seed, Umbau `calendar_events` |
| `backend/src/main/resources/db/changelog/changes/20260727-0045-calendar-event-persons.xml` | Zuordnungstabelle `calendar_event_person` |
| `backend/src/main/java/com/household/manager/model/entity/CalendarCategory.java` | Entity (ersetzt das gleichnamige Enum) |
| `backend/src/main/java/com/household/manager/model/entity/CalendarEventPerson.java` | Entity der Zuordnung |
| `backend/src/main/java/com/household/manager/repository/CalendarCategoryRepository.java` | Repository |
| `backend/src/main/java/com/household/manager/repository/CalendarEventPersonRepository.java` | Repository |
| `backend/src/main/java/com/household/manager/dto/CalendarCategoryView.java` | Eingebettete Kategorie in Termin-Antworten |
| `backend/src/main/java/com/household/manager/dto/CalendarPersonView.java` | Eingebettete Person in Termin-Antworten |
| `backend/src/main/java/com/household/manager/dto/CalendarCategoryRequest.java` | Anlege-/Änderungsdaten einer Kategorie |
| `backend/src/main/java/com/household/manager/dto/CalendarCategoryResponse.java` | Kategorie inkl. Verwaltungsfelder |
| `backend/src/main/java/com/household/manager/calendar/CalendarCategoryKeyGenerator.java` | Schlüsselerzeugung, isoliert testbar |
| `backend/src/main/java/com/household/manager/calendar/CalendarCategoryService.java` | CRUD, Löschschutz, Audit |
| `backend/src/main/java/com/household/manager/calendar/CalendarCategoryController.java` | `/v1/calendar/categories` |
| `backend/src/main/java/com/household/manager/security/HouseholdUserController.java` | `GET /v1/users` |

**Neu (Frontend)**

| Datei | Verantwortung |
|---|---|
| `frontend/src/app/models/calendar-category.model.ts` | Kategorie-Typen |
| `frontend/src/app/services/calendar-category.service.ts` | Kategorie-API |
| `frontend/src/app/services/household-user.service.ts` | `GET /v1/users` |
| `frontend/src/app/pages/admin-calendar-categories/*` | Admin-Seite |

**Geändert:** `CalendarEvent`, `CalendarEventRepository`, die drei Kalender-DTOs, `CalendarEventService`, `CalendarReminderScheduler`, `SecurityConfig`, `AppUserPrincipal`, `CurrentUserResponse`, `db.changelog-master.xml`, die vier Kalender-Tests, `SecurityRulesTest`, `CurrentUserResponseTest`; im Frontend `calendar-event.model.ts`, `auth.model.ts`, `calendar.component.*`, `app.routes.ts`, `header.component.ts`, `CLAUDE.md`.

**Gelöscht:** das Enum `CalendarCategory` und die Konstante `CATEGORY_META`.

---

### Task 1: Kategorie wird eine Tabelle

Der größte Schritt: Datenbank, Entity und alle Nutzer des Enums wechseln gemeinsam. Ein Commit, weil `ddl-auto=validate` jeden Zwischenstand unstartbar machen würde.

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260727-0044-calendar-categories.xml`
- Create: `backend/src/main/java/com/household/manager/model/entity/CalendarCategory.java` (ersetzt das Enum)
- Create: `backend/src/main/java/com/household/manager/repository/CalendarCategoryRepository.java`
- Create: `backend/src/main/java/com/household/manager/dto/CalendarCategoryView.java`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `backend/src/main/java/com/household/manager/model/entity/CalendarEvent.java`
- Modify: `backend/src/main/java/com/household/manager/repository/CalendarEventRepository.java`
- Modify: `backend/src/main/java/com/household/manager/dto/CalendarEventRequest.java`
- Modify: `backend/src/main/java/com/household/manager/dto/CalendarEventResponse.java`
- Modify: `backend/src/main/java/com/household/manager/dto/CalendarOccurrenceResponse.java`
- Modify: `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`
- Modify: `backend/src/main/java/com/household/manager/calendar/CalendarReminderScheduler.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarEventServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarEventControllerTest.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarReminderSchedulerTest.java`

---

- [ ] **Step 1: Migration schreiben**

Neue Datei `backend/src/main/resources/db/changelog/changes/20260727-0044-calendar-categories.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260727-0044-a" author="household-manager">
        <comment>Kalender-Kategorien werden Stammdaten; cat_key ist der stabile Schluessel, auf den Flows filtern.</comment>

        <createTable tableName="calendar_category">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="cat_key" type="VARCHAR(50)">
                <constraints nullable="false" unique="true" uniqueConstraintName="uk_calendar_category_key"/>
            </column>
            <column name="name" type="VARCHAR(100)">
                <constraints nullable="false"/>
            </column>
            <column name="color" type="VARCHAR(7)">
                <constraints nullable="false"/>
            </column>
            <column name="icon" type="VARCHAR(50)"/>
            <column name="sort_order" type="INT">
                <constraints nullable="false"/>
            </column>
            <column name="active" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
            <column name="created_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
            <column name="updated_at" type="TIMESTAMP" defaultValueComputed="CURRENT_TIMESTAMP">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <!-- Die Schluessel sind exakt die Strings, die der Erinnerungs-Scheduler bisher
             als Event-State geschrieben hat: bestehende Flows bleiben lauffaehig. -->
        <insert tableName="calendar_category">
            <column name="cat_key" value="general"/><column name="name" value="Allgemein"/>
            <column name="color" value="#64b5f6"/><column name="sort_order" valueNumeric="1"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="calendar_category">
            <column name="cat_key" value="family"/><column name="name" value="Familie"/>
            <column name="color" value="#ba68c8"/><column name="sort_order" valueNumeric="2"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="calendar_category">
            <column name="cat_key" value="health"/><column name="name" value="Gesundheit"/>
            <column name="color" value="#e57373"/><column name="sort_order" valueNumeric="3"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="calendar_category">
            <column name="cat_key" value="household"/><column name="name" value="Haushalt"/>
            <column name="color" value="#81c784"/><column name="sort_order" valueNumeric="4"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="calendar_category">
            <column name="cat_key" value="work"/><column name="name" value="Arbeit"/>
            <column name="color" value="#ffb74d"/><column name="sort_order" valueNumeric="5"/>
            <column name="active" valueBoolean="true"/>
        </insert>
        <insert tableName="calendar_category">
            <column name="cat_key" value="birthday"/><column name="name" value="Geburtstag"/>
            <column name="color" value="#f06292"/><column name="sort_order" valueNumeric="6"/>
            <column name="active" valueBoolean="true"/>
        </insert>

        <rollback>
            <dropTable tableName="calendar_category"/>
        </rollback>
    </changeSet>

    <changeSet id="20260727-0044-b" author="household-manager">
        <comment>calendar_events.category (Enum-Text) wird zu category_id mit Fremdschluessel.</comment>

        <addColumn tableName="calendar_events">
            <column name="category_id" type="BIGINT"/>
        </addColumn>

        <sql>
            UPDATE calendar_events e
            SET e.category_id = (SELECT c.id FROM calendar_category c WHERE c.cat_key = LOWER(e.category))
        </sql>

        <!-- Bleibt hier eine Zeile ohne Treffer, schlaegt der naechste Schritt fehl und
             Liquibase bricht den Start ab. Gewollt: lauter Abbruch statt stiller
             Reparatur auf "Allgemein". -->
        <addNotNullConstraint tableName="calendar_events" columnName="category_id" columnDataType="BIGINT"/>

        <addForeignKeyConstraint
                baseTableName="calendar_events" baseColumnNames="category_id"
                referencedTableName="calendar_category" referencedColumnNames="id"
                constraintName="fk_calendar_events_category" onDelete="RESTRICT"/>

        <dropColumn tableName="calendar_events" columnName="category"/>

        <rollback>
            <addColumn tableName="calendar_events">
                <column name="category" type="VARCHAR(30)"/>
            </addColumn>
            <sql>
                UPDATE calendar_events e
                SET e.category = (SELECT UPPER(c.cat_key) FROM calendar_category c WHERE c.id = e.category_id)
            </sql>
            <dropForeignKeyConstraint baseTableName="calendar_events" constraintName="fk_calendar_events_category"/>
            <dropColumn tableName="calendar_events" columnName="category_id"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Migration im Master eintragen**

In `backend/src/main/resources/db/changelog/db.changelog-master.xml` direkt nach der Zeile
`<include file="db/changelog/changes/20260726-0043-add-must-change-password.xml"/>` einfügen:

```xml

    <!-- Kalender: konfigurierbare Kategorien -->
    <include file="db/changelog/changes/20260727-0044-calendar-categories.xml"/>
```

- [ ] **Step 3: Enum durch Entity ersetzen**

`backend/src/main/java/com/household/manager/model/entity/CalendarCategory.java` **vollständig** durch diesen Inhalt ersetzen:

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Eine gepflegte Kalender-Kategorie. {@link #key} ist der stabile Schluessel, auf den
 * Flows ueber den State von {@code event.calendar_reminder} filtern — er wird beim
 * Anlegen erzeugt und danach nie geaendert, damit ein Umbenennen keinen Flow bricht.
 */
@Entity
@Table(name = "calendar_category")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cat_key", nullable = false, unique = true, length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    /** Hex-Farbe fuer Chips und Dialog, z.B. "#64b5f6". */
    @Column(nullable = false, length = 7)
    private String color;

    /** Material-Symbol-Name; null = kein Icon. */
    @Column(length = 50)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** false = nicht mehr waehlbar; Bestandstermine behalten die Kategorie. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

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

- [ ] **Step 4: Repository anlegen**

Neue Datei `backend/src/main/java/com/household/manager/repository/CalendarCategoryRepository.java`
(das Paket ist Pflicht — `JpaConfig` beschränkt das Repository-Scanning darauf):

```java
package com.household.manager.repository;

import com.household.manager.model.entity.CalendarCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalendarCategoryRepository extends JpaRepository<CalendarCategory, Long> {

    /** Anzeigereihenfolge des Admin-Bereichs und der Auswahlliste. */
    List<CalendarCategory> findAllByOrderBySortOrderAscNameAsc();

    boolean existsByKey(String key);
}
```

- [ ] **Step 5: Terminzeile auf die Id umstellen**

In `backend/src/main/java/com/household/manager/model/entity/CalendarEvent.java` diesen Block:

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CalendarCategory category;
```

ersetzen durch:

```java
    /** Fremdschluessel auf calendar_category; bewusst als nackte Id (Projektstil). */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;
```

Die dadurch unbenutzten Importe `jakarta.persistence.EnumType` und `jakarta.persistence.Enumerated` entfernen.

- [ ] **Step 6: Zählabfrage für den späteren Löschschutz ergänzen**

In `backend/src/main/java/com/household/manager/repository/CalendarEventRepository.java` innerhalb des Interfaces ergänzen:

```java
    /** Grundlage des Loeschschutzes: eine genutzte Kategorie darf nicht verschwinden. */
    long countByCategoryId(Long categoryId);
```

- [ ] **Step 7: Eingebettete Kategorie-Ansicht anlegen**

Neue Datei `backend/src/main/java/com/household/manager/dto/CalendarCategoryView.java`:

```java
package com.household.manager.dto;

import com.household.manager.model.entity.CalendarCategory;

/**
 * Die Kategorie, wie sie in Termin-Antworten eingebettet mitgeliefert wird — damit das
 * Monatsraster ohne Nachschlagen rendert und ein Termin mit inzwischen deaktivierter
 * Kategorie weiterhin in seiner Farbe erscheint.
 */
public record CalendarCategoryView(Long id, String key, String name, String color, String icon) {

    public static CalendarCategoryView of(CalendarCategory category) {
        return new CalendarCategoryView(category.getId(), category.getKey(),
                category.getName(), category.getColor(), category.getIcon());
    }
}
```

- [ ] **Step 8: DTOs umstellen**

In `CalendarEventRequest.java` das Feld

```java
    private CalendarCategory category;
```

ersetzen durch

```java
    private Long categoryId;
```

und den Import `com.household.manager.model.entity.CalendarCategory` entfernen.

In `CalendarEventResponse.java` **und** `CalendarOccurrenceResponse.java` jeweils

```java
    private CalendarCategory category;
```

ersetzen durch

```java
    private CalendarCategoryView category;
```

und den Import `com.household.manager.model.entity.CalendarCategory` entfernen (`CalendarCategoryView` liegt im selben Paket, braucht also keinen Import).

- [ ] **Step 9: Service umstellen**

In `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`:

Import ergänzen:

```java
import com.household.manager.dto.CalendarCategoryView;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.repository.CalendarCategoryRepository;
import java.util.function.Function;
```

Feld und Konstruktor erweitern:

```java
    private final CalendarEventRepository repository;
    private final CalendarCategoryRepository categoryRepository;
    private final RecurrenceExpansionService expansionService;
    private final Clock clock;
    private final AuditService auditService;

    public CalendarEventService(CalendarEventRepository repository,
                                CalendarCategoryRepository categoryRepository,
                                RecurrenceExpansionService expansionService,
                                Clock clock,
                                AuditService auditService) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.expansionService = expansionService;
        this.clock = clock;
        this.auditService = auditService;
    }
```

In `getOccurrences` direkt nach `List<CalendarEvent> all = repository.findAll();` einfügen:

```java
        // Einmal alle Kategorien laden statt pro Termin nachzuschlagen: der Fensterabruf
        // kostet dadurch genau eine zusaetzliche Abfrage, unabhaengig von der Terminanzahl.
        Map<Long, CalendarCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(CalendarCategory::getId, Function.identity()));
```

Alle drei Aufrufe `toOccurrence(event, ..., today)` um den Parameter `categories` erweitern und die Methode ersetzen:

```java
    private CalendarOccurrenceResponse toOccurrence(CalendarEvent event, LocalDate date,
                                                    LocalDate today,
                                                    Map<Long, CalendarCategory> categories) {
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
                .category(categoryView(event.getCategoryId(), categories))
                .allDay(event.isAllDay())
                .startTime(event.getStartTime())
                .endTime(event.getEndTime())
                .endDate(event.getEndDate() != null ? date.plusDays(durationDays) : null)
                .recurring(event.isRecurring() || override)
                .daysUntil(ChronoUnit.DAYS.between(today, date))
                .build();
    }

    /**
     * Die Kategorie eines Termins als eingebettete Ansicht. Fehlt sie in der Map (der
     * Fremdschluessel schliesst das aus, ein Testdouble aber nicht), liefert die Methode
     * null statt zu werfen — eine fehlende Farbe darf nie den ganzen Monat leeren.
     */
    private CalendarCategoryView categoryView(Long categoryId,
                                              Map<Long, CalendarCategory> categories) {
        CalendarCategory category = categories.get(categoryId);
        return category != null ? CalendarCategoryView.of(category) : null;
    }
```

In `updateOccurrence` ersetzt der letzte Aufruf `toOccurrence(repository.save(override), occurrenceDate, today())` sich durch:

```java
        Map<Long, CalendarCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(CalendarCategory::getId, Function.identity()));
        CalendarOccurrenceResponse response =
                toOccurrence(repository.save(override), occurrenceDate, today(), categories);
```

In `applyRequest` die Zeile `event.setCategory(request.getCategory());` ersetzen durch:

```java
        event.setCategoryId(request.getCategoryId());
```

In `validate` den Kategorie-Block ersetzen:

```java
        if (request.getCategoryId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Die Kategorie fehlt.");
        }
        if (!categoryRepository.existsById(request.getCategoryId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Kategorie %d existiert nicht.".formatted(request.getCategoryId()));
        }
```

In `toResponse` die Zeile `.category(event.getCategory())` ersetzen durch:

```java
                .category(categoryRepository.findById(event.getCategoryId())
                        .map(CalendarCategoryView::of).orElse(null))
```

- [ ] **Step 10: Scheduler auf den Schlüssel umstellen**

In `backend/src/main/java/com/household/manager/calendar/CalendarReminderScheduler.java` die Zeile

```java
                .state(occ.getCategory().name().toLowerCase(Locale.ROOT))
```

ersetzen durch:

```java
                // Der Kategorie-Schluessel ist der Vertrag zur Flow-Engine: er bleibt
                // stabil, auch wenn die Kategorie umbenannt wird.
                .state(occ.getCategory() != null ? occ.getCategory().key() : "general")
```

Den nun unbenutzten Import `java.util.Locale` entfernen.

- [ ] **Step 11: Bestehende Tests anpassen**

`CalendarEventServiceTest.java`: Import `com.household.manager.repository.CalendarCategoryRepository` ergänzen, das Mock hinzufügen und den Aufbau erweitern:

```java
    @Mock
    private CalendarEventRepository repository;
    @Mock
    private CalendarCategoryRepository categoryRepository;
    @Mock
    private AuditService auditService;

    private static final CalendarCategory HEALTH = CalendarCategory.builder()
            .id(3L).key("health").name("Gesundheit").color("#e57373").sortOrder(3).active(true).build();

    private CalendarEventService service;

    @BeforeEach
    void setUp() {
        service = new CalendarEventService(repository, categoryRepository,
                new RecurrenceExpansionService(), CLOCK, auditService);
        lenient().when(categoryRepository.existsById(3L)).thenReturn(true);
        lenient().when(categoryRepository.findAll()).thenReturn(List.of(HEALTH));
        lenient().when(categoryRepository.findById(3L)).thenReturn(Optional.of(HEALTH));
    }
```

`lenient()` statisch importieren (`import static org.mockito.Mockito.lenient;`). In `validRequest()` `.category(CalendarCategory.HEALTH)` durch `.categoryId(3L)` ersetzen. Überall, wo Testdaten mit `CalendarEvent.builder()...category(...)` gebaut werden, `categoryId(3L)` setzen. Assertions auf `getCategory()` prüfen jetzt `getCategory().key()`.

`CalendarEventControllerTest.java`: analog jedes `category`-Feld im JSON-Body auf `"categoryId": 3` umstellen und Erwartungen auf `$.category.key` ziehen.

`CalendarReminderSchedulerTest.java`: Import des Enums entfernen und in der Hilfsmethode `occurrence(...)` die Kategorie setzen als

```java
                .category(new CalendarCategoryView(3L, "health", "Gesundheit", "#e57373", null))
```

(Import `com.household.manager.dto.CalendarCategoryView`.) Die Assertion auf den State prüft weiterhin `"health"`.

- [ ] **Step 12: Bauen und testen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test
```

Erwartet: alle Kalender- und Security-Tests grün; nur `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern (vorbestehend, Datenbank lokal nicht erreichbar).

- [ ] **Step 13: Commit**

```bash
git add backend/src/main backend/src/test && git commit -m "feat(kalender): Kategorien werden Stammdaten mit stabilem Schluessel"
```

---

### Task 2: Schlüsselerzeugung

Isolierte Klasse, damit die Regeln ohne Datenbank testbar sind.

**Files:**
- Create: `backend/src/main/java/com/household/manager/calendar/CalendarCategoryKeyGenerator.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarCategoryKeyGeneratorTest.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

Neue Datei `backend/src/test/java/com/household/manager/calendar/CalendarCategoryKeyGeneratorTest.java`:

```java
package com.household.manager.calendar;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarCategoryKeyGeneratorTest {

    private final CalendarCategoryKeyGenerator generator = new CalendarCategoryKeyGenerator();

    @Test
    void kleinschreibtEinfacheNamen() {
        assertThat(generator.generate("Arbeit", Set.of())).isEqualTo("arbeit");
    }

    @Test
    void transliteriertUmlauteUndScharfesS() {
        assertThat(generator.generate("Bürogebäude Straße", Set.of()))
                .isEqualTo("buerogebaeude_strasse");
    }

    @Test
    void fasstSonderzeichenZuEinemTrennerZusammen() {
        assertThat(generator.generate("Sport & Freizeit!!", Set.of())).isEqualTo("sport_freizeit");
    }

    @Test
    void kuerztAufFuenfzigZeichen() {
        String lang = "a".repeat(80);
        assertThat(generator.generate(lang, Set.of())).hasSize(50);
    }

    @Test
    void faelltAufKategorieZurueckWennNichtsUebrigBleibt() {
        assertThat(generator.generate("🎉🎉", Set.of())).isEqualTo("kategorie");
    }

    @Test
    void haengtBeiKollisionEineZahlAn() {
        assertThat(generator.generate("Arbeit", Set.of("arbeit"))).isEqualTo("arbeit_2");
        assertThat(generator.generate("Arbeit", Set.of("arbeit", "arbeit_2"))).isEqualTo("arbeit_3");
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarCategoryKeyGeneratorTest
```

Erwartet: Kompilierfehler „cannot find symbol: class CalendarCategoryKeyGenerator".

- [ ] **Step 3: Generator implementieren**

Neue Datei `backend/src/main/java/com/household/manager/calendar/CalendarCategoryKeyGenerator.java`:

```java
package com.household.manager.calendar;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Erzeugt den stabilen Schluessel einer Kalender-Kategorie aus ihrem Namen.
 *
 * <p>Der Schluessel wird ausschliesslich beim Anlegen vergeben und danach nie wieder
 * berechnet: Flows filtern ueber den State von {@code event.calendar_reminder} darauf,
 * ein Umbenennen darf sie nicht ins Leere laufen lassen.
 */
@Component
public class CalendarCategoryKeyGenerator {

    private static final int MAX_LENGTH = 50;
    private static final String FALLBACK = "kategorie";

    /**
     * @param name  der Anzeigename
     * @param taken bereits vergebene Schluessel; bei Kollision wird "_2", "_3", ... angehaengt
     */
    public String generate(String name, Set<String> taken) {
        String base = normalize(name);
        if (!taken.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = truncate(base, MAX_LENGTH - ("_" + suffix).length()) + "_" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    private String normalize(String name) {
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.isEmpty() ? FALLBACK : truncate(slug, MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
```

- [ ] **Step 4: Test laufen lassen und grün bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarCategoryKeyGeneratorTest
```

Erwartet: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/calendar/CalendarCategoryKeyGenerator.java backend/src/test/java/com/household/manager/calendar/CalendarCategoryKeyGeneratorTest.java && git commit -m "feat(kalender): stabile Schluesselerzeugung fuer Kategorien"
```

---

### Task 3: Kategorie-API mit Löschschutz

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/CalendarCategoryRequest.java`
- Create: `backend/src/main/java/com/household/manager/dto/CalendarCategoryResponse.java`
- Create: `backend/src/main/java/com/household/manager/calendar/CalendarCategoryService.java`
- Create: `backend/src/main/java/com/household/manager/calendar/CalendarCategoryController.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarCategoryServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Fehlschlagenden Service-Test schreiben**

Neue Datei `backend/src/test/java/com/household/manager/calendar/CalendarCategoryServiceTest.java`:

```java
package com.household.manager.calendar;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CalendarCategoryRequest;
import com.household.manager.dto.CalendarCategoryResponse;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.repository.CalendarCategoryRepository;
import com.household.manager.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarCategoryServiceTest {

    @Mock
    private CalendarCategoryRepository repository;
    @Mock
    private CalendarEventRepository eventRepository;
    @Mock
    private AuditService auditService;

    private CalendarCategoryService service;

    @BeforeEach
    void setUp() {
        service = new CalendarCategoryService(repository, eventRepository,
                new CalendarCategoryKeyGenerator(), auditService);
    }

    private CalendarCategory existing() {
        return CalendarCategory.builder()
                .id(7L).key("arbeit").name("Arbeit").color("#ffb74d")
                .sortOrder(5).active(true).build();
    }

    @Test
    void erzeugtDenSchluesselAusDemNamen() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.create(new CalendarCategoryRequest(
                "Sport & Freizeit", "#4caf50", "pets", 9, true));

        assertThat(response.key()).isEqualTo("sport_freizeit");
    }

    @Test
    void haengtBeiKollisionEineZahlAn() {
        when(repository.findAll()).thenReturn(List.of(existing()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.create(new CalendarCategoryRequest(
                "Arbeit", "#ffb74d", null, 6, true));

        assertThat(response.key()).isEqualTo("arbeit_2");
    }

    @Test
    void laesstDenSchluesselBeimUmbenennenUnveraendert() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.update(7L, new CalendarCategoryRequest(
                "Büro", "#ffb74d", "work", 5, true));

        assertThat(response.key()).isEqualTo("arbeit");
        assertThat(response.name()).isEqualTo("Büro");
    }

    @Test
    void verweigertDasLoeschenEinerGenutztenKategorie() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(eventRepository.countByCategoryId(7L)).thenReturn(4L);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("4")
                .satisfies(thrown -> assertThat(((ResponseStatusException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).delete(any());
    }

    @Test
    void loeschtEineUngenutzteKategorie() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(eventRepository.countByCategoryId(7L)).thenReturn(0L);

        service.delete(7L);

        verify(repository).delete(any());
    }

    @Test
    void deaktivierenLaesstDenSchluesselUndDieTermineUnberuehrt() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.update(7L, new CalendarCategoryRequest(
                "Arbeit", "#ffb74d", null, 5, false));

        assertThat(response.active()).isFalse();
        assertThat(response.key()).isEqualTo("arbeit");
        verify(eventRepository, never()).countByCategoryId(any());
    }

    @Test
    void weistEineUngueltigeFarbeAb() {
        assertThatThrownBy(() -> service.create(new CalendarCategoryRequest(
                "Arbeit", "rot", null, 1, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Farbe");
    }

    @Test
    void schreibtEinenAuditEintragBeimAnlegen() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.create(new CalendarCategoryRequest("Arbeit", "#ffb74d", null, 1, true));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(action.capture(), any());
        assertThat(action.getValue()).isEqualTo("calendar-category.create");
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarCategoryServiceTest
```

Erwartet: Kompilierfehler „cannot find symbol: class CalendarCategoryService".

- [ ] **Step 3: DTOs anlegen**

Neue Datei `backend/src/main/java/com/household/manager/dto/CalendarCategoryRequest.java`:

```java
package com.household.manager.dto;

/**
 * Anlege- und Aenderungsdaten einer Kalender-Kategorie. Der Schluessel fehlt bewusst:
 * er wird beim Anlegen erzeugt und ist danach unveraenderlich.
 */
public record CalendarCategoryRequest(String name, String color, String icon,
                                      int sortOrder, boolean active) {
}
```

Neue Datei `backend/src/main/java/com/household/manager/dto/CalendarCategoryResponse.java`:

```java
package com.household.manager.dto;

import com.household.manager.model.entity.CalendarCategory;

/** Eine Kategorie inklusive Verwaltungsfeldern, wie die Admin-Seite sie zeigt. */
public record CalendarCategoryResponse(Long id, String key, String name, String color,
                                       String icon, int sortOrder, boolean active) {

    public static CalendarCategoryResponse of(CalendarCategory category) {
        return new CalendarCategoryResponse(category.getId(), category.getKey(),
                category.getName(), category.getColor(), category.getIcon(),
                category.getSortOrder(), category.isActive());
    }
}
```

- [ ] **Step 4: Service implementieren**

Neue Datei `backend/src/main/java/com/household/manager/calendar/CalendarCategoryService.java`:

```java
package com.household.manager.calendar;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CalendarCategoryRequest;
import com.household.manager.dto.CalendarCategoryResponse;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.repository.CalendarCategoryRepository;
import com.household.manager.repository.CalendarEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Verwaltung der Kalender-Kategorien. Der Schluessel entsteht einmal beim Anlegen und
 * bleibt danach unangetastet — er ist der Vertrag zur Flow-Engine.
 */
@Service
@RequiredArgsConstructor
public class CalendarCategoryService {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final CalendarCategoryRepository repository;
    private final CalendarEventRepository eventRepository;
    private final CalendarCategoryKeyGenerator keyGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CalendarCategoryResponse> list() {
        return repository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(CalendarCategoryResponse::of)
                .toList();
    }

    @Transactional
    public CalendarCategoryResponse create(CalendarCategoryRequest request) {
        validate(request);
        Set<String> taken = repository.findAll().stream()
                .map(CalendarCategory::getKey)
                .collect(Collectors.toSet());
        CalendarCategory category = CalendarCategory.builder()
                .key(keyGenerator.generate(request.name(), taken))
                .name(request.name().trim())
                .color(request.color())
                .icon(blankToNull(request.icon()))
                .sortOrder(request.sortOrder())
                .active(request.active())
                .build();
        CalendarCategoryResponse response = CalendarCategoryResponse.of(repository.save(category));
        auditService.record("calendar-category.create", response.name());
        return response;
    }

    @Transactional
    public CalendarCategoryResponse update(Long id, CalendarCategoryRequest request) {
        validate(request);
        CalendarCategory category = findOrThrow(id);
        // Der Schluessel wird bewusst nicht neu berechnet.
        category.setName(request.name().trim());
        category.setColor(request.color());
        category.setIcon(blankToNull(request.icon()));
        category.setSortOrder(request.sortOrder());
        category.setActive(request.active());
        CalendarCategoryResponse response = CalendarCategoryResponse.of(repository.save(category));
        auditService.record("calendar-category.update", response.name());
        return response;
    }

    /**
     * Loescht nur, solange kein Termin die Kategorie nutzt. Der Fremdschluessel wuerde das
     * ohnehin verhindern — die Pruefung hier liefert die verstaendliche Meldung samt Anzahl,
     * damit die Admin-Seite das Deaktivieren als Ausweg anbieten kann.
     */
    @Transactional
    public void delete(Long id) {
        CalendarCategory category = findOrThrow(id);
        long inUse = eventRepository.countByCategoryId(id);
        if (inUse > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Die Kategorie wird von %d Termin(en) genutzt und kann nicht geloescht werden."
                            .formatted(inUse));
        }
        repository.delete(category);
        auditService.record("calendar-category.delete", category.getName());
    }

    private CalendarCategory findOrThrow(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Die Kategorie %d existiert nicht.".formatted(id)));
    }

    private void validate(CalendarCategoryRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Name darf nicht leer sein.");
        }
        if (request.name().trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Name darf hoechstens 100 Zeichen lang sein.");
        }
        if (request.color() == null || !HEX_COLOR.matcher(request.color()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Farbe muss ein Hex-Wert wie #4caf50 sein.");
        }
        if (request.icon() != null && request.icon().length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Der Iconname darf hoechstens 50 Zeichen lang sein.");
        }
    }

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }
}
```

- [ ] **Step 5: Test laufen lassen und grün bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarCategoryServiceTest
```

Erwartet: `Tests run: 8, Failures: 0, Errors: 0`.

- [ ] **Step 6: Controller anlegen**

Neue Datei `backend/src/main/java/com/household/manager/calendar/CalendarCategoryController.java`:

```java
package com.household.manager.calendar;

import com.household.manager.dto.CalendarCategoryRequest;
import com.household.manager.dto.CalendarCategoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Kalender-Kategorien. Lesen darf jeder angemeldete Nutzer (auch das KIOSK-Wandtablet
 * braucht Namen und Farben zum Rendern), schreiben nur ADMIN — siehe SecurityConfig.
 */
@RestController
@RequestMapping("/v1/calendar/categories")
@RequiredArgsConstructor
public class CalendarCategoryController {

    private final CalendarCategoryService service;

    @GetMapping
    public ResponseEntity<List<CalendarCategoryResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<CalendarCategoryResponse> create(
            @RequestBody CalendarCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CalendarCategoryResponse> update(@PathVariable Long id,
            @RequestBody CalendarCategoryRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 7: Schreibrechte in SecurityConfig ergänzen**

In `backend/src/main/java/com/household/manager/security/SecurityConfig.java` unmittelbar **nach** dem ADMIN-Block (der mit `"/v1/tractive/home-settings").hasRole("ADMIN")` endet) einfügen:

```java
                        // Kategorien: lesen darf jeder Angemeldete ueber die generische
                        // GET-Regel weiter unten, aendern nur ADMIN. Die Regeln muessen
                        // methodenspezifisch sein — ein methodenloser Matcher wuerde das
                        // Lesen fuer das Wandtablet mitsperren.
                        .requestMatchers(HttpMethod.POST, "/v1/calendar/categories").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/v1/calendar/categories/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/calendar/categories/*").hasRole("ADMIN")
```

- [ ] **Step 8: Security-Test ergänzen**

In `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` im Stil der vorhandenen Tests ergänzen (Annotationen und Hilfsmethoden der Datei übernehmen):

```java
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKategorienLesen() throws Exception {
        mockMvc.perform(get("/v1/calendar/categories")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKategorienNichtAendern() throws Exception {
        mockMvc.perform(post("/v1/calendar/categories")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"color\":\"#4caf50\",\"sortOrder\":1,\"active\":true}"))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 9: Tests laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest='CalendarCategory*Test,SecurityRulesTest'
```

Erwartet: alle grün.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main backend/src/test && git commit -m "feat(kalender): Kategorie-API mit Loeschschutz und Admin-Rechten"
```

---

### Task 4: Personen-Zuordnung

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260727-0045-calendar-event-persons.xml`
- Create: `backend/src/main/java/com/household/manager/model/entity/CalendarEventPerson.java`
- Create: `backend/src/main/java/com/household/manager/repository/CalendarEventPersonRepository.java`
- Create: `backend/src/main/java/com/household/manager/dto/CalendarPersonView.java`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Modify: `backend/src/main/java/com/household/manager/dto/CalendarEventRequest.java`
- Modify: `backend/src/main/java/com/household/manager/dto/CalendarEventResponse.java`
- Modify: `backend/src/main/java/com/household/manager/dto/CalendarOccurrenceResponse.java`
- Modify: `backend/src/main/java/com/household/manager/calendar/CalendarEventService.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarEventServiceTest.java`

- [ ] **Step 1: Fehlschlagende Tests schreiben**

In `CalendarEventServiceTest.java` ergänzen (Mocks `AppUserRepository userRepository` und `CalendarEventPersonRepository personRepository` als `@Mock` hinzufügen und dem Konstruktoraufruf in `setUp()` anfügen):

```java
    private static final AppUser ANNA = AppUser.builder()
            .id(2L).username("anna").displayName("Anna").enabled(true).build();

    @Test
    void speichertDieZugeordnetenPersonen() {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(ANNA));

        service.create(validRequest().personUserIds(List.of(2L, 2L)).build());

        ArgumentCaptor<List<CalendarEventPerson>> saved = ArgumentCaptor.forClass(List.class);
        verify(personRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getUserId()).isEqualTo(2L);
    }

    @Test
    void weistUnbekanntePersonenAb() {
        when(userRepository.findAllById(List.of(99L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(validRequest().personUserIds(List.of(99L)).build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("99");
        verify(repository, never()).save(any());
    }

    @Test
    void liefertPersonenAmVorkommenMit() {
        CalendarEvent event = CalendarEvent.builder()
                .id(1L).title("Zahnarzt").categoryId(3L).allDay(true)
                .startDate(LocalDate.of(2026, 7, 25)).build();
        when(repository.findAll()).thenReturn(List.of(event));
        when(personRepository.findAll()).thenReturn(List.of(
                CalendarEventPerson.builder().calendarEventId(1L).userId(2L).build()));
        when(userRepository.findAll()).thenReturn(List.of(ANNA));

        List<CalendarOccurrenceResponse> occurrences = service.getOccurrences(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(occurrences).hasSize(1);
        assertThat(occurrences.get(0).getPersons())
                .extracting(CalendarPersonView::displayName)
                .containsExactly("Anna");
    }

    @Test
    void raeumtDieZuordnungenBeimLoeschenAb() {
        CalendarEvent event = CalendarEvent.builder()
                .id(1L).title("Zahnarzt").categoryId(3L).allDay(true)
                .startDate(LocalDate.of(2026, 7, 25)).build();
        when(repository.findById(1L)).thenReturn(Optional.of(event));

        service.delete(1L);

        verify(personRepository).deleteByCalendarEventId(1L);
    }
```

Nötige Importe: `com.household.manager.model.entity.AppUser`, `com.household.manager.model.entity.CalendarEventPerson`, `com.household.manager.repository.AppUserRepository`, `com.household.manager.repository.CalendarEventPersonRepository`, `com.household.manager.dto.CalendarPersonView`, `org.mockito.ArgumentCaptor`.

- [ ] **Step 2: Tests laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarEventServiceTest
```

Erwartet: Kompilierfehler „cannot find symbol: class CalendarEventPerson".

- [ ] **Step 3: Migration schreiben**

Neue Datei `backend/src/main/resources/db/changelog/changes/20260727-0045-calendar-event-persons.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260727-0045" author="household-manager">
        <comment>Zuordnung Termin zu Personen; keine Zeile fuer einen Termin = Haushaltstermin.</comment>

        <createTable tableName="calendar_event_person">
            <column name="calendar_event_id" type="BIGINT">
                <constraints nullable="false"/>
            </column>
            <column name="user_id" type="BIGINT">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <addPrimaryKey tableName="calendar_event_person"
                       columnNames="calendar_event_id, user_id"
                       constraintName="pk_calendar_event_person"/>

        <addForeignKeyConstraint
                baseTableName="calendar_event_person" baseColumnNames="calendar_event_id"
                referencedTableName="calendar_events" referencedColumnNames="id"
                constraintName="fk_calendar_event_person_event" onDelete="CASCADE"/>

        <addForeignKeyConstraint
                baseTableName="calendar_event_person" baseColumnNames="user_id"
                referencedTableName="app_user" referencedColumnNames="id"
                constraintName="fk_calendar_event_person_user" onDelete="CASCADE"/>

        <rollback>
            <dropTable tableName="calendar_event_person"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

In `db.changelog-master.xml` direkt nach dem Kategorien-Include ergänzen:

```xml
    <include file="db/changelog/changes/20260727-0045-calendar-event-persons.xml"/>
```

- [ ] **Step 4: Entity und Repository anlegen**

Neue Datei `backend/src/main/java/com/household/manager/model/entity/CalendarEventPerson.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/**
 * Zuordnung eines Termins zu einem Nutzer. Keine Zeile fuer einen Termin bedeutet
 * "betrifft den ganzen Haushalt" — das ist der Normalfall, kein fehlender Wert.
 */
@Entity
@Table(name = "calendar_event_person")
@IdClass(CalendarEventPerson.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventPerson {

    @Id
    @Column(name = "calendar_event_id", nullable = false)
    private Long calendarEventId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private Long calendarEventId;
        private Long userId;
    }
}
```

Neue Datei `backend/src/main/java/com/household/manager/repository/CalendarEventPersonRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.CalendarEventPerson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalendarEventPersonRepository
        extends JpaRepository<CalendarEventPerson, CalendarEventPerson.Key> {

    List<CalendarEventPerson> findByCalendarEventId(Long calendarEventId);

    void deleteByCalendarEventId(Long calendarEventId);
}
```

- [ ] **Step 5: Personen-Ansicht anlegen**

Neue Datei `backend/src/main/java/com/household/manager/dto/CalendarPersonView.java`:

```java
package com.household.manager.dto;

import com.household.manager.model.entity.AppUser;

/** Eine zugeordnete Person, wie Termin-Antworten sie einbetten. */
public record CalendarPersonView(Long id, String displayName) {

    public static CalendarPersonView of(AppUser user) {
        return new CalendarPersonView(user.getId(), user.getDisplayName());
    }
}
```

- [ ] **Step 6: DTOs erweitern**

In `CalendarEventRequest.java` ergänzen:

```java
    /** Zugeordnete Nutzer; leer oder null = Haushaltstermin. */
    private List<Long> personUserIds;
```

(Import `java.util.List`.)

In `CalendarEventResponse.java` und `CalendarOccurrenceResponse.java` jeweils ergänzen:

```java
    /** Zugeordnete Personen; leer = Haushaltstermin. */
    private List<CalendarPersonView> persons;
```

(Import `java.util.List`.)

- [ ] **Step 7: Service erweitern**

In `CalendarEventService.java` Konstruktor und Felder um `CalendarEventPersonRepository personRepository` und `AppUserRepository userRepository` erweitern (analog zu Task 1, Step 9) und folgende Methoden ergänzen:

```java
    /**
     * Speichert die Personenzuordnung neu: erst alles zu diesem Termin loeschen, dann die
     * gewuenschten Zeilen schreiben. Duplikate im Request werden dabei zusammengefasst.
     */
    private void replacePersons(Long eventId, List<Long> userIds) {
        personRepository.deleteByCalendarEventId(eventId);
        List<Long> distinct = distinctPersonIds(userIds);
        if (distinct.isEmpty()) {
            return;
        }
        personRepository.saveAll(distinct.stream()
                .map(userId -> CalendarEventPerson.builder()
                        .calendarEventId(eventId).userId(userId).build())
                .toList());
    }

    private List<Long> distinctPersonIds(List<Long> userIds) {
        return userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** Wirft, sobald eine Id keinem Nutzer entspricht — sonst entstuenden stille Geisterzuordnungen. */
    private void validatePersons(List<Long> userIds) {
        List<Long> distinct = distinctPersonIds(userIds);
        if (distinct.isEmpty()) {
            return;
        }
        Set<Long> known = userRepository.findAllById(distinct).stream()
                .map(AppUser::getId).collect(Collectors.toSet());
        for (Long userId : distinct) {
            if (!known.contains(userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Der Nutzer %d existiert nicht.".formatted(userId));
            }
        }
    }
```

In `validate(CalendarEventRequest)` als letzte Zeile `validatePersons(request.getPersonUserIds());` ergänzen.

In `create`, `update` und `updateOccurrence` jeweils **nach** dem `repository.save(...)` die Zuordnung schreiben, zum Beispiel in `create`:

```java
    @Transactional
    public CalendarEventResponse create(CalendarEventRequest request) {
        validate(request);
        CalendarEvent saved = repository.save(applyRequest(request, new CalendarEvent()));
        replacePersons(saved.getId(), request.getPersonUserIds());
        CalendarEventResponse response = toResponse(saved);
        auditService.record("calendar.create", request.getTitle());
        return response;
    }
```

In `delete` vor `repository.delete(event)` ergänzen (die Datenbank räumt per `ON DELETE CASCADE` ohnehin auf; der Aufruf hält den Java-Pfad ohne Datenbank-Cascade korrekt — dieselbe bewusste Absicherung wie bei `deleteByRecurringParentId`):

```java
        personRepository.deleteByCalendarEventId(id);
```

In `getOccurrences` nach dem Laden der Kategorien ergänzen:

```java
        Map<Long, String> displayNames = userRepository.findAll().stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        Map<Long, List<CalendarPersonView>> personsByEvent = personRepository.findAll().stream()
                .filter(link -> displayNames.containsKey(link.getUserId()))
                .collect(Collectors.groupingBy(CalendarEventPerson::getCalendarEventId,
                        Collectors.mapping(link -> new CalendarPersonView(
                                link.getUserId(), displayNames.get(link.getUserId())),
                                Collectors.toList())));
```

`toOccurrence` bekommt `personsByEvent` als weiteren Parameter und setzt:

```java
                .persons(personsByEvent.getOrDefault(event.getId(), List.of()))
```

> Achtung: Die Zuordnung hängt immer an der **eigenen** Zeilen-Id, auch bei Override-Zeilen — eine Override-Zeile hat ihre eigenen Personen. `eventId` in der Antwort zeigt dagegen weiterhin auf den Master, deshalb steht hier bewusst `event.getId()` und nicht der Wert aus dem Builder.

`toResponse` setzt:

```java
                .persons(personRepository.findByCalendarEventId(event.getId()).stream()
                        .map(link -> userRepository.findById(link.getUserId())
                                .map(CalendarPersonView::of).orElse(null))
                        .filter(Objects::nonNull)
                        .toList())
```

- [ ] **Step 8: Tests laufen lassen und grün bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarEventServiceTest
```

Erwartet: alle Tests der Klasse grün.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main backend/src/test && git commit -m "feat(kalender): Termine koennen Personen zugeordnet werden"
```

---

### Task 5: Personen im Erinnerungs-Event

**Files:**
- Modify: `backend/src/main/java/com/household/manager/calendar/CalendarReminderScheduler.java`
- Test: `backend/src/test/java/com/household/manager/calendar/CalendarReminderSchedulerTest.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

In `CalendarReminderSchedulerTest.java` ergänzen:

```java
    @Test
    void meldetZugeordnetePersonenAlsAttribute() {
        CalendarOccurrenceResponse occ = occurrence(false, LocalTime.of(10, 0));
        occ.setPersons(List.of(new CalendarPersonView(2L, "Anna")));
        when(calendarService.getUpcoming(anyInt())).thenReturn(List.of(occ));

        scheduler.checkDueReminders();

        ArgumentCaptor<EntityStateUpdate> update = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(update.capture());
        assertThat(update.getValue().getAttributes()).containsEntry("persons", List.of("Anna"));
        assertThat(update.getValue().getAttributes()).containsEntry("personIds", List.of(2L));
    }
```

Importe `com.household.manager.dto.CalendarPersonView` und `org.mockito.ArgumentMatchers.anyInt` ergänzen.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarReminderSchedulerTest
```

Erwartet: FAIL — die Attribute `persons` und `personIds` fehlen.

- [ ] **Step 3: Attribute ergänzen**

In `CalendarReminderScheduler.fire(...)` nach `attributes.put("eventId", occ.getEventId());` einfügen:

```java
        // Ids zum Filtern (stabil) und Namen zum Ansagen (aenderbar): ein Flow, der auf
        // den Anzeigenamen filtert, braeche beim naechsten Umbenennen still.
        List<CalendarPersonView> persons =
                occ.getPersons() != null ? occ.getPersons() : List.of();
        attributes.put("personIds", persons.stream().map(CalendarPersonView::id).toList());
        attributes.put("persons", persons.stream().map(CalendarPersonView::displayName).toList());
```

Importe `com.household.manager.dto.CalendarPersonView` und `java.util.List` ergänzen.

- [ ] **Step 4: Test laufen lassen und grün bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CalendarReminderSchedulerTest
```

Erwartet: alle Tests der Klasse grün.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main backend/src/test && git commit -m "feat(kalender): Erinnerungs-Event meldet die zugeordneten Personen"
```

---

### Task 6: Nutzerliste und eigene Id

**Files:**
- Create: `backend/src/main/java/com/household/manager/security/HouseholdUserController.java`
- Modify: `backend/src/main/java/com/household/manager/security/AppUserPrincipal.java`
- Modify: `backend/src/main/java/com/household/manager/security/dto/CurrentUserResponse.java`
- Test: `backend/src/test/java/com/household/manager/security/CurrentUserResponseTest.java`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

In `CurrentUserResponseTest.java` ergänzen (Hilfsmethoden der bestehenden Datei wiederverwenden):

```java
    @Test
    void liefertDieIdDesAngemeldetenNutzers() {
        AppUser user = AppUser.builder()
                .id(5L).username("anna").displayName("Anna")
                .passwordHash("x").role(UserRole.MEMBER).enabled(true).build();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new AppUserPrincipal(user), null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));

        assertThat(CurrentUserResponse.from(authentication).id()).isEqualTo(5L);
    }

    @Test
    void laesstDieIdBeiEinemServiceTokenLeer() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "service-token", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThat(CurrentUserResponse.from(authentication).id()).isNull();
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test -Dtest=CurrentUserResponseTest
```

Erwartet: Kompilierfehler „cannot find symbol: method id()".

- [ ] **Step 3: Id in Principal und Antwort ergänzen**

In `AppUserPrincipal.java` ein Feld ergänzen:

```java
    private final Long id;
```

und im Konstruktor nach `super(...)` setzen:

```java
        this.id = user.getId();
```

In `CurrentUserResponse.java` die Signatur und die Fabrikmethode anpassen:

```java
/** Der angemeldete Aktor, wie ihn das Frontend braucht. {@code id} ist null bei Service-Tokens. */
public record CurrentUserResponse(Long id, String username, String displayName, String role,
                                  boolean mustChangePassword) {

    public static CurrentUserResponse from(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("KIOSK");
        boolean isUser = authentication.getPrincipal() instanceof AppUserPrincipal;
        AppUserPrincipal principal = isUser ? (AppUserPrincipal) authentication.getPrincipal() : null;
        String displayName = isUser ? principal.getDisplayName() : authentication.getName();
        boolean mustChangePassword = isUser && principal.isMustChangePassword();
        Long id = isUser ? principal.getId() : null;
        return new CurrentUserResponse(id, authentication.getName(), displayName, role,
                mustChangePassword);
    }
}
```

- [ ] **Step 4: Nutzerliste anlegen**

Neue Datei `backend/src/main/java/com/household/manager/security/HouseholdUserController.java`:

```java
package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Schlanke Nutzerliste fuer die Personenauswahl im Kalender. {@code /v1/admin/users} ist
 * ADMIN-only — ohne diesen Endpunkt koennte ein MEMBER keine Person auswaehlen.
 * Ausgeliefert werden bewusst nur Id, Anzeigename und Aktiv-Flag: keine Rolle, kein
 * Benutzername. Die Leseberechtigung ergibt sich aus der generischen GET-Regel in
 * SecurityConfig (KIOSK und darueber).
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class HouseholdUserController {

    private final AppUserService service;

    public record HouseholdUserResponse(Long id, String displayName, boolean enabled) {
        static HouseholdUserResponse of(AppUser user) {
            return new HouseholdUserResponse(user.getId(), user.getDisplayName(), user.isEnabled());
        }
    }

    @GetMapping
    public ResponseEntity<List<HouseholdUserResponse>> list() {
        return ResponseEntity.ok(service.list().stream()
                .map(HouseholdUserResponse::of)
                .toList());
    }
}
```

- [ ] **Step 5: Tests laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test
```

Erwartet: alles grün außer den beiden vorbestehenden Datenbank-Fehlschlägen.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main backend/src/test && git commit -m "feat(security): schlanke Nutzerliste und eigene Id in /v1/auth/me"
```

---

### Task 7: Frontend-Modelle und Services

**Files:**
- Create: `frontend/src/app/models/calendar-category.model.ts`
- Create: `frontend/src/app/services/calendar-category.service.ts`
- Create: `frontend/src/app/services/household-user.service.ts`
- Modify: `frontend/src/app/models/calendar-event.model.ts`
- Modify: `frontend/src/app/models/auth.model.ts`

- [ ] **Step 1: Kategorie-Modell anlegen**

Neue Datei `frontend/src/app/models/calendar-category.model.ts`:

```typescript
/** Eine gepflegte Kalender-Kategorie, wie die Admin-Seite sie verwaltet. */
export interface CalendarCategory {
  id: number;
  /** Stabiler Schluessel; Flows filtern darauf. Nicht aenderbar. */
  key: string;
  name: string;
  /** Hex-Farbe, z. B. "#64b5f6". */
  color: string;
  /** Material-Symbol-Name; leer/null = kein Icon. */
  icon: string | null;
  sortOrder: number;
  active: boolean;
}

/** Anlege-/Aenderungsdaten; der Schluessel wird serverseitig vergeben. */
export interface CalendarCategoryRequest {
  name: string;
  color: string;
  icon: string | null;
  sortOrder: number;
  active: boolean;
}

/** Die Kategorie, wie sie an einem Termin eingebettet mitkommt. */
export interface CalendarCategoryRef {
  id: number;
  key: string;
  name: string;
  color: string;
  icon: string | null;
}
```

- [ ] **Step 2: Termin-Modell umstellen**

`frontend/src/app/models/calendar-event.model.ts` **vollständig** ersetzen:

```typescript
import { CalendarCategoryRef } from './calendar-category.model';

/** Eine dem Termin zugeordnete Person. */
export interface CalendarPerson {
  id: number;
  displayName: string;
}

/** Anlege-/Aenderungsdaten eines Termins. */
export interface CalendarEventRequest {
  title: string;
  notes: string | null;
  categoryId: number;
  /** Leer = Haushaltstermin. */
  personUserIds: number[];
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
export interface CalendarEvent extends Omit<CalendarEventRequest, 'categoryId' | 'personUserIds'> {
  id: number;
  category: CalendarCategoryRef;
  persons: CalendarPerson[];
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
  category: CalendarCategoryRef;
  persons: CalendarPerson[];
  allDay: boolean;
  startTime: string | null;
  endTime: string | null;
  endDate: string | null;
  recurring: boolean;
  /** 0 = heute, 1 = morgen. */
  daysUntil: number;
}
```

- [ ] **Step 3: Kategorie-Service anlegen**

Neue Datei `frontend/src/app/services/calendar-category.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CalendarCategory, CalendarCategoryRequest } from '../models/calendar-category.model';

/** Service fuer die Kalender-Kategorien. Schreiben ist serverseitig ADMIN-only. */
@Injectable({ providedIn: 'root' })
export class CalendarCategoryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/calendar/categories';

  list(): Observable<CalendarCategory[]> {
    return this.http.get<CalendarCategory[]>(this.baseUrl).pipe(catchError(this.handleError));
  }

  create(request: CalendarCategoryRequest): Observable<CalendarCategory> {
    return this.http.post<CalendarCategory>(this.baseUrl, request)
      .pipe(catchError(this.handleError));
  }

  update(id: number, request: CalendarCategoryRequest): Observable<CalendarCategory> {
    return this.http.put<CalendarCategory>(`${this.baseUrl}/${id}`, request)
      .pipe(catchError(this.handleError));
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Kategorie-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Kategorie-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
```

- [ ] **Step 4: Nutzer-Service anlegen**

Neue Datei `frontend/src/app/services/household-user.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Ein Haushaltsmitglied, wie es die Personenauswahl braucht. */
export interface HouseholdUser {
  id: number;
  displayName: string;
  enabled: boolean;
}

/** Schlanke Nutzerliste fuer die Personenauswahl (nicht die Admin-Nutzerverwaltung). */
@Injectable({ providedIn: 'root' })
export class HouseholdUserService {
  private readonly http = inject(HttpClient);

  list(): Observable<HouseholdUser[]> {
    return this.http.get<HouseholdUser[]>('/api/v1/users');
  }
}
```

- [ ] **Step 5: Eigene Id im Auth-Modell ergänzen**

In `frontend/src/app/models/auth.model.ts` das Interface `CurrentUser` erweitern:

```typescript
export interface CurrentUser {
  /** null bei Anmeldung per Service-Token. */
  id: number | null;
  username: string;
  displayName: string;
  role: UserRole;
  /** true = Passwortwechsel erzwungen (z. B. Bootstrap-Admin mit "changeit") */
  mustChangePassword: boolean;
}
```

- [ ] **Step 6: Build prüfen**

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```

Erwartet: Fehler nur noch in `calendar.component.ts` (nutzt `CATEGORY_META` und `form.category`) — die räumt Task 8 auf.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/models frontend/src/app/services && git commit -m "feat(kalender-ui): Modelle und Services fuer Kategorien und Personen"
```

---

### Task 8: Kalenderseite auf Stammdaten umstellen

**Files:**
- Modify: `frontend/src/app/pages/calendar/calendar.component.ts`
- Modify: `frontend/src/app/pages/calendar/calendar.component.html`
- Test: `frontend/src/app/pages/calendar/calendar.component.spec.ts`

- [ ] **Step 1: Komponentenzustand umstellen**

In `calendar.component.ts` den Import

```typescript
import {
  CATEGORY_META, CalendarCategory, CalendarEvent, CalendarEventRequest, CalendarOccurrence
} from '../../models/calendar-event.model';
```

ersetzen durch:

```typescript
import {
  CalendarEvent, CalendarEventRequest, CalendarOccurrence
} from '../../models/calendar-event.model';
import { CalendarCategory } from '../../models/calendar-category.model';
import { CalendarCategoryService } from '../../services/calendar-category.service';
import { HouseholdUser, HouseholdUserService } from '../../services/household-user.service';
import { AuthService } from '../../services/auth.service';
```

Im Formularzustand `CalendarFormState` das Feld `category: CalendarCategory;` ersetzen durch:

```typescript
  categoryId: number | null;
  personUserIds: number[];
```

Die Felder

```typescript
  readonly categoryMeta = CATEGORY_META;
  readonly categories = Object.keys(CATEGORY_META) as CalendarCategory[];
```

ersetzen durch:

```typescript
  private readonly categoryService = inject(CalendarCategoryService);
  private readonly userService = inject(HouseholdUserService);
  private readonly authService = inject(AuthService);

  /** Alle Kategorien inkl. deaktivierter — Bestandstermine brauchen ihre Farbe weiter. */
  categories: CalendarCategory[] = [];
  users: HouseholdUser[] = [];
  /** false = Stammdaten noch nicht geladen; der Termindialog bleibt dann zu. */
  categoriesLoaded = false;

  /**
   * Id des angemeldeten Nutzers; null bei Service-Token — dann entfaellt "Meine".
   * AuthService haelt den Nutzer als Signal (`currentUser` ist ein computed), nicht als
   * Observable; die Kalenderroute liegt hinter dem authGuard, der Wert steht also bereits.
   */
  get currentUserId(): number | null {
    return this.authService.currentUser()?.id ?? null;
  }
```

`emptyForm()` anpassen — die Vorgabe ist die erste aktive Kategorie:

```typescript
  private emptyForm(): CalendarFormState {
    return {
      title: '', notes: '', categoryId: this.defaultCategoryId(), personUserIds: [],
      allDay: true,
      // die uebrigen Felder unveraendert aus der bisherigen Fassung uebernehmen
      startDate: '', startTime: '', endTime: '', endDate: ''
    };
  }

  /** Erste aktive Kategorie; ohne geladene Stammdaten null (der Dialog oeffnet dann nicht). */
  private defaultCategoryId(): number | null {
    return this.activeCategories()[0]?.id ?? null;
  }

  /** Waehlbare Kategorien: alle aktiven plus die aktuell gesetzte (auch wenn deaktiviert). */
  selectableCategories(): CalendarCategory[] {
    const active = this.activeCategories();
    const current = this.categories.find(cat => cat.id === this.form.categoryId);
    return current && !current.active ? [current, ...active] : active;
  }

  private activeCategories(): CalendarCategory[] {
    return this.categories.filter(cat => cat.active);
  }
```

`colorFor(occurrence)` ersetzen:

```typescript
  colorFor(occurrence: CalendarOccurrence): string {
    return occurrence.category?.color ?? '#64b5f6';
  }

  /** Initialen der zugeordneten Personen; leer = Haushaltstermin. */
  initialsFor(occurrence: CalendarOccurrence): string[] {
    return (occurrence.persons ?? []).map(person => person.displayName.charAt(0).toUpperCase());
  }
```

In `openEdit(...)` das Setzen des Formulars anpassen — statt `category: occurrence.category` jetzt:

```typescript
          categoryId: occurrence.category?.id ?? this.defaultCategoryId(),
          personUserIds: (occurrence.persons ?? []).map(person => person.id),
```

In `buildRequest(...)` statt `category: this.form.category`:

```typescript
      categoryId: this.form.categoryId!,
      personUserIds: this.form.personUserIds,
```

- [ ] **Step 2: Stammdaten beim Start laden**

In `ngOnInit()` **vor** dem bisherigen Monatsabruf ergänzen:

```typescript
    this.categoryService.list().subscribe({
      next: categories => {
        this.categories = categories;
        this.categoriesLoaded = true;
        this.form = this.emptyForm();
      },
      // Ein Dialog mit leerer Auswahlliste sieht aus wie "es gibt keine Kategorien" und
      // verleitet zu falschen Eingaben — deshalb bleibt er ohne Stammdaten geschlossen.
      error: () => this.loadError = 'Die Kategorien konnten nicht geladen werden.'
    });
    this.userService.list().subscribe({
      next: users => this.users = users.filter(user => user.enabled),
      error: () => this.users = []
    });
```

Der angemeldete Nutzer wird nicht abonniert — `AuthService.currentUser` ist ein `computed`-Signal, das der Getter `currentUserId` direkt liest.

In `openCreate(day)` und `openEdit(...)` jeweils als erste Zeile ergänzen:

```typescript
    if (!this.categoriesLoaded) {
      return;
    }
```

- [ ] **Step 3: Template anpassen**

In `calendar.component.html` den Kategorie-Block

```html
          <span>Kategorie</span>
          <select [(ngModel)]="form.category" name="category">
            @for (cat of categories; track cat) {
              <option [value]="cat">{{ categoryMeta[cat].label }}</option>
            }
          </select>
```

ersetzen durch:

```html
          <span>Kategorie</span>
          <select [(ngModel)]="form.categoryId" name="categoryId">
            @for (cat of selectableCategories(); track cat.id) {
              <option [ngValue]="cat.id">{{ cat.name }}{{ cat.active ? '' : ' (deaktiviert)' }}</option>
            }
          </select>
```

Direkt darunter die Personenauswahl ergänzen:

```html
        <label class="calendar__field">
          <span>Personen</span>
          <div class="calendar__persons">
            @for (user of users; track user.id) {
              <button type="button"
                      class="calendar__person"
                      [class.calendar__person--on]="form.personUserIds.includes(user.id)"
                      (click)="togglePerson(user.id)">
                {{ user.displayName }}
              </button>
            }
          </div>
          @if (form.personUserIds.length === 0) {
            <small class="calendar__hint">Niemand ausgewählt — betrifft den ganzen Haushalt.</small>
          }
        </label>
```

Im Chip-Block nach `<span class="calendar__chip-time">` die Initialen ergänzen:

```html
                @for (initial of initialsFor(occ); track initial) {
                  <span class="calendar__chip-initial">{{ initial }}</span>
                }
```

- [ ] **Step 4: Umschaltmethode ergänzen**

In `calendar.component.ts`:

```typescript
  /** Personenauswahl im Dialog umschalten. */
  togglePerson(userId: number): void {
    const selected = this.form.personUserIds;
    this.form.personUserIds = selected.includes(userId)
      ? selected.filter(id => id !== userId)
      : [...selected, userId];
  }
```

- [ ] **Step 5: Styles ergänzen**

In `calendar.component.scss` am Ende ergänzen:

```scss
.calendar__persons {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.calendar__person {
  border: 1px solid rgba(255, 255, 255, 0.25);
  border-radius: 999px;
  padding: 0.25rem 0.75rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 0.85rem;

  &--on {
    background: rgba(255, 255, 255, 0.18);
    border-color: rgba(255, 255, 255, 0.5);
  }
}

.calendar__chip-initial {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.1rem;
  height: 1.1rem;
  margin-left: 0.15rem;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.25);
  font-size: 0.65rem;
  line-height: 1;
}

.calendar__hint {
  opacity: 0.7;
  font-size: 0.8rem;
}
```

- [ ] **Step 6: Tests anpassen und laufen lassen**

In `calendar.component.spec.ts` die Testdaten auf die neue Form umstellen: `category` wird zu `{ id: 3, key: 'health', name: 'Gesundheit', color: '#e57373', icon: null }`, `persons: []` ergänzen. `CalendarCategoryService` und `HouseholdUserService` per `provideHttpClientTesting()` bedienen oder als Spy bereitstellen.

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: nur die 3 Baseline-Fehlschläge.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/calendar && git commit -m "feat(kalender-ui): Kategorien aus Stammdaten und Personenauswahl im Dialog"
```

---

### Task 9: Filterleiste

**Files:**
- Modify: `frontend/src/app/pages/calendar/calendar.component.ts`
- Modify: `frontend/src/app/pages/calendar/calendar.component.html`
- Modify: `frontend/src/app/pages/calendar/calendar.component.scss`
- Test: `frontend/src/app/pages/calendar/calendar.component.spec.ts`

- [ ] **Step 1: Fehlschlagenden Test schreiben**

In `calendar.component.spec.ts` ergänzen:

```typescript
  it('zeigt beim Personenfilter auch Termine ohne Zuordnung', () => {
    const meins = { ...baseOccurrence, eventId: 1, persons: [{ id: 2, displayName: 'Anna' }] };
    const haushalt = { ...baseOccurrence, eventId: 2, persons: [] };
    const fremd = { ...baseOccurrence, eventId: 3, persons: [{ id: 9, displayName: 'Ben' }] };

    component.personFilter = 2;

    expect(component.visible([meins, haushalt, fremd]).map(o => o.eventId))
      .toEqual([1, 2]);
  });

  it('zeigt ohne Filter alles', () => {
    const fremd = { ...baseOccurrence, eventId: 3, persons: [{ id: 9, displayName: 'Ben' }] };
    component.personFilter = null;

    expect(component.visible([fremd]).map(o => o.eventId)).toEqual([3]);
  });
```

`baseOccurrence` ist ein vollständiges `CalendarOccurrence`-Objekt aus den bestehenden Testdaten der Datei.

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: FAIL — `component.visible is not a function`.

- [ ] **Step 3: Filter implementieren**

In `calendar.component.ts` ergänzen:

```typescript
  /** null = alle; sonst die Nutzer-Id, auf die gefiltert wird. Nicht persistiert. */
  personFilter: number | null = null;

  /**
   * Ein Personenfilter zeigt zusaetzlich immer die Termine ohne Zuordnung: "Meine Termine"
   * heisst "mir zugeordnet oder den ganzen Haushalt betreffend". Sonst verschwaende die
   * Muellabfuhr genau dann, wenn jemand auf sich selbst filtert.
   */
  visible(occurrences: CalendarOccurrence[]): CalendarOccurrence[] {
    if (this.personFilter === null) {
      return occurrences;
    }
    return occurrences.filter(occ =>
      (occ.persons ?? []).length === 0
      || (occ.persons ?? []).some(person => person.id === this.personFilter));
  }
```

`chipsFor(day)` zieht das Ergebnis durch den Filter — aus

```typescript
  chipsFor(day: MonthDay): CalendarOccurrence[] {
    return this.occurrencesByDate.get(day.isoDate) ?? [];
  }
```

wird

```typescript
  chipsFor(day: MonthDay): CalendarOccurrence[] {
    return this.visible(this.occurrencesByDate.get(day.isoDate) ?? []);
  }
```

`overflowCount(day)` ruft `chipsFor` auf und zählt damit automatisch nur die sichtbaren Termine — „+n weitere" bleibt konsistent mit dem Filter.

- [ ] **Step 4: Filterleiste ins Template**

In `calendar.component.html` unmittelbar vor dem Monatsraster einfügen:

```html
<div class="calendar__filters">
  <button type="button" class="calendar__filter"
          [class.calendar__filter--on]="personFilter === null"
          (click)="personFilter = null">Alle</button>
  @if (currentUserId !== null) {
    <button type="button" class="calendar__filter"
            [class.calendar__filter--on]="personFilter === currentUserId"
            (click)="personFilter = currentUserId">Meine</button>
  }
  @for (user of users; track user.id) {
    <button type="button" class="calendar__filter"
            [class.calendar__filter--on]="personFilter === user.id"
            (click)="personFilter = user.id">{{ user.displayName }}</button>
  }
</div>
```

- [ ] **Step 5: Styles ergänzen**

In `calendar.component.scss` am Ende ergänzen:

```scss
.calendar__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-bottom: 0.75rem;
}

.calendar__filter {
  border: 1px solid rgba(255, 255, 255, 0.25);
  border-radius: 999px;
  padding: 0.25rem 0.85rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 0.85rem;

  &--on {
    background: rgba(255, 255, 255, 0.18);
    border-color: rgba(255, 255, 255, 0.5);
  }
}
```

- [ ] **Step 6: Tests laufen lassen und grün bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: nur die 3 Baseline-Fehlschläge.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/calendar && git commit -m "feat(kalender-ui): Personenfilter im Monatsraster"
```

---

### Task 10: Admin-Seite für Kategorien

**Files:**
- Create: `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.ts`
- Create: `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.html`
- Create: `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.scss`
- Create: `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.spec.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/header/header.component.ts`

- [ ] **Step 1: Komponente anlegen**

Neue Datei `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.ts`:

```typescript
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CalendarCategory, CalendarCategoryRequest } from '../../models/calendar-category.model';
import { CalendarCategoryService } from '../../services/calendar-category.service';
import { IconPickerComponent } from '../../components/icon-picker/icon-picker.component';

/** Formularzustand; getrennt von der Liste, damit ein Abbruch nichts anfasst. */
interface CategoryFormState extends CalendarCategoryRequest {
  /** null = Anlegen, sonst die Id der bearbeiteten Kategorie. */
  id: number | null;
  /** Nur zur Anzeige — der Schluessel ist unveraenderlich. */
  key: string | null;
}

/**
 * Verwaltung der Kalender-Kategorien. Der Schluessel wird schreibgeschuetzt gezeigt: er ist
 * der Wert, auf den ein Flow ueber den State von event.calendar_reminder filtert.
 */
@Component({
  selector: 'app-admin-calendar-categories',
  standalone: true,
  imports: [CommonModule, FormsModule, IconPickerComponent],
  templateUrl: './admin-calendar-categories.component.html',
  styleUrl: './admin-calendar-categories.component.scss'
})
export class AdminCalendarCategoriesComponent implements OnInit {
  private readonly service = inject(CalendarCategoryService);

  categories: CalendarCategory[] = [];
  form: CategoryFormState = this.emptyForm();
  formOpen = false;
  error: string | null = null;
  /** Meldung des Loeschschutzes samt Angebot zum Deaktivieren. */
  blocked: { category: CalendarCategory; message: string } | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.service.list().subscribe({
      next: categories => {
        this.categories = categories;
        this.error = null;
      },
      error: (err: Error) => this.error = err.message
    });
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.formOpen = true;
  }

  openEdit(category: CalendarCategory): void {
    this.form = {
      id: category.id, key: category.key, name: category.name, color: category.color,
      icon: category.icon, sortOrder: category.sortOrder, active: category.active
    };
    this.formOpen = true;
  }

  save(): void {
    const request: CalendarCategoryRequest = {
      name: this.form.name, color: this.form.color, icon: this.form.icon,
      sortOrder: this.form.sortOrder, active: this.form.active
    };
    const call = this.form.id === null
      ? this.service.create(request)
      : this.service.update(this.form.id, request);
    call.subscribe({
      next: () => {
        this.formOpen = false;
        this.load();
      },
      error: (err: Error) => this.error = err.message
    });
  }

  cancel(): void {
    this.formOpen = false;
    this.error = null;
  }

  remove(category: CalendarCategory): void {
    this.blocked = null;
    this.service.delete(category.id).subscribe({
      next: () => this.load(),
      // Der Server antwortet mit 409 und der Anzahl betroffener Termine; statt einer
      // Sackgasse bieten wir direkt das Deaktivieren an.
      error: (err: Error) => this.blocked = { category, message: err.message }
    });
  }

  deactivate(category: CalendarCategory): void {
    this.blocked = null;
    this.service.update(category.id, {
      name: category.name, color: category.color, icon: category.icon,
      sortOrder: category.sortOrder, active: false
    }).subscribe({
      next: () => this.load(),
      error: (err: Error) => this.error = err.message
    });
  }

  private emptyForm(): CategoryFormState {
    return {
      id: null, key: null, name: '', color: '#64b5f6', icon: null,
      sortOrder: this.categories.length + 1, active: true
    };
  }
}
```

- [ ] **Step 2: Template anlegen**

Neue Datei `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.html`:

```html
<div class="categories">
  <header class="categories__header">
    <div>
      <h1>Kalender-Kategorien</h1>
      <p class="categories__subtitle">
        Name, Farbe, Icon und Reihenfolge der Terminkategorien. Der Schlüssel bleibt
        unverändert — Flows filtern darauf.
      </p>
    </div>
    <button type="button" class="btn" (click)="openCreate()">Neue Kategorie</button>
  </header>

  @if (error) {
    <p class="categories__error">{{ error }}</p>
  }

  @if (blocked) {
    <div class="categories__blocked">
      <p>{{ blocked.message }}</p>
      <button type="button" class="btn btn--ghost" (click)="deactivate(blocked.category)">
        Stattdessen deaktivieren
      </button>
      <button type="button" class="btn btn--ghost" (click)="blocked = null">Abbrechen</button>
    </div>
  }

  @if (formOpen) {
    <form class="categories__form" (ngSubmit)="save()">
      <label>
        <span>Name</span>
        <input type="text" name="name" [(ngModel)]="form.name" required maxlength="100">
      </label>
      <label>
        <span>Farbe</span>
        <input type="color" name="color" [(ngModel)]="form.color">
      </label>
      <label>
        <span>Icon</span>
        <app-icon-picker [(value)]="form.icon"></app-icon-picker>
      </label>
      <label>
        <span>Reihenfolge</span>
        <input type="number" name="sortOrder" [(ngModel)]="form.sortOrder" min="1">
      </label>
      <label class="categories__checkbox">
        <input type="checkbox" name="active" [(ngModel)]="form.active">
        <span>Aktiv (wählbar im Termindialog)</span>
      </label>
      @if (form.key) {
        <p class="categories__key">Schlüssel: <code>{{ form.key }}</code> (nicht änderbar)</p>
      }
      <div class="categories__actions">
        <button type="submit" class="btn">Speichern</button>
        <button type="button" class="btn btn--ghost" (click)="cancel()">Abbrechen</button>
      </div>
    </form>
  }

  <table class="categories__table">
    <thead>
      <tr>
        <th>Icon</th><th>Name</th><th>Farbe</th><th>Schlüssel</th>
        <th>Reihenfolge</th><th>Aktiv</th><th></th>
      </tr>
    </thead>
    <tbody>
      @for (category of categories; track category.id) {
        <tr>
          <td>{{ category.icon || '—' }}</td>
          <td>{{ category.name }}</td>
          <td><span class="categories__swatch" [style.background]="category.color"></span></td>
          <td><code>{{ category.key }}</code></td>
          <td>{{ category.sortOrder }}</td>
          <td>{{ category.active ? 'ja' : 'nein' }}</td>
          <td>
            <button type="button" class="btn btn--ghost" (click)="openEdit(category)">Bearbeiten</button>
            <button type="button" class="btn btn--ghost" (click)="remove(category)">Löschen</button>
          </td>
        </tr>
      }
    </tbody>
  </table>
</div>
```

- [ ] **Step 3: Styles anlegen**

Neue Datei `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.scss`:

```scss
.categories {
  padding: 1.5rem;

  &__header {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 1rem;
    margin-bottom: 1.25rem;
  }

  &__subtitle {
    opacity: 0.75;
    max-width: 48ch;
  }

  &__error {
    color: #e57373;
  }

  &__blocked {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    flex-wrap: wrap;
    padding: 0.75rem 1rem;
    margin-bottom: 1rem;
    border-radius: 0.5rem;
    background: rgba(229, 115, 115, 0.15);
  }

  &__form {
    display: grid;
    gap: 0.75rem;
    max-width: 32rem;
    margin-bottom: 1.5rem;

    label {
      display: grid;
      gap: 0.25rem;
    }
  }

  &__checkbox {
    display: flex !important;
    align-items: center;
    gap: 0.5rem;
  }

  &__key code {
    font-family: monospace;
  }

  &__actions {
    display: flex;
    gap: 0.5rem;
  }

  &__table {
    width: 100%;
    border-collapse: collapse;

    th, td {
      text-align: left;
      padding: 0.5rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
    }

    code {
      font-family: monospace;
      opacity: 0.85;
    }
  }

  &__swatch {
    display: inline-block;
    width: 1.25rem;
    height: 1.25rem;
    border-radius: 0.25rem;
  }
}
```

- [ ] **Step 4: Test schreiben**

Neue Datei `frontend/src/app/pages/admin-calendar-categories/admin-calendar-categories.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminCalendarCategoriesComponent } from './admin-calendar-categories.component';

describe('AdminCalendarCategoriesComponent', () => {
  let fixture: ComponentFixture<AdminCalendarCategoriesComponent>;
  let component: AdminCalendarCategoriesComponent;
  let http: HttpTestingController;

  const arbeit = {
    id: 7, key: 'arbeit', name: 'Arbeit', color: '#ffb74d',
    icon: null, sortOrder: 5, active: true
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminCalendarCategoriesComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(AdminCalendarCategoriesComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/calendar/categories').flush([arbeit]);
  });

  afterEach(() => http.verify());

  it('zeigt die geladenen Kategorien', () => {
    expect(component.categories).toEqual([arbeit]);
  });

  it('bietet beim Loeschkonflikt das Deaktivieren an', () => {
    component.remove(arbeit);
    http.expectOne('/api/v1/calendar/categories/7').flush(
      { message: 'Die Kategorie wird von 4 Termin(en) genutzt und kann nicht geloescht werden.' },
      { status: 409, statusText: 'Conflict' });

    expect(component.blocked?.category).toEqual(arbeit);
    expect(component.blocked?.message).toContain('4');
  });
});
```

- [ ] **Step 5: Route und Menüeintrag ergänzen**

In `frontend/src/app/app.routes.ts` nach dem Block für `admin/audit-log` einfügen:

```typescript
  {
    path: 'admin/calendar-categories',
    loadComponent: () => import('./pages/admin-calendar-categories/admin-calendar-categories.component')
      .then(m => m.AdminCalendarCategoriesComponent),
    canActivate: [adminGuard],
    title: 'Kalender-Kategorien - Household Manager'
  },
```

In `frontend/src/app/components/header/header.component.ts` in der Admin-Gruppe nach dem Eintrag für `/admin/audit-log` ergänzen:

```typescript
        { path: '/admin/calendar-categories', label: 'Kalender-Kategorien', minRole: 'ADMIN' }
```

- [ ] **Step 6: Tests laufen lassen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: nur die 3 Baseline-Fehlschläge; die zwei neuen Tests sind grün.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/admin-calendar-categories frontend/src/app/app.routes.ts frontend/src/app/components/header && git commit -m "feat(admin): Seite zur Verwaltung der Kalender-Kategorien"
```

---

### Task 11: Dokumentation

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Abschnitt „Haushaltskalender" ergänzen**

In `CLAUDE.md` im Abschnitt „Haushaltskalender" nach dem Punkt zur Flow-Anbindung einfügen:

```markdown
- **Kategorien sind Stammdaten** (`calendar_category`, Admin-Seite „Kalender-Kategorien", Route `admin/calendar-categories`): Name, Farbe, Icon, Reihenfolge und Aktiv-Flag sind pflegbar. Der Schlüssel `cat_key` entsteht **einmal** beim Anlegen aus dem Namen (Umlaute transliteriert, Kollision → `_2`) und ist danach unveränderlich — er ist der State von `event.calendar_reminder`, auf den Flows filtern. Umbenennen und Deaktivieren sind deshalb gefahrlos; **Löschen** einer Kategorie lässt einen darauf filternden Flow still ins Leere laufen. Löschen ist nur möglich, solange kein Termin die Kategorie nutzt (409 mit Anzahl, `ON DELETE RESTRICT` als Datenbank-Hälfte); die Admin-Seite bietet dann das Deaktivieren an. Deaktivierte Kategorien sind **nur im UI** nicht mehr wählbar — die API akzeptiert sie weiterhin, sonst schlüge jede Änderung an einem alten Termin unerwartet fehl (Muster `confirm_required`)
- **Termine gehören optional Personen** (`calendar_event_person`, n:m auf `app_user`): keine Zeile = Haushaltstermin. Die Zuordnung steuert **keine Sichtbarkeit** — jeder sieht alles, inklusive Wandtablet. Der Filter auf der Kalenderseite zeigt bei einer gewählten Person zusätzlich **immer** die Termine ohne Zuordnung, sonst verschwände die Müllabfuhr genau dann, wenn jemand auf sich selbst filtert. Das Erinnerungs-Event trägt `personIds` (stabil, zum Filtern) **und** `persons` (Anzeigenamen, zum Ansagen) — ein Flow, der auf den Anzeigenamen filtert, bräche beim nächsten Umbenennen still
- `GET /v1/users` liefert jedem angemeldeten Nutzer die schlanke Liste `{id, displayName, enabled}` für die Personenauswahl (`/v1/admin/users` ist ADMIN-only). `/v1/auth/me` enthält seitdem die eigene `id`; bei Anmeldung per Service-Token ist sie `null` und der Filter „Meine" entfällt
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md && git commit -m "docs(kalender): Personen-Zuordnung und Kategorie-Stammdaten festhalten"
```

---

## Abschluss

- [ ] **Vollständiger Backend-Lauf**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -f backend/pom.xml test
```

Erwartet: nur `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` scheitern (vorbestehend, lokale Datenbank).

- [ ] **Vollständiger Frontend-Lauf**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: genau die 3 Baseline-Fehlschläge.

- [ ] **Produktionsbuild des Frontends**

```bash
cd frontend && npx ng build --configuration production
```

Erwartet: Build erfolgreich — er fängt Typfehler, die die Tests nicht berühren.

Danach greift `superpowers:finishing-a-development-branch` für den Abschluss.
