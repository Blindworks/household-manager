# Modus-Schnellzugriff nach Uhrzeit — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Haus-Modus, für den ein Zeitfenster gepflegt ist, steht im Tablet-Dashboard während dieses Fensters direkt als Knopf neben der eingeklappten Modus-Leiste — und verschwindet, sobald er eingeschaltet ist.

**Architecture:** Neue Tabelle `mode_quick_access` (Modus-Entity-ID, Von-/Bis-Zeit, aktiv). `ModeQuickAccessResolver` ist die einzige Definition von „jetzt fällig" und reichert `GET /v1/modes` um ein `quickAccess`-Flag an; das Frontend rechnet nichts. Pflege über eine ADMIN-only CRUD-API `/v1/mode-quick-access` und eine Admin-Seite.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / MariaDB; Angular 19 standalone / SCSS / Karma-Jasmine.

**Spec:** `docs/superpowers/specs/2026-09-09-modus-schnellzugriff-design.md`

---

## Umgebung (vor dem ersten Task lesen)

**Backend-Tests** laufen aus `backend/`. Auf dieser Maschine zeigt `JAVA_HOME` auf JDK 17, das Projekt braucht 21:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Es gibt **kein** `mvnw`; `mvn` liegt auf dem PATH. **Vorbestehende, umgebungsbedingte Fehlschläge**, die nichts mit diesem Plan zu tun haben und ignoriert werden: `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` („Access denied for user 'root'@'localhost'"). Deshalb werden unten immer **einzelne** Testklassen gestartet, nie `mvn test` allein.

**Frontend-Tests** laufen aus `frontend/`:

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

**Vorbestehende Fehlschläge (Baseline: genau 3):** `AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`. Gelegentlich kommt eine Karma-Flake in `SmartDeviceListComponent` dazu — bei Verdacht den Lauf wiederholen. Nur **zusätzliche** Fails sind Regressionen.

**`dashboard.component.scss`** liegt bei ~27,9 kB und reißt damit die 16-kB-**Warn**schwelle des `anyComponentStyle`-Budgets. Das ist erwartetes Rauschen, kein Fehler. Die **Fehler**schwelle liegt bei 32 kB — dieser Plan fügt rund 20 Zeilen hinzu, das bleibt weit darunter.

**JPA-Repositories müssen in `com.household.manager.repository` liegen** — `JpaConfig` schränkt das Scanning auf dieses Paket ein. Ein Repository im Feature-Paket wird nicht gefunden.

---

## Dateiübersicht

**Backend — neu**

| Datei | Verantwortung |
|---|---|
| `backend/src/main/resources/db/changelog/changes/20260909-0053-create-mode-quick-access.xml` | Tabelle `mode_quick_access` |
| `backend/src/main/java/com/household/manager/model/entity/ModeQuickAccess.java` | JPA-Entity |
| `backend/src/main/java/com/household/manager/repository/ModeQuickAccessRepository.java` | Repository (Paket ist Pflicht, s. o.) |
| `backend/src/main/java/com/household/manager/mode/ModeQuickAccessResolver.java` | Einzige Definition von „jetzt fällig"; wirft nie |
| `backend/src/main/java/com/household/manager/mode/ModeQuickAccessDtos.java` | Request-/Response-Records |
| `backend/src/main/java/com/household/manager/mode/ModeQuickAccessService.java` | CRUD + Validierung + Audit |
| `backend/src/main/java/com/household/manager/mode/ModeQuickAccessController.java` | REST unter `/v1/mode-quick-access` |

**Backend — geändert**

| Datei | Änderung |
|---|---|
| `.../resources/db/changelog/db.changelog-master.xml` | `<include>` des neuen Changesets |
| `.../dto/ModeResponse.java` | Feld `quickAccess` |
| `.../entitystate/mapper/ModeResponseMapper.java` | Resolver injizieren, Flag setzen, Batch-Überladung |
| `.../entitystate/HouseModeQueryService.java` | Fenster einmal je Abruf laden |
| `.../security/SecurityConfig.java` | ADMIN-Matcher vor der generischen GET-Regel |
| `.../test/.../entitystate/HouseModeQueryServiceTest.java` | Konstruktor des Mappers hat ein Argument mehr |
| `.../test/.../security/SecurityRulesTest.java` | Rollenmatrix des neuen Pfades |

**Frontend — neu**

| Datei | Verantwortung |
|---|---|
| `frontend/src/app/models/mode-quick-access.model.ts` | Request-/Response-Typen |
| `frontend/src/app/services/mode-quick-access.service.ts` | REST-Service der Pflege-API |
| `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.{ts,html,scss,spec.ts}` | Admin-Seite |

**Frontend — geändert**

| Datei | Änderung |
|---|---|
| `frontend/src/app/models/mode.model.ts` | Feld `quickAccess` |
| `frontend/src/app/services/mode.service.spec.ts` | Fixture um `quickAccess` |
| `frontend/src/app/pages/dashboard/dashboard.component.ts` | Getter `quickAccessModes` |
| `frontend/src/app/pages/dashboard/dashboard.component.html` | Zeile mit Karte + Schnellzugriff |
| `frontend/src/app/pages/dashboard/dashboard.component.scss` | zwei neue Layout-Regeln |
| `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` | Fixtures + neue Suite |
| `frontend/src/app/app.routes.ts` | Route `admin/mode-quick-access` |
| `frontend/src/app/components/header/header.component.ts` | Menüpunkt |

---

## Task 1: Tabelle, Entity, Repository

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20260909-0053-create-mode-quick-access.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (ans Ende, vor `</databaseChangeLog>`)
- Create: `backend/src/main/java/com/household/manager/model/entity/ModeQuickAccess.java`
- Create: `backend/src/main/java/com/household/manager/repository/ModeQuickAccessRepository.java`

Dieser Task hat bewusst keinen Test: Schema, Entity und Repository-Interface tragen keine eigene Logik. Verifiziert wird über `mvn -q compile` und ab Task 2 durch die Tests, die auf diesen Typen sitzen.

- [ ] **Step 1: Changeset anlegen**

Erstelle `backend/src/main/resources/db/changelog/changes/20260909-0053-create-mode-quick-access.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">

    <changeSet id="20260909-0053-create-mode-quick-access" author="claude">
        <comment>
            Zeitfenster, in denen ein Haus-Modus im Tablet-Dashboard direkt als Knopf steht.
            entity_id ist eindeutig: ein Modus hat hoechstens ein Fenster. from_time gilt
            inklusive, to_time exklusiv; from_time > to_time ueberspannt Mitternacht.
        </comment>

        <createTable tableName="mode_quick_access">
            <column name="id" type="BIGINT" autoIncrement="true">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="entity_id" type="VARCHAR(255)">
                <constraints nullable="false" unique="true"
                             uniqueConstraintName="uk_mode_quick_access_entity_id"/>
            </column>
            <column name="from_time" type="TIME">
                <constraints nullable="false"/>
            </column>
            <column name="to_time" type="TIME">
                <constraints nullable="false"/>
            </column>
            <column name="active" type="BOOLEAN" defaultValueBoolean="true">
                <constraints nullable="false"/>
            </column>
        </createTable>

        <rollback>
            <dropTable tableName="mode_quick_access"/>
        </rollback>
    </changeSet>

</databaseChangeLog>
```

- [ ] **Step 2: Changeset einhängen**

In `backend/src/main/resources/db/changelog/db.changelog-master.xml` direkt **vor** der Zeile `</databaseChangeLog>` einfügen:

```xml
    <!-- Zeitgesteuerter Modus-Schnellzugriff im Tablet-Dashboard -->
    <include file="db/changelog/changes/20260909-0053-create-mode-quick-access.xml"/>
```

- [ ] **Step 3: Entity anlegen**

Erstelle `backend/src/main/java/com/household/manager/model/entity/ModeQuickAccess.java`:

```java
package com.household.manager.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/**
 * Zeitfenster, in dem ein Haus-Modus im Tablet-Dashboard direkt als Knopf steht.
 *
 * <p>Bewusst ohne Fremdschluessel auf {@code entity_states} (Muster
 * {@code entity_tile_visibility}): die Zugehoerigkeit haengt allein an der stabilen
 * Entity-ID. Ein Fenster fuer einen Modus, den es nicht mehr gibt, bleibt wirkungslos
 * stehen; die Admin-Seite macht das sichtbar, indem sie dann die rohe ID zeigt.
 */
@Entity
@Table(name = "mode_quick_access")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModeQuickAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_id", nullable = false, length = 255, unique = true)
    private String entityId;

    /** Beginn des Fensters, inklusive. */
    @Column(name = "from_time", nullable = false)
    private LocalTime fromTime;

    /** Ende des Fensters, exklusiv. Liegt es vor {@link #fromTime}, ueberspannt das Fenster Mitternacht. */
    @Column(name = "to_time", nullable = false)
    private LocalTime toTime;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
```

- [ ] **Step 4: Repository anlegen**

Erstelle `backend/src/main/java/com/household/manager/repository/ModeQuickAccessRepository.java`:

```java
package com.household.manager.repository;

import com.household.manager.model.entity.ModeQuickAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModeQuickAccessRepository extends JpaRepository<ModeQuickAccess, Long> {

    List<ModeQuickAccess> findAllByOrderByIdAsc();

    List<ModeQuickAccess> findByActiveTrue();

    Optional<ModeQuickAccess> findByEntityId(String entityId);
}
```

- [ ] **Step 5: Compile prüfen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile
```

Erwartet: kein Fehler (keine Ausgabe außer eventuellen Download-Zeilen).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/household/manager/model/entity/ModeQuickAccess.java backend/src/main/java/com/household/manager/repository/ModeQuickAccessRepository.java
git commit -m "feat(modes): Tabelle mode_quick_access fuer zeitgesteuerte Modus-Knoepfe"
```

---

## Task 2: ModeQuickAccessResolver

**Files:**
- Create: `backend/src/test/java/com/household/manager/mode/ModeQuickAccessResolverTest.java`
- Create: `backend/src/main/java/com/household/manager/mode/ModeQuickAccessResolver.java`

- [ ] **Step 1: Failing test schreiben**

Erstelle `backend/src/test/java/com/household/manager/mode/ModeQuickAccessResolverTest.java`:

```java
package com.household.manager.mode;

import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeQuickAccessResolverTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    private ModeQuickAccessRepository repository;

    /** Feste Uhr auf der gewuenschten lokalen Uhrzeit des 9. September 2026. */
    private ModeQuickAccessResolver resolverAt(String localTime) {
        Instant instant = java.time.LocalDate.of(2026, 9, 9)
                .atTime(LocalTime.parse(localTime))
                .atZone(BERLIN)
                .toInstant();
        return new ModeQuickAccessResolver(repository, Clock.fixed(instant, BERLIN));
    }

    private ModeQuickAccess window(String from, String to, boolean active) {
        return ModeQuickAccess.builder()
                .id(1L)
                .entityId(NACHTMODUS)
                .fromTime(LocalTime.parse(from))
                .toTime(LocalTime.parse(to))
                .active(active)
                .build();
    }

    @Test
    void meldetEinenModusInnerhalbEinesTagesfensters() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "18:00", true)));

        assertThat(resolverAt("12:00").dueEntityIds()).containsExactly(NACHTMODUS);
    }

    @Test
    void meldetNichtsVorUndNachEinemTagesfenster() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "18:00", true)));

        assertThat(resolverAt("07:59").dueEntityIds()).isEmpty();
        assertThat(resolverAt("18:00").dueEntityIds()).isEmpty();
    }

    /** Der Beginn gehoert zum Fenster, das Ende nicht — halboffenes Intervall [from, to). */
    @Test
    void behandeltDenBeginnInklusiveUndDasEndeExklusiv() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "18:00", true)));

        assertThat(resolverAt("08:00").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("17:59").dueEntityIds()).containsExactly(NACHTMODUS);
    }

    /** 20:00-06:00 ueberspannt Mitternacht: abends und frueh morgens faellig, mittags nicht. */
    @Test
    void beherrschtEinFensterUeberMitternacht() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("20:00", "06:00", true)));

        assertThat(resolverAt("20:00").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("23:59").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("05:59").dueEntityIds()).containsExactly(NACHTMODUS);
        assertThat(resolverAt("06:00").dueEntityIds()).isEmpty();
        assertThat(resolverAt("13:00").dueEntityIds()).isEmpty();
    }

    /**
     * Ein Fenster mit gleichem Beginn und Ende ist leer, nicht "immer". Die API laesst so
     * etwas nicht entstehen; eine von Hand eingetragene Zeile darf trotzdem keinen
     * Dauerknopf erzeugen.
     */
    @Test
    void behandeltGleichenBeginnUndEndeAlsLeeresFenster() {
        when(repository.findByActiveTrue()).thenReturn(List.of(window("08:00", "08:00", true)));

        assertThat(resolverAt("08:00").dueEntityIds()).isEmpty();
    }

    @Test
    void ignoriertDeaktivierteFenster() {
        when(repository.findByActiveTrue()).thenReturn(List.of());

        assertThat(resolverAt("12:00").dueEntityIds()).isEmpty();
    }

    /**
     * Wirft nie: der Resolver reichert nur die Modus-Antwort an. Ein Datenbankfehler darf
     * nicht die gesamte Modus-Leiste des Wandtablets in einen 500 kippen.
     */
    @Test
    void meldetBeiEinemDatenbankfehlerNichtsStattZuWerfen() {
        when(repository.findByActiveTrue()).thenThrow(new RuntimeException("DB weg"));

        Set<String> due = resolverAt("12:00").dueEntityIds();

        assertThat(due).isEmpty();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ModeQuickAccessResolverTest
```

Erwartet: Compile-Fehler „cannot find symbol: class ModeQuickAccessResolver".

- [ ] **Step 3: Resolver implementieren**

Erstelle `backend/src/main/java/com/household/manager/mode/ModeQuickAccessResolver.java`:

```java
package com.household.manager.mode;

import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Einzige Definition von „dieser Haus-Modus ist jetzt faellig" (Muster
 * {@code TractiveHomeResolver}, {@code PowerConsumerQueryService.findConsumer}).
 *
 * <p><b>Wirft nie.</b> Ein Fehler beim Lesen der Fenster ergibt „nichts ist faellig"
 * plus Warnung im Log. Der Resolver reichert nur die Modus-Antwort an — eine kaputte
 * Konfigurationstabelle darf nicht die gesamte Modus-Leiste des Wandtablets mit einem
 * 500 ausknipsen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModeQuickAccessResolver {

    private final ModeQuickAccessRepository repository;
    private final Clock clock;

    /** Entity-IDs der Modi, deren Fenster gerade offen ist. Nie {@code null}. */
    @Transactional(readOnly = true)
    public Set<String> dueEntityIds() {
        LocalTime now = LocalTime.now(clock);
        try {
            return repository.findByActiveTrue().stream()
                    .filter(window -> isWithin(window, now))
                    .map(ModeQuickAccess::getEntityId)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (Exception ex) {
            log.warn("Zeitfenster der Modus-Schnellzugriffe nicht lesbar, keiner gilt als faellig: {}",
                    ex.getMessage());
            return Set.of();
        }
    }

    /**
     * Halboffenes Intervall [from, to): der Beginn gehoert dazu, das Ende nicht.
     * Liegt {@code to} vor {@code from}, ueberspannt das Fenster Mitternacht.
     * Gleicher Beginn und gleiches Ende ergeben ein leeres Fenster.
     */
    private static boolean isWithin(ModeQuickAccess window, LocalTime now) {
        LocalTime from = window.getFromTime();
        LocalTime to = window.getToTime();
        if (from.equals(to)) {
            return false;
        }
        if (from.isBefore(to)) {
            return !now.isBefore(from) && now.isBefore(to);
        }
        return !now.isBefore(from) || now.isBefore(to);
    }
}
```

- [ ] **Step 4: Test laufen lassen, Erfolg bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ModeQuickAccessResolverTest
```

Erwartet: „Tests run: 7, Failures: 0, Errors: 0".

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/mode/ModeQuickAccessResolver.java backend/src/test/java/com/household/manager/mode/ModeQuickAccessResolverTest.java
git commit -m "feat(modes): Resolver fuer faellige Modus-Zeitfenster"
```

---

## Task 3: `quickAccess` an der Modus-API

**Files:**
- Create: `backend/src/test/java/com/household/manager/entitystate/mapper/ModeResponseMapperTest.java`
- Modify: `backend/src/main/java/com/household/manager/dto/ModeResponse.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/ModeResponseMapper.java`
- Modify: `backend/src/main/java/com/household/manager/entitystate/HouseModeQueryService.java`
- Modify: `backend/src/test/java/com/household/manager/entitystate/HouseModeQueryServiceTest.java` (Zeilen 31–34)

- [ ] **Step 1: Failing test schreiben**

Erstelle `backend/src/test/java/com/household/manager/entitystate/mapper/ModeResponseMapperTest.java`:

```java
package com.household.manager.entitystate.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.mode.ModeQuickAccessResolver;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeResponseMapperTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";

    @Mock
    private ModeQuickAccessResolver quickAccessResolver;

    private ModeResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ModeResponseMapper(new EntityStateResponseMapper(new ObjectMapper()), quickAccessResolver);
    }

    private EntityState nachtmodus() {
        return EntityState.builder()
                .entityId(NACHTMODUS)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef("nachtmodus")
                .friendlyName("Nachtmodus")
                .state("off")
                .attributes("{\"mode\":true,\"icon\":\"nights_stay\"}")
                .build();
    }

    @Test
    void markiertEinenFaelligenModusAlsSchnellzugriff() {
        when(quickAccessResolver.dueEntityIds()).thenReturn(Set.of(NACHTMODUS));

        ModeResponse response = mapper.toResponse(nachtmodus());

        assertThat(response.quickAccess()).isTrue();
        assertThat(response.entityId()).isEqualTo(NACHTMODUS);
        assertThat(response.icon()).isEqualTo("nights_stay");
    }

    @Test
    void laesstEinenNichtFaelligenModusOhneSchnellzugriff() {
        when(quickAccessResolver.dueEntityIds()).thenReturn(Set.of());

        assertThat(mapper.toResponse(nachtmodus()).quickAccess()).isFalse();
    }

    /**
     * Die Batch-Ueberladung bekommt die Fenster von aussen gereicht, damit ein Listenabruf
     * sie nur einmal laedt statt einmal je Modus.
     */
    @Test
    void nutztDieUebergebeneFenstermengeOhneDenResolverZuFragen() {
        ModeResponse response = mapper.toResponse(nachtmodus(), Set.of(NACHTMODUS));

        assertThat(response.quickAccess()).isTrue();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ModeResponseMapperTest
```

Erwartet: Compile-Fehler — der Mapper-Konstruktor nimmt noch ein Argument, `quickAccess()` gibt es nicht.

- [ ] **Step 3: `ModeResponse` erweitern**

Ersetze den Inhalt von `backend/src/main/java/com/household/manager/dto/ModeResponse.java`:

```java
package com.household.manager.dto;

import lombok.Builder;

/** API-Repräsentation eines Haus-Modus für die Modus-Leiste des Dashboards. */
@Builder
public record ModeResponse(
        String entityId,
        String displayName,
        String icon,
        String state,
        /**
         * True, wenn fuer diesen Modus gerade ein Zeitfenster offen ist. Das Tablet-Dashboard
         * zeigt ihn dann direkt als Knopf neben der eingeklappten Modus-Leiste.
         */
        boolean quickAccess
) {
}
```

- [ ] **Step 4: Mapper anpassen**

Ersetze den Inhalt von `backend/src/main/java/com/household/manager/entitystate/mapper/ModeResponseMapper.java`:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.dto.ModeResponse;
import com.household.manager.mode.ModeQuickAccessResolver;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Bildet eine Modus-Entity auf die API-{@link ModeResponse} ab. */
@Component
@RequiredArgsConstructor
public class ModeResponseMapper {

    private static final String DEFAULT_ICON = "flag";
    private static final String ATTR_ICON = "icon";

    private final EntityStateResponseMapper entityStateResponseMapper;
    private final ModeQuickAccessResolver quickAccessResolver;

    /** Einzelabbildung; fragt die offenen Zeitfenster selbst ab. */
    public ModeResponse toResponse(EntityState entity) {
        return toResponse(entity, quickAccessResolver.dueEntityIds());
    }

    /**
     * Abbildung mit bereits geladenen Fenstern. Ein Listenabruf reicht sie durch, damit die
     * Fenster einmal je Antwort geladen werden und nicht einmal je Modus.
     */
    public ModeResponse toResponse(EntityState entity, Set<String> dueEntityIds) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return ModeResponse.builder()
                .entityId(entity.getEntityId())
                .displayName(entityStateResponseMapper.displayName(entity))
                .icon(icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON)
                .state(entity.getState())
                .quickAccess(dueEntityIds.contains(entity.getEntityId()))
                .build();
    }
}
```

- [ ] **Step 5: `HouseModeQueryService` die Fenster einmal laden lassen**

In `backend/src/main/java/com/household/manager/entitystate/HouseModeQueryService.java`:

Import ergänzen (nach `import com.household.manager.entitystate.mapper.ModeResponseMapper;`):

```java
import com.household.manager.mode.ModeQuickAccessResolver;
```

und `import java.util.Set;` zu den `java.util`-Imports.

Feld ergänzen (nach `private final ModeResponseMapper modeResponseMapper;`):

```java
    private final ModeQuickAccessResolver quickAccessResolver;
```

Und `listModes()` ersetzen:

```java
    @Transactional(readOnly = true)
    public List<ModeResponse> listModes() {
        // Die offenen Zeitfenster einmal je Abruf laden, nicht einmal je Modus.
        Set<String> due = quickAccessResolver.dueEntityIds();
        return entityStateRepository
                .findByDomainAndSourceOrderByEntityIdAsc(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL)
                .stream()
                .filter(entity -> HouseModes.isMode(
                        entityStateResponseMapper.parseAttributes(entity.getAttributes())))
                .sorted(Comparator.comparingInt(this::catalogIndex))
                .map(entity -> modeResponseMapper.toResponse(entity, due))
                .toList();
    }
```

- [ ] **Step 6: Bestehenden `HouseModeQueryServiceTest` nachziehen**

Der Mapper- und der Service-Konstruktor haben je ein Argument mehr. In
`backend/src/test/java/com/household/manager/entitystate/HouseModeQueryServiceTest.java`:

Imports ergänzen:

```java
import com.household.manager.mode.ModeQuickAccessResolver;
import java.util.Set;
```

Mock-Feld ergänzen (nach dem vorhandenen `@Mock private EntityStateRepository entityStateRepository;`):

```java
    @Mock
    private ModeQuickAccessResolver quickAccessResolver;
```

`setUp()` (Zeilen 31–34) ersetzen:

```java
    @BeforeEach
    void setUp() {
        EntityStateResponseMapper entityMapper = new EntityStateResponseMapper(new ObjectMapper());
        // lenient(), weil nicht jeder Test dieser Klasse bis zur Abbildung kommt — mit
        // striktem Stubbing waere das sonst "unnecessary stubbing".
        lenient().when(quickAccessResolver.dueEntityIds()).thenReturn(Set.of());
        service = new HouseModeQueryService(entityStateRepository, entityMapper,
                new ModeResponseMapper(entityMapper, quickAccessResolver), quickAccessResolver);
    }
```

Dafür zusätzlich `import static org.mockito.Mockito.lenient;` ergänzen.

- [ ] **Step 7: Beide Tests laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest='ModeResponseMapperTest,HouseModeQueryServiceTest,SetModeToolTest'
```

Erwartet: alle grün. (`SetModeToolTest` baut `ModeResponse` über den Builder — das neue `boolean`-Feld bleibt dort auf `false` und bricht nichts; der Lauf belegt es.)

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/dto/ModeResponse.java backend/src/main/java/com/household/manager/entitystate backend/src/test/java/com/household/manager/entitystate
git commit -m "feat(modes): GET /v1/modes meldet faellige Schnellzugriffe"
```

---

## Task 4: DTOs und CRUD-Service

**Files:**
- Create: `backend/src/test/java/com/household/manager/mode/ModeQuickAccessServiceTest.java`
- Create: `backend/src/main/java/com/household/manager/mode/ModeQuickAccessDtos.java`
- Create: `backend/src/main/java/com/household/manager/mode/ModeQuickAccessService.java`

- [ ] **Step 1: Failing test schreiben**

Erstelle `backend/src/test/java/com/household/manager/mode/ModeQuickAccessServiceTest.java`:

```java
package com.household.manager.mode;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeQuickAccessServiceTest {

    private static final String NACHTMODUS = "input_boolean.manual_nachtmodus";

    @Mock
    private ModeQuickAccessRepository repository;
    @Mock
    private HouseModeQueryService houseModeQueryService;
    @Mock
    private AuditService auditService;

    private ModeQuickAccessService service;

    @BeforeEach
    void setUp() {
        service = new ModeQuickAccessService(repository, houseModeQueryService, auditService);
        lenient().when(houseModeQueryService.listModes()).thenReturn(List.of(
                ModeResponse.builder().entityId(NACHTMODUS).displayName("Nachtmodus")
                        .icon("nights_stay").state("off").quickAccess(false).build()));
    }

    private ModeQuickAccessDtos.Request request(String entityId, String from, String to) {
        return new ModeQuickAccessDtos.Request(entityId, LocalTime.parse(from), LocalTime.parse(to), true);
    }

    private ModeQuickAccess saved() {
        return ModeQuickAccess.builder().id(7L).entityId(NACHTMODUS)
                .fromTime(LocalTime.of(20, 0)).toTime(LocalTime.of(6, 0)).active(true).build();
    }

    @Test
    void legtEinFensterAnUndAuditiertEs() {
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.empty());
        when(repository.save(any())).thenReturn(saved());

        ModeQuickAccessDtos.Response response = service.create(request(NACHTMODUS, "20:00", "06:00"));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.displayName()).isEqualTo("Nachtmodus");
        verify(auditService).record("mode.quick-access.create", "Nachtmodus 20:00-06:00");
    }

    /** Das Dropdown der Admin-Seite kennt nur echte Modi; ueber die API kaeme sonst Unsinn durch. */
    @Test
    void lehntEineEntityAbDieKeinHausModusIst() {
        assertThatThrownBy(() -> service.create(request("switch.meross_kaffeemaschine", "20:00", "06:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kein Haus-Modus");
        verify(repository, never()).save(any());
    }

    /**
     * Gleicher Beginn und gleiches Ende sind mehrdeutig ("nie" oder "immer"?) und werden
     * deshalb an der API-Grenze abgelehnt statt im Resolver geraten.
     */
    @Test
    void lehntGleichenBeginnUndEndeAb() {
        assertThatThrownBy(() -> service.create(request(NACHTMODUS, "20:00", "20:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Beginn und Ende");
        verify(repository, never()).save(any());
    }

    @Test
    void lehntEinenFehlendenModusAb() {
        assertThatThrownBy(() -> service.create(request(null, "20:00", "06:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Modus");
    }

    @Test
    void lehntFehlendeZeitenAb() {
        ModeQuickAccessDtos.Request ohneEnde =
                new ModeQuickAccessDtos.Request(NACHTMODUS, LocalTime.of(20, 0), null, true);

        assertThatThrownBy(() -> service.create(ohneEnde))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Beginn und Ende");
    }

    /** Ein Modus hat hoechstens ein Fenster — sonst waere unklar, welches gilt. */
    @Test
    void lehntEinZweitesFensterFuerDenselbenModusAb() {
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.of(saved()));

        assertThatThrownBy(() -> service.create(request(NACHTMODUS, "08:00", "10:00")))
                .isInstanceOf(DuplicateEntityException.class);
        verify(repository, never()).save(any());
    }

    /** Beim Aendern darf die eigene Zeile nicht als Duplikat gelten. */
    @Test
    void aendertEinFensterUndErlaubtDabeiDieEigeneZeile() {
        when(repository.findById(7L)).thenReturn(Optional.of(saved()));
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.of(saved()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModeQuickAccessDtos.Response response = service.update(7L, request(NACHTMODUS, "21:00", "07:00"));

        assertThat(response.fromTime()).isEqualTo(LocalTime.of(21, 0));
        verify(auditService).record("mode.quick-access.update", "Nachtmodus 21:00-07:00");
    }

    @Test
    void meldetEineUnbekannteIdAlsNichtGefunden() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, request(NACHTMODUS, "20:00", "06:00")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void loeschtEinFensterUndAuditiertEs() {
        when(repository.findById(7L)).thenReturn(Optional.of(saved()));

        service.delete(7L);

        verify(repository).delete(any());
        verify(auditService).record("mode.quick-access.delete", "Nachtmodus 20:00-06:00");
    }

    /**
     * Ein Fenster fuer einen Modus, den es nicht mehr gibt, bleibt in der Liste sichtbar —
     * ohne Anzeigenamen. Wuerde es weggefiltert, waere es nicht mehr loeschbar.
     */
    @Test
    void listetEinFensterOhneZugehoerigenModusOhneAnzeigenamen() {
        ModeQuickAccess verwaist = ModeQuickAccess.builder().id(8L).entityId("input_boolean.manual_weg")
                .fromTime(LocalTime.of(1, 0)).toTime(LocalTime.of(2, 0)).active(true).build();
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(verwaist));

        List<ModeQuickAccessDtos.Response> list = service.list();

        assertThat(list).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.entityId()).isEqualTo("input_boolean.manual_weg");
                    assertThat(entry.displayName()).isNull();
                });
    }

    /** Ein fehlendes active-Feld heisst "aktiv" — wie der Default der Spalte. */
    @Test
    void behandeltEinFehlendesAktivFeldAlsAktiv() {
        when(repository.findByEntityId(NACHTMODUS)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ModeQuickAccessDtos.Request ohneAktiv =
                new ModeQuickAccessDtos.Request(NACHTMODUS, LocalTime.of(20, 0), LocalTime.of(6, 0), null);

        assertThat(service.create(ohneAktiv).active()).isTrue();
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ModeQuickAccessServiceTest
```

Erwartet: Compile-Fehler „cannot find symbol: class ModeQuickAccessDtos".

- [ ] **Step 3: DTOs anlegen**

Erstelle `backend/src/main/java/com/household/manager/mode/ModeQuickAccessDtos.java`:

```java
package com.household.manager.mode;

import com.household.manager.model.entity.ModeQuickAccess;

import java.time.LocalTime;

/**
 * Request-/Response-Records der Pflege-API der Modus-Zeitfenster.
 *
 * <p>Die Zeiten laufen als ISO-Uhrzeit ueber die Leitung ("20:00:00"). Ein HTML-Feld
 * {@code <input type="time">} sendet "20:00" — Jackson parst beides zu {@link LocalTime}.
 */
public final class ModeQuickAccessDtos {

    private ModeQuickAccessDtos() {
    }

    public record Request(String entityId, LocalTime fromTime, LocalTime toTime, Boolean active) {
    }

    /**
     * @param displayName Anzeigename des Modus, {@code null} wenn es zu der Entity-ID keinen
     *                    Haus-Modus (mehr) gibt. Die Admin-Seite zeigt dann die rohe ID.
     */
    public record Response(Long id, String entityId, String displayName,
                           LocalTime fromTime, LocalTime toTime, boolean active) {

        public static Response from(ModeQuickAccess window, String displayName) {
            return new Response(window.getId(), window.getEntityId(), displayName,
                    window.getFromTime(), window.getToTime(), window.isActive());
        }
    }
}
```

- [ ] **Step 4: Service implementieren**

Erstelle `backend/src/main/java/com/household/manager/mode/ModeQuickAccessService.java`:

```java
package com.household.manager.mode;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ModeQuickAccess;
import com.household.manager.repository.ModeQuickAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Pflegt die Zeitfenster, in denen ein Haus-Modus im Tablet-Dashboard direkt als Knopf
 * steht. Die Auswertung selbst gehoert dem {@link ModeQuickAccessResolver}.
 */
@Service
@RequiredArgsConstructor
public class ModeQuickAccessService {

    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ModeQuickAccessRepository repository;
    private final HouseModeQueryService houseModeQueryService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ModeQuickAccessDtos.Response> list() {
        Map<String, String> names = modeNames();
        return repository.findAllByOrderByIdAsc().stream()
                .map(window -> ModeQuickAccessDtos.Response.from(window, names.get(window.getEntityId())))
                .toList();
    }

    @Transactional
    public ModeQuickAccessDtos.Response create(ModeQuickAccessDtos.Request request) {
        // Einmal laden und weiterreichen: Validierung, Audit-Text und Antwort brauchen
        // dieselben Namen, drei getrennte Abfragen waeren reine Verschwendung.
        Map<String, String> names = modeNames();
        validate(request, names);
        if (repository.findByEntityId(request.entityId()).isPresent()) {
            throw new DuplicateEntityException(
                    "Fuer diesen Modus gibt es bereits ein Zeitfenster.");
        }
        ModeQuickAccess saved = repository.save(ModeQuickAccess.builder()
                .entityId(request.entityId())
                .fromTime(request.fromTime())
                .toTime(request.toTime())
                .active(activeOrDefault(request))
                .build());
        auditService.record("mode.quick-access.create", auditDetail(saved, names));
        return ModeQuickAccessDtos.Response.from(saved, names.get(saved.getEntityId()));
    }

    @Transactional
    public ModeQuickAccessDtos.Response update(Long id, ModeQuickAccessDtos.Request request) {
        Map<String, String> names = modeNames();
        validate(request, names);
        ModeQuickAccess window = findOrThrow(id);
        // Die eigene Zeile darf beim Aendern nicht als Duplikat gelten.
        Optional<ModeQuickAccess> other = repository.findByEntityId(request.entityId())
                .filter(existing -> !existing.getId().equals(id));
        if (other.isPresent()) {
            throw new DuplicateEntityException(
                    "Fuer diesen Modus gibt es bereits ein Zeitfenster.");
        }
        window.setEntityId(request.entityId());
        window.setFromTime(request.fromTime());
        window.setToTime(request.toTime());
        window.setActive(activeOrDefault(request));
        ModeQuickAccess saved = repository.save(window);
        auditService.record("mode.quick-access.update", auditDetail(saved, names));
        return ModeQuickAccessDtos.Response.from(saved, names.get(saved.getEntityId()));
    }

    @Transactional
    public void delete(Long id) {
        ModeQuickAccess window = findOrThrow(id);
        repository.delete(window);
        auditService.record("mode.quick-access.delete", auditDetail(window, modeNames()));
    }

    private ModeQuickAccess findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ModeQuickAccess", "id", id));
    }

    /** Anzeigenamen der bekannten Haus-Modi, nach Entity-ID. */
    private Map<String, String> modeNames() {
        return houseModeQueryService.listModes().stream()
                .collect(Collectors.toMap(ModeResponse::entityId, ModeResponse::displayName,
                        (first, second) -> first));
    }

    /** Fehlendes Feld heisst "aktiv" — wie der Default der Spalte (Muster Netzwerk-Geraete). */
    private boolean activeOrDefault(ModeQuickAccessDtos.Request request) {
        return request.active() == null || request.active();
    }

    private String auditDetail(ModeQuickAccess window, Map<String, String> names) {
        String name = Optional.ofNullable(names.get(window.getEntityId()))
                .orElse(window.getEntityId());
        return "%s %s-%s".formatted(name, AUDIT_TIME.format(window.getFromTime()),
                AUDIT_TIME.format(window.getToTime()));
    }

    /**
     * Die Zeit-Checks stehen vor dem Modus-Check: ein offensichtlich falsches Formular soll
     * die naheliegende Meldung bekommen.
     */
    private void validate(ModeQuickAccessDtos.Request request, Map<String, String> knownModes) {
        if (request.entityId() == null || request.entityId().isBlank()) {
            throw new IllegalArgumentException("Es ist kein Modus ausgewaehlt.");
        }
        if (request.fromTime() == null || request.toTime() == null) {
            throw new IllegalArgumentException("Beginn und Ende muessen gesetzt sein.");
        }
        if (request.fromTime().equals(request.toTime())) {
            throw new IllegalArgumentException(
                    "Beginn und Ende duerfen nicht gleich sein — das Fenster waere leer.");
        }
        if (!knownModes.containsKey(request.entityId())) {
            throw new IllegalArgumentException(
                    "%s ist kein Haus-Modus.".formatted(request.entityId()));
        }
    }
}
```

- [ ] **Step 5: Test laufen lassen, Erfolg bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ModeQuickAccessServiceTest
```

Erwartet: „Tests run: 11, Failures: 0, Errors: 0".

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/mode backend/src/test/java/com/household/manager/mode
git commit -m "feat(modes): CRUD-Service fuer Modus-Zeitfenster"
```

---

## Task 5: Controller und Security

**Files:**
- Create: `backend/src/main/java/com/household/manager/mode/ModeQuickAccessController.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Modify: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

- [ ] **Step 1: Failing test schreiben**

Füge in `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` am Ende der Klasse (vor der schließenden `}`) ein:

```java
    /**
     * Die Zeitfenster der Modus-Schnellzugriffe sind ADMIN-only, auch lesend: das Wandtablet
     * braucht die Konfiguration nie — es bekommt das fertige quickAccess-Flag ueber
     * GET /v1/modes. Der Matcher steht deshalb VOR der generischen Regel GET /v1/** -> KIOSK;
     * genau das belegen diese Tests. KIOSK und MEMBER muessen es je aus eigenem Test belegen,
     * sonst faellt ein zu laxer Matcher fuer die jeweils andere Rolle niemandem auf.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieModusZeitfensterNichtLesen() throws Exception {
        mockMvc.perform(get("/v1/mode-quick-access")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfDieModusZeitfensterNichtLesen() throws Exception {
        mockMvc.perform(get("/v1/mode-quick-access")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinModusZeitfensterAnlegen() throws Exception {
        mockMvc.perform(post("/v1/mode-quick-access").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeinModusZeitfensterAnlegen() throws Exception {
        mockMvc.perform(post("/v1/mode-quick-access").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void memberDarfKeinModusZeitfensterAendernOderLoeschen() throws Exception {
        mockMvc.perform(put("/v1/mode-quick-access/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/mode-quick-access/1").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminKommtAnDieModusZeitfensterVorbei() throws Exception {
        // Kein ModeQuickAccessController im Slice: 404 statt 403 belegt, dass die Regel durchlaesst.
        mockMvc.perform(get("/v1/mode-quick-access")).andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/mode-quick-access").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/v1/mode-quick-access/1").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/v1/mode-quick-access/1").with(csrf()))
                .andExpect(status().isNotFound());
    }

    /** Das Flag selbst haengt an GET /v1/modes und bleibt fuer das Wandtablet lesbar. */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieModiWeiterhinLesen() throws Exception {
        mockMvc.perform(get("/v1/modes")).andExpect(status().isNotFound());
    }
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SecurityRulesTest
```

Erwartet: Die vier neuen „darf nicht"-Tests schlagen fehl — ohne eigenen Matcher fällt `GET /v1/mode-quick-access` auf die generische KIOSK-Regel (404 statt des erwarteten 403), und POST/PUT/DELETE landen bei `anyRequest -> MEMBER`.

- [ ] **Step 3: Security-Regel ergänzen**

In `backend/src/main/java/com/household/manager/security/SecurityConfig.java` den bestehenden ADMIN-`requestMatchers`-Aufruf erweitern. Ersetze:

```java
                        .requestMatchers("/v1/flows/**", "/v1/admin/**", "/v1/vision/**",
                                "/v1/alexa/auth/**", "/v1/tractive/login", "/v1/tractive/logout",
                                "/v1/tractive/home-settings", "/v1/presence/settings",
                                // Gasfaktor: liegt unter /v1/utility-prices/**, dessen GET weiter unten
                                // KIOSK ist — muss deshalb hier vor dieser Regel stehen.
                                "/v1/utility-prices/settings").hasRole("ADMIN")
```

durch:

```java
                        .requestMatchers("/v1/flows/**", "/v1/admin/**", "/v1/vision/**",
                                "/v1/alexa/auth/**", "/v1/tractive/login", "/v1/tractive/logout",
                                "/v1/tractive/home-settings", "/v1/presence/settings",
                                // Gasfaktor: liegt unter /v1/utility-prices/**, dessen GET weiter unten
                                // KIOSK ist — muss deshalb hier vor dieser Regel stehen.
                                "/v1/utility-prices/settings",
                                // Zeitfenster der Modus-Schnellzugriffe: auch LESEN ist ADMIN.
                                // Das Wandtablet braucht die Konfiguration nie, es bekommt das
                                // fertige quickAccess-Flag ueber GET /v1/modes. Die Position vor
                                // der generischen GET-Regel weiter unten ist deshalb tragend.
                                // Beide Formen, damit auch der Pfad ohne Unterpfad sicher trifft.
                                "/v1/mode-quick-access", "/v1/mode-quick-access/**").hasRole("ADMIN")
```

- [ ] **Step 4: Test laufen lassen, Erfolg bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SecurityRulesTest
```

Erwartet: alle Tests grün, inklusive der sieben neuen.

- [ ] **Step 5: Controller anlegen**

Erstelle `backend/src/main/java/com/household/manager/mode/ModeQuickAccessController.java`:

```java
package com.household.manager.mode;

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
 * Pflege-API der Modus-Zeitfenster (ADMIN-only, siehe SecurityConfig).
 *
 * <p>Bewusst ein eigener Pfad statt {@code /v1/modes/quick-access}: unter {@code /v1/modes}
 * steht bereits {@code {entityId}}, und eine Pfad-Kollision dieser Art hat sich bei den
 * Zaehlerstaenden ({@code /series} vs. {@code /{type}}) schon einmal als Falle erwiesen.
 */
@RestController
@RequestMapping("/v1/mode-quick-access")
@RequiredArgsConstructor
public class ModeQuickAccessController {

    private final ModeQuickAccessService service;

    @GetMapping
    public ResponseEntity<List<ModeQuickAccessDtos.Response>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<ModeQuickAccessDtos.Response> create(
            @RequestBody ModeQuickAccessDtos.Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ModeQuickAccessDtos.Response> update(
            @PathVariable Long id, @RequestBody ModeQuickAccessDtos.Request request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 6: Compile und Backend-Tests des Features**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest='ModeQuickAccess*Test,ModeResponseMapperTest,HouseModeQueryServiceTest,SecurityRulesTest'
```

Erwartet: alle grün.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/mode/ModeQuickAccessController.java backend/src/main/java/com/household/manager/security/SecurityConfig.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(modes): ADMIN-API /v1/mode-quick-access"
```

---

## Task 6: Frontend-Modelle und Service

**Files:**
- Modify: `frontend/src/app/models/mode.model.ts`
- Modify: `frontend/src/app/services/mode.service.spec.ts` (Fixture Zeilen 11–16)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (Fixtures Zeilen ~673, ~1786, ~1794)
- Create: `frontend/src/app/models/mode-quick-access.model.ts`
- Create: `frontend/src/app/services/mode-quick-access.service.ts`

`quickAccess` wird ein **Pflichtfeld** von `ModeEntity` — ein optionales Feld würde verdecken, dass das Backend es nicht liefert. Dadurch brechen die drei Test-Fixtures, die `ModeEntity`-Objektliterale bauen; sie werden in diesem Task mit angepasst.

- [ ] **Step 1: `ModeEntity` erweitern**

Ersetze den Inhalt von `frontend/src/app/models/mode.model.ts`:

```ts
/** Haus-Modus der Dashboard-Modus-Leiste (INPUT_BOOLEAN mit Modus-Marker). */
export interface ModeEntity {
  entityId: string;
  displayName: string;
  /** Material-Symbols-Name. */
  icon: string;
  /** "on" oder "off". */
  state: string;
  /**
   * True, wenn das Backend fuer diesen Modus gerade ein Zeitfenster offen sieht. Das
   * Tablet-Dashboard zeigt ihn dann direkt als Knopf neben der eingeklappten Leiste.
   */
  quickAccess: boolean;
}
```

- [ ] **Step 2: Vorhandene Fixtures nachziehen**

In `frontend/src/app/services/mode.service.spec.ts` das Literal `const mode: ModeEntity = { … }` um `quickAccess: false` ergänzen:

```ts
  const mode: ModeEntity = {
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off',
    quickAccess: false
  };
```

In `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` in **allen drei** Factories (`mode`, `toniAllein`, `nachtmodus`) jeweils `quickAccess: false,` **vor** der `...overrides`-Zeile einfügen. Beispiel für die erste:

```ts
  const mode = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off',
    quickAccess: false,
    ...overrides
  });
```

- [ ] **Step 3: Modell der Pflege-API anlegen**

Erstelle `frontend/src/app/models/mode-quick-access.model.ts`:

```ts
/**
 * Zeitfenster, in dem ein Haus-Modus im Tablet-Dashboard direkt als Knopf steht.
 *
 * Die Zeiten sind ISO-Uhrzeiten. Das Backend liefert "20:00:00", ein
 * `<input type="time">` sendet "20:00" — beides ist gueltig.
 */
export interface ModeQuickAccess {
  id: number;
  entityId: string;
  /** Anzeigename des Modus; null, wenn es zu der Entity-ID keinen Modus (mehr) gibt. */
  displayName: string | null;
  fromTime: string;
  toTime: string;
  active: boolean;
}

export interface ModeQuickAccessRequest {
  entityId: string;
  fromTime: string;
  toTime: string;
  active: boolean;
}
```

- [ ] **Step 4: Service anlegen**

Erstelle `frontend/src/app/services/mode-quick-access.service.ts`:

```ts
import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ModeQuickAccess, ModeQuickAccessRequest } from '../models/mode-quick-access.model';

/** REST-Service für die Zeitfenster der Modus-Schnellzugriffe (ADMIN-only). */
@Injectable({ providedIn: 'root' })
export class ModeQuickAccessService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/mode-quick-access';

  list(): Observable<ModeQuickAccess[]> {
    return this.http.get<ModeQuickAccess[]>(this.baseUrl);
  }

  create(request: ModeQuickAccessRequest): Observable<ModeQuickAccess> {
    return this.http.post<ModeQuickAccess>(this.baseUrl, request);
  }

  update(id: number, request: ModeQuickAccessRequest): Observable<ModeQuickAccess> {
    return this.http.put<ModeQuickAccess>(`${this.baseUrl}/${id}`, request);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
```

- [ ] **Step 5: Bestehende Tests laufen lassen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/services/mode.service.spec.ts
```

Erwartet: alle Tests dieser Datei grün (kein Compile-Fehler mehr durch das neue Pflichtfeld).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/models frontend/src/app/services/mode-quick-access.service.ts frontend/src/app/services/mode.service.spec.ts frontend/src/app/pages/dashboard/dashboard.component.spec.ts
git commit -m "feat(modes): Frontend-Modelle und Service fuer den Modus-Schnellzugriff"
```

---

## Task 7: Schnellzugriff im Dashboard

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` (neue Suite am Dateiende)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts` (nach `get activeModes()`, ~Zeile 629)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html` (Block ab `<div class="lumina__modes-area">`, ~Zeile 404)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss` (nach `.lumina__modes-area`, ~Zeile 1518)

- [ ] **Step 1: Failing tests schreiben**

Füge am **Ende** von `frontend/src/app/pages/dashboard/dashboard.component.spec.ts` an:

```ts
/**
 * Der Schnellzugriff zeigt einen faelligen Modus direkt neben der eingeklappten
 * Modus-Leiste. `viewMode` wird hier nicht gemockt: der echte ViewModeService liest
 * localStorage beim Erzeugen, deshalb wird der Schluessel vor jedem Test entfernt und
 * die Tablet-Ansicht ueber `componentInstance.viewMode.toggle()` eingeschaltet — genau
 * wie in der bestehenden Ansichtsmodus-Suite.
 */
describe('DashboardComponent (Modus-Schnellzugriff)', () => {
  let modeServiceSpy: jasmine.SpyObj<ModeService>;

  const nachtmodus = (overrides: Partial<ModeEntity> = {}): ModeEntity => ({
    entityId: 'input_boolean.manual_nachtmodus',
    displayName: 'Nachtmodus',
    icon: 'nights_stay',
    state: 'off',
    quickAccess: true,
    ...overrides
  });

  beforeEach(async () => {
    localStorage.removeItem('household-manager-view-mode');

    modeServiceSpy = jasmine.createSpyObj('ModeService', ['getModes', 'toggle']);
    modeServiceSpy.getModes.and.returnValue(of([nachtmodus()]));
    modeServiceSpy.toggle.and.returnValue(of(nachtmodus({ state: 'on' })));

    const switchSpy = jasmine.createSpyObj('SwitchService', ['getSwitches', 'toggle']);
    switchSpy.getSwitches.and.returnValue(of([]));

    const weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(of(null));

    const energySpy = jasmine.createSpyObj('EnergyLiveService', ['getLiveStream', 'getStatusStream', 'disconnect']);
    energySpy.getLiveStream.and.returnValue(of(null));
    energySpy.getStatusStream.and.returnValue(of('connected'));

    const ankerSpy = jasmine.createSpyObj('AnkerSolixService', ['getLiveStream', 'disconnectLive']);
    ankerSpy.getLiveStream.and.returnValue(of(null));

    const temperatureSpy = jasmine.createSpyObj('TemperatureService', ['getCurrent', 'getSensorSeries']);
    temperatureSpy.getCurrent.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ModeService, useValue: modeServiceSpy },
        { provide: SwitchService, useValue: switchSpy },
        { provide: WeatherService, useValue: weatherSpy },
        { provide: EnergyLiveService, useValue: energySpy },
        { provide: AnkerSolixService, useValue: ankerSpy },
        { provide: TemperatureService, useValue: temperatureSpy }
      ]
    }).compileComponents();
  });

  afterAll(() => localStorage.removeItem('household-manager-view-mode'));

  /** Startet das Dashboard in der Tablet-Ansicht. */
  function tabletFixture(): ComponentFixture<DashboardComponent> {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.componentInstance.viewMode.toggle();
    fixture.detectChanges();
    return fixture;
  }

  function quickButtons(fixture: ComponentFixture<DashboardComponent>): HTMLElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.lumina__modes-quick .lumina__mode'));
  }

  it('zeigt einen faelligen, ausgeschalteten Modus als Schnellzugriff', fakeAsync(() => {
    const fixture = tabletFixture();

    const buttons = quickButtons(fixture);
    expect(buttons.length).toBe(1);
    expect(buttons[0].textContent).toContain('Nachtmodus');

    discardPeriodicTasks();
  }));

  it('zeigt keinen Schnellzugriff, sobald der Modus an ist', fakeAsync(() => {
    modeServiceSpy.getModes.and.returnValue(of([nachtmodus({ state: 'on' })]));
    const fixture = tabletFixture();

    expect(quickButtons(fixture).length).toBe(0);

    discardPeriodicTasks();
  }));

  it('zeigt keinen Schnellzugriff, wenn kein Fenster offen ist', fakeAsync(() => {
    modeServiceSpy.getModes.and.returnValue(of([nachtmodus({ quickAccess: false })]));
    const fixture = tabletFixture();

    expect(quickButtons(fixture).length).toBe(0);

    discardPeriodicTasks();
  }));

  it('zeigt keinen Schnellzugriff in der Website-Ansicht', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.viewMode.isTabletView()).toBeFalse();
    expect(quickButtons(fixture).length).toBe(0);

    discardPeriodicTasks();
  }));

  /** Ausgeklappt steht der Modus ohnehin in der Leiste — doppelt braucht ihn niemand. */
  it('zeigt keinen Schnellzugriff bei ausgeklappter Modus-Leiste', fakeAsync(() => {
    const fixture = tabletFixture();
    fixture.componentInstance.toggleModesBar();
    fixture.detectChanges();

    expect(fixture.componentInstance.modesExpanded).toBeTrue();
    expect(quickButtons(fixture).length).toBe(0);

    discardPeriodicTasks();
  }));

  /**
   * Der Knopf liegt NEBEN der Karte, nicht in ihr: ein Klick darf den Modus schalten,
   * ohne die Leiste aufzuklappen.
   */
  it('schaltet per Klick, ohne die Leiste aufzuklappen', fakeAsync(() => {
    const fixture = tabletFixture();

    quickButtons(fixture)[0].click();
    tick();
    fixture.detectChanges();

    expect(modeServiceSpy.toggle).toHaveBeenCalledWith('input_boolean.manual_nachtmodus');
    expect(fixture.componentInstance.modesExpanded).toBeFalse();

    discardPeriodicTasks();
  }));
});
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/dashboard/dashboard.component.spec.ts
```

Erwartet: Die drei „zeigt einen Schnellzugriff"/„schaltet per Klick"-Tests schlagen fehl (`buttons.length` ist 0, `toggle` nicht aufgerufen); die „zeigt keinen"-Tests sind trivial grün, weil es das Markup noch gar nicht gibt.

- [ ] **Step 3: Getter in der Komponente ergänzen**

In `frontend/src/app/pages/dashboard/dashboard.component.ts` direkt **nach** dem bestehenden `get activeModes()` einfügen:

```ts
  /**
   * Modi, die gerade per Schnellzugriff neben der eingeklappten Leiste stehen.
   *
   * Vier Bedingungen, alle bewusst: nur im Tablet-Modus (die Browser-Ansicht bleibt
   * unveraendert), nur bei eingeklappter Leiste (ausgeklappt stuende der Modus doppelt),
   * nur wenn das Backend sein Zeitfenster fuer offen haelt, und nur solange er AUS ist —
   * eingeschaltet hat der Knopf seinen Zweck erfuellt, und der aktive Modus erscheint
   * ohnehin als Symbol in der eingeklappten Karte.
   *
   * Ob ein Fenster offen ist, entscheidet allein das Backend (`quickAccess`); hier wird
   * keine Uhrzeit ausgewertet.
   */
  get quickAccessModes(): ModeEntity[] {
    if (!this.viewMode.isTabletView() || this.modesExpanded) {
      return [];
    }
    return this.modes.filter(mode => mode.quickAccess && mode.state !== 'on');
  }
```

- [ ] **Step 4: Markup ergänzen**

In `frontend/src/app/pages/dashboard/dashboard.component.html` den Block ersetzen, der mit `<div class="lumina__modes-area">` beginnt. Das Ergebnis (die Karte selbst bleibt unverändert, sie bekommt nur einen Zeilen-Wrapper und einen Nachbarn):

```html
    <div class="lumina__modes-area">
      <!-- Zeile aus eingeklappter Karte und Schnellzugriff. Der Schnellzugriff ist
           bewusst ein GESCHWISTER der Karte, kein Kind: als Kind wuerde jeder Tipp
           darauf zusaetzlich die Leiste aufklappen. -->
      <div class="lumina__modes-row">
        <!-- Klappt exakt wie die Tuerschloss- und die Toni-Kachel: die ganze
             Flaeche ist das Klickziel, die Symbole der aktiven Modi bleiben
             stehen, alles andere faehrt seitlich und in der Hoehe ein. Die
             Knoepfe darin muessen das Klick-Ereignis stoppen, sonst klappt die
             Leiste beim Schalten wieder zu. -->
        <div class="lumina-card lumina__secured lumina__modes-card"
             [class.lumina__secured--expanded]="modesExpanded"
             [class.lumina__modes-card--active]="activeModes.length > 0"
             role="button"
             tabindex="0"
             [attr.aria-expanded]="modesExpanded"
             [attr.aria-label]="modesToggleLabel"
             (click)="toggleModesBar()"
             (keydown.enter)="toggleModesBar()"
             (keydown.space)="$event.preventDefault(); toggleModesBar()">
          <div class="lumina__secured-icon lumina__modes-icons">
            <span class="material-symbols-outlined" *ngFor="let mode of activeModes">{{ modeIcon(mode) }}</span>
            <span class="material-symbols-outlined" *ngIf="activeModes.length === 0">tune</span>
          </div>
          <div class="lumina__lock-collapsible" [attr.aria-hidden]="!modesExpanded">
            <div class="lumina__lock-collapsible-inner">
              <div class="lumina__modes">
                <button
                  *ngFor="let mode of modes; let i = index"
                  type="button"
                  class="lumina-card lumina__mode"
                  [ngClass]="'lumina__mode--' + modeTone(i)"
                  [class.lumina__mode--active]="mode.state === 'on'"
                  [class.lumina__mode--pending]="pendingModeIds.has(mode.entityId)"
                  [attr.tabindex]="modesExpanded ? null : -1"
                  (click)="$event.stopPropagation(); toggleMode(mode)"
                >
                  <span class="lumina__mode-icon">
                    <span class="material-symbols-outlined">{{ modeIcon(mode) }}</span>
                  </span>
                  <span class="lumina__mode-label">{{ mode.displayName }}</span>
                </button>
                <!-- Reboot ist eine Aktion, kein Modus: fester Button mit Bestaetigungsdialog -->
                <button
                  type="button"
                  class="lumina-card lumina__mode lumina__mode--error lumina__mode--reboot"
                  [class.lumina__mode--pending]="rebootInProgress"
                  [attr.tabindex]="modesExpanded ? null : -1"
                  (click)="$event.stopPropagation(); openRebootDialog()"
                >
                  <span class="lumina__mode-icon">
                    <span class="material-symbols-outlined">restart_alt</span>
                  </span>
                  <span class="lumina__mode-label">Reboot</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <div class="lumina__modes-quick" *ngIf="quickAccessModes.length > 0">
          <button
            *ngFor="let mode of quickAccessModes"
            type="button"
            class="lumina-card lumina__mode"
            [class.lumina__mode--pending]="pendingModeIds.has(mode.entityId)"
            (click)="toggleMode(mode)"
          >
            <span class="lumina__mode-icon">
              <span class="material-symbols-outlined">{{ modeIcon(mode) }}</span>
            </span>
            <span class="lumina__mode-label">{{ mode.displayName }}</span>
          </button>
        </div>
      </div>
      <p *ngIf="modeError" class="lumina__mode-error">{{ modeError }}</p>
    </div>
```

- [ ] **Step 5: Styles ergänzen**

In `frontend/src/app/pages/dashboard/dashboard.component.scss` direkt **nach** dem Block `.lumina__modes-area { … }` einfügen:

```scss
// Karte und Schnellzugriff stehen nebeneinander; die Flaeche darum ist bewusst
// kein Klickziel, damit ein Tipp auf den Schnellzugriff die Leiste nicht aufklappt.
.lumina__modes-row {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 100%;
}

// Mehrere gleichzeitig faellige Modi scrollen seitwaerts statt umzubrechen: ein
// Umbruch hier stiehlt dem darunterliegenden Inhalt Hoehe (dieselbe Lehre wie bei
// .lumina__viewbar, als der fuenfte Eintrag die Leiste umbrechen liess).
.lumina__modes-quick {
  display: flex;
  flex-wrap: nowrap;
  gap: 12px;
  overflow-x: auto;
}
```

- [ ] **Step 6: Tests laufen lassen, Erfolg bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/dashboard/dashboard.component.spec.ts
```

Erwartet: alle Tests der Datei grün, inklusive der sechs neuen.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/dashboard
git commit -m "feat(dashboard): faelliger Modus als Schnellzugriff neben der Modus-Leiste"
```

---

## Task 8: Admin-Seite

**Files:**
- Create: `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.spec.ts`
- Create: `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.ts`
- Create: `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.html`
- Create: `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.scss`
- Modify: `frontend/src/app/app.routes.ts` (vor der Route `path: 'admin'`)
- Modify: `frontend/src/app/components/header/header.component.ts` (Admin-`children`)

- [ ] **Step 1: Failing test schreiben**

Erstelle `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.spec.ts`:

```ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AdminModeQuickAccessComponent } from './admin-mode-quick-access.component';
import { ModeQuickAccess } from '../../models/mode-quick-access.model';
import { ModeEntity } from '../../models/mode.model';

const WINDOWS_URL = '/api/v1/mode-quick-access';
const MODES_URL = '/api/v1/modes';

const NACHTMODUS: ModeEntity = {
  entityId: 'input_boolean.manual_nachtmodus',
  displayName: 'Nachtmodus',
  icon: 'nights_stay',
  state: 'off',
  quickAccess: false
};

const ABWESEND: ModeEntity = {
  entityId: 'input_boolean.manual_abwesend',
  displayName: 'Abwesend',
  icon: 'exit_to_app',
  state: 'off',
  quickAccess: false
};

const NACHT_FENSTER: ModeQuickAccess = {
  id: 1,
  entityId: 'input_boolean.manual_nachtmodus',
  displayName: 'Nachtmodus',
  fromTime: '20:00:00',
  toTime: '06:00:00',
  active: true
};

describe('AdminModeQuickAccessComponent', () => {
  let fixture: ComponentFixture<AdminModeQuickAccessComponent>;
  let httpMock: HttpTestingController;
  let el: HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminModeQuickAccessComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    fixture = TestBed.createComponent(AdminModeQuickAccessComponent);
    httpMock = TestBed.inject(HttpTestingController);
    el = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => httpMock.verify());

  /**
   * Startet die Seite und beantwortet beide Abrufe. Das `whenStable()` ist nicht optional:
   * innerhalb eines `<form>` registriert NgForm jedes NgModel erst in einem Microtask —
   * vorher veraendert ein `input`-Ereignis aus dem Test das Formular nicht.
   */
  async function loadWith(windows: ModeQuickAccess[], modes: ModeEntity[] = [NACHTMODUS, ABWESEND]) {
    fixture.detectChanges();
    httpMock.expectOne(WINDOWS_URL).flush(windows);
    httpMock.expectOne(MODES_URL).flush(modes);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return Array.from(el.querySelectorAll('.admin-mode-quick-access__table tbody tr'));
  }

  function setInput(name: string, value: string): void {
    const input = el.querySelector(`[name="${name}"]`) as HTMLInputElement | HTMLSelectElement;
    input.value = value;
    input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
    fixture.detectChanges();
  }

  it('zeigt die gepflegten Zeitfenster', async () => {
    await loadWith([NACHT_FENSTER]);

    expect(el.textContent).toContain('Nachtmodus');
    expect(el.textContent).toContain('20:00');
    expect(el.textContent).toContain('06:00');
  });

  it('bietet die Haus-Modi zur Auswahl an', async () => {
    await loadWith([]);

    const options = Array.from(el.querySelectorAll('[name="entityId"] option'))
      .map(option => option.textContent?.trim());
    expect(options).toContain('Nachtmodus');
    expect(options).toContain('Abwesend');
  });

  it('legt ein Zeitfenster mit den Formularwerten an', async () => {
    await loadWith([]);

    setInput('entityId', 'input_boolean.manual_nachtmodus');
    setInput('fromTime', '20:00');
    setInput('toTime', '06:00');

    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    const created = httpMock.expectOne(WINDOWS_URL);
    expect(created.request.method).toBe('POST');
    expect(created.request.body).toEqual({
      entityId: 'input_boolean.manual_nachtmodus',
      fromTime: '20:00',
      toTime: '06:00',
      active: true
    });
    created.flush(NACHT_FENSTER);
    httpMock.expectOne(WINDOWS_URL).flush([NACHT_FENSTER]);
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS, ABWESEND]);
  });

  it('lehnt ein Speichern ohne Modus ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('fromTime', '20:00');
    setInput('toTime', '06:00');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(WINDOWS_URL);
    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Es ist kein Modus ausgewählt.');
  });

  it('lehnt gleichen Beginn und gleiches Ende ohne Anfrage ab', async () => {
    await loadWith([]);

    setInput('entityId', 'input_boolean.manual_nachtmodus');
    setInput('fromTime', '20:00');
    setInput('toTime', '20:00');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    httpMock.expectNone(WINDOWS_URL);
    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Beginn und Ende');
  });

  /**
   * Der Server liest ein fehlendes `active` als „aktiv". Ein Teil-PUT wuerde ein
   * deaktiviertes Fenster also stillschweigend reaktivieren.
   */
  it('schaltet ein Fenster mit vollstaendigem Request aktiv/inaktiv', async () => {
    await loadWith([NACHT_FENSTER]);

    (rows()[0].querySelector('.admin-mode-quick-access__toggle-active') as HTMLButtonElement).click();

    const update = httpMock.expectOne(`${WINDOWS_URL}/1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).toEqual({
      entityId: 'input_boolean.manual_nachtmodus',
      fromTime: '20:00:00',
      toTime: '06:00:00',
      active: false
    });
    update.flush({ ...NACHT_FENSTER, active: false });
    httpMock.expectOne(WINDOWS_URL).flush([{ ...NACHT_FENSTER, active: false }]);
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS, ABWESEND]);
  });

  it('loescht ein Fenster nach Bestaetigung', async () => {
    await loadWith([NACHT_FENSTER]);
    spyOn(window, 'confirm').and.returnValue(true);

    (rows()[0].querySelector('.admin-mode-quick-access__delete') as HTMLButtonElement).click();

    const deleted = httpMock.expectOne(`${WINDOWS_URL}/1`);
    expect(deleted.request.method).toBe('DELETE');
    deleted.flush(null);
    httpMock.expectOne(WINDOWS_URL).flush([]);
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS, ABWESEND]);
  });

  /** Ein Fenster ohne zugehoerigen Modus bleibt sichtbar — sonst waere es nicht loeschbar. */
  it('zeigt ein verwaistes Fenster mit seiner rohen Entity-ID', async () => {
    await loadWith([{ ...NACHT_FENSTER, id: 5, entityId: 'input_boolean.manual_weg', displayName: null }]);

    expect(el.textContent).toContain('input_boolean.manual_weg');
  });

  it('zeigt einen Serverfehler beim Anlegen an', async () => {
    await loadWith([]);

    setInput('entityId', 'input_boolean.manual_nachtmodus');
    setInput('fromTime', '20:00');
    setInput('toTime', '06:00');
    (el.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    httpMock.expectOne(WINDOWS_URL).flush(
      { message: 'Fuer diesen Modus gibt es bereits ein Zeitfenster.' },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('bereits ein Zeitfenster');
  });

  it('zeigt einen Fehler, wenn das Laden fehlschlaegt', () => {
    fixture.detectChanges();
    httpMock.expectOne(WINDOWS_URL)
      .flush({ message: 'Datenbank nicht erreichbar.' }, { status: 500, statusText: 'Error' });
    httpMock.expectOne(MODES_URL).flush([NACHTMODUS]);
    fixture.detectChanges();

    expect(el.querySelector('.admin-mode-quick-access__error')?.textContent)
      .toContain('Datenbank nicht erreichbar.');
    expect(el.querySelector('.admin-mode-quick-access__table')).toBeFalsy();
  });
});
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.spec.ts
```

Erwartet: Compile-Fehler „Cannot find module './admin-mode-quick-access.component'".

- [ ] **Step 3: Komponente anlegen**

Erstelle `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.ts`:

```ts
import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ModeQuickAccessService } from '../../services/mode-quick-access.service';
import { ModeService } from '../../services/mode.service';
import { ModeQuickAccess, ModeQuickAccessRequest } from '../../models/mode-quick-access.model';
import { ModeEntity } from '../../models/mode.model';

/** Zustand des Anlege-/Bearbeiten-Formulars. */
interface WindowFormState {
  /** null = Anlegen, sonst die Id des bearbeiteten Fensters. */
  id: number | null;
  entityId: string;
  fromTime: string;
  toTime: string;
  active: boolean;
}

function emptyForm(): WindowFormState {
  return { id: null, entityId: '', fromTime: '20:00', toTime: '06:00', active: true };
}

/**
 * Admin-Seite „Modus-Schnellzugriff": pflegt die Zeitfenster, in denen ein Haus-Modus im
 * Tablet-Dashboard direkt als Knopf steht. Muster und Interaktionsform an der Admin-Seite
 * „Netzwerk-Geräte" ausgerichtet.
 */
@Component({
  selector: 'app-admin-mode-quick-access',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-mode-quick-access.component.html',
  styleUrl: './admin-mode-quick-access.component.scss'
})
export class AdminModeQuickAccessComponent implements OnInit {
  private readonly api = inject(ModeQuickAccessService);
  private readonly modeApi = inject(ModeService);

  readonly windows = signal<ModeQuickAccess[]>([]);
  /** Auswahl des Dropdowns; leer, wenn die Modi nicht geladen werden konnten. */
  readonly modes = signal<ModeEntity[]>([]);
  /** Nur der erste Abruf blendet die Tabelle aus; spaetere lassen sie stehen. */
  readonly loading = signal(true);
  /** Bei fehlgeschlagenem Laden bleibt die Tabelle verborgen — eine leere Liste loege. */
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  form: WindowFormState = emptyForm();

  ngOnInit(): void {
    this.load();
    this.loadModes();
  }

  /** Laedt die Fensterliste neu. `afterLoad` laeuft auch im Fehlerfall. */
  load(afterLoad?: () => void): void {
    this.api.list().subscribe({
      next: windows => {
        this.windows.set(windows);
        this.loadFailed.set(false);
        this.loading.set(false);
        afterLoad?.();
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set(this.messageFrom(error));
        afterLoad?.();
      }
    });
  }

  /**
   * Laedt die Haus-Modi fuer das Dropdown. Ein Fehlschlag blockiert die Pflege nicht:
   * die Liste bleibt sichtbar, nur die Auswahl ist leer.
   */
  private loadModes(): void {
    this.modeApi.getModes().subscribe({
      next: modes => this.modes.set(modes),
      error: () => this.modes.set([])
    });
  }

  get editing(): boolean {
    return this.form.id !== null;
  }

  /** Anzeigename eines Fensters; ohne zugehoerigen Modus die rohe Entity-ID. */
  label(window: ModeQuickAccess): string {
    return window.displayName ?? window.entityId;
  }

  /** "20:00:00" -> "20:00"; ein `<input type="time">` erwartet die kurze Form. */
  shortTime(time: string): string {
    return time.slice(0, 5);
  }

  startEdit(window: ModeQuickAccess): void {
    this.errorMessage.set(null);
    this.form = {
      id: window.id,
      entityId: window.entityId,
      fromTime: this.shortTime(window.fromTime),
      toTime: this.shortTime(window.toTime),
      active: window.active
    };
  }

  resetForm(): void {
    this.form = emptyForm();
    this.errorMessage.set(null);
  }

  save(): void {
    if (!this.form.entityId) {
      this.errorMessage.set('Es ist kein Modus ausgewählt.');
      return;
    }
    if (!this.form.fromTime || !this.form.toTime) {
      this.errorMessage.set('Beginn und Ende müssen gesetzt sein.');
      return;
    }
    if (this.form.fromTime === this.form.toTime) {
      this.errorMessage.set('Beginn und Ende dürfen nicht gleich sein — das Fenster wäre leer.');
      return;
    }
    const request: ModeQuickAccessRequest = {
      entityId: this.form.entityId,
      fromTime: this.form.fromTime,
      toTime: this.form.toTime,
      active: this.form.active
    };
    const id = this.form.id;
    this.saving.set(true);
    this.errorMessage.set(null);
    const call = id === null ? this.api.create(request) : this.api.update(id, request);
    call.subscribe({
      next: () => this.load(() => {
        this.saving.set(false);
        this.resetForm();
      }),
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(this.messageFrom(error));
      }
    });
  }

  setActive(window: ModeQuickAccess, active: boolean): void {
    this.errorMessage.set(null);
    this.api.update(window.id, this.requestFrom(window, active)).subscribe({
      next: () => {
        // Steht dasselbe Fenster gerade im Formular, muss der Schalter dort mitwandern.
        if (this.form.id === window.id) {
          this.form.active = active;
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  remove(window: ModeQuickAccess): void {
    if (!confirm(`Zeitfenster für „${this.label(window)}“ endgültig löschen?`)) {
      return;
    }
    this.errorMessage.set(null);
    this.api.remove(window.id).subscribe({
      next: () => {
        // Stand das geloeschte Fenster im Formular, ist dessen Id jetzt tot.
        if (this.form.id === window.id) {
          this.resetForm();
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  /**
   * Vollstaendiger Request aus einem bestehenden Fenster, mit ausgetauschtem `active`.
   * Der Server liest ein fehlendes `active` als „aktiv" — ein Teil-PUT wuerde ein
   * deaktiviertes Fenster also stillschweigend wieder aktivieren.
   */
  private requestFrom(window: ModeQuickAccess, active: boolean): ModeQuickAccessRequest {
    return {
      entityId: window.entityId,
      fromTime: window.fromTime,
      toTime: window.toTime,
      active
    };
  }

  private messageFrom(error: HttpErrorResponse): string {
    return error.error?.message ?? 'Fehler bei der Netzwerk-Kommunikation.';
  }
}
```

- [ ] **Step 4: Template anlegen**

Erstelle `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.html`:

```html
<div class="admin-mode-quick-access">
  <h1>Modus-Schnellzugriff</h1>

  <p class="admin-mode-quick-access__hint">
    Ein Modus mit Zeitfenster steht im Tablet-Dashboard während dieser Zeit direkt als Knopf
    neben der eingeklappten Modus-Leiste — und verschwindet, sobald er eingeschaltet ist.
    Liegt das Ende vor dem Beginn, überspannt das Fenster Mitternacht (20:00–06:00).
    Der Beginn zählt mit, das Ende nicht. Ein deaktiviertes Fenster wirkt nicht, bleibt aber
    angelegt. Ein Wechsel wird am Tablet innerhalb von 30 Sekunden sichtbar.
  </p>

  @if (errorMessage()) {
    <div class="admin-mode-quick-access__error" role="alert">{{ errorMessage() }}</div>
  }

  <form class="admin-mode-quick-access__form" (ngSubmit)="save()">
    <h2 class="admin-mode-quick-access__form-title">
      {{ editing ? 'Zeitfenster bearbeiten' : 'Neues Zeitfenster' }}
    </h2>

    <div class="admin-mode-quick-access__fields">
      <label class="admin-mode-quick-access__field">
        <span>Modus</span>
        <select name="entityId" [(ngModel)]="form.entityId">
          <option value="">— bitte wählen —</option>
          @for (mode of modes(); track mode.entityId) {
            <option [value]="mode.entityId">{{ mode.displayName }}</option>
          }
        </select>
      </label>

      <label class="admin-mode-quick-access__field admin-mode-quick-access__field--narrow">
        <span>Von</span>
        <input type="time" name="fromTime" [(ngModel)]="form.fromTime">
      </label>

      <label class="admin-mode-quick-access__field admin-mode-quick-access__field--narrow">
        <span>Bis</span>
        <input type="time" name="toTime" [(ngModel)]="form.toTime">
      </label>

      <label class="admin-mode-quick-access__checkbox">
        <input type="checkbox" name="active" [(ngModel)]="form.active">
        <span>Aktiv</span>
      </label>
    </div>

    <div class="admin-mode-quick-access__form-actions">
      <button type="submit" [disabled]="saving()">
        {{ saving() ? 'Speichert …' : (editing ? 'Änderungen speichern' : 'Anlegen') }}
      </button>
      @if (editing) {
        <button type="button" class="admin-mode-quick-access__secondary ghost" (click)="resetForm()">
          Abbrechen
        </button>
      }
    </div>
  </form>

  @if (loading()) {
    <p>Wird geladen …</p>
  } @else if (loadFailed()) {
    <!-- Keine Tabelle bei Ladefehler: eine leere Liste saehe aus wie „es gibt keine". -->
    <p>Die Zeitfenster konnten nicht geladen werden. Bitte die Seite neu laden.</p>
  } @else {
    <table class="admin-mode-quick-access__table">
      <thead>
        <tr>
          <th scope="col">Modus</th>
          <th scope="col">Von</th>
          <th scope="col">Bis</th>
          <th scope="col">Aktiv</th>
          <th scope="col"><span class="admin-mode-quick-access__sr-only">Aktionen</span></th>
        </tr>
      </thead>
      <tbody>
        @for (window of windows(); track window.id) {
          <tr [class.admin-mode-quick-access__row--inactive]="!window.active">
            <td>{{ label(window) }}</td>
            <td>{{ shortTime(window.fromTime) }}</td>
            <td>{{ shortTime(window.toTime) }}</td>
            <td>{{ window.active ? 'Ja' : 'Nein' }}</td>
            <td class="admin-mode-quick-access__actions">
              <button type="button" (click)="startEdit(window)">Bearbeiten</button>
              <button
                type="button"
                class="admin-mode-quick-access__toggle-active ghost"
                (click)="setActive(window, !window.active)">
                {{ window.active ? 'Deaktivieren' : 'Aktivieren' }}
              </button>
              <button
                type="button"
                class="admin-mode-quick-access__delete danger"
                (click)="remove(window)">
                Löschen
              </button>
            </td>
          </tr>
        } @empty {
          <tr><td colspan="5">Noch keine Zeitfenster angelegt.</td></tr>
        }
      </tbody>
    </table>
  }
</div>
```

- [ ] **Step 5: Styles anlegen**

Erstelle `frontend/src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.scss`:

```scss
.admin-mode-quick-access {
  padding: var(--spacing-lg);

  &__hint {
    max-width: 70ch;
    margin-bottom: var(--spacing-md);
    color: var(--color-gray);
    font-size: 0.9rem;
  }

  &__error {
    margin-bottom: var(--spacing-md);
    padding: var(--spacing-sm);
    border: 1px solid rgba(239, 68, 68, 0.25);
    border-radius: var(--radius-sm, 6px);
    background: rgba(239, 68, 68, 0.1);
    color: var(--color-error);
  }

  &__form {
    margin-bottom: var(--spacing-lg);
    padding: var(--spacing-md);
    border: 1px solid var(--color-light-gray);
    border-radius: var(--radius-sm, 6px);
  }

  &__form-title {
    margin: 0 0 var(--spacing-sm);
    font-size: 1.1rem;
  }

  &__fields {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: var(--spacing-md);
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    font-size: 0.85rem;

    input,
    select {
      padding: var(--spacing-xs) var(--spacing-sm);
      border: 1px solid var(--color-light-gray);
      border-radius: var(--radius-sm, 6px);
      font-size: 0.95rem;
    }

    &--narrow input {
      width: 7rem;
    }
  }

  &__checkbox {
    display: flex;
    align-items: center;
    gap: var(--spacing-xs);
    padding-bottom: var(--spacing-xs);
    font-size: 0.9rem;
  }

  &__form-actions {
    display: flex;
    gap: var(--spacing-sm);
    margin-top: var(--spacing-md);
  }

  /*
   * Die Button-Optik kommt aus dem globalen Admin-Scope in styles.scss
   * (default = primaer, .ghost, .danger); hier stehen nur noch Layout-Regeln.
   */

  &__table {
    width: 100%;
    border-collapse: collapse;

    th,
    td {
      padding: var(--spacing-sm);
      border-bottom: 1px solid var(--color-light-gray);
      text-align: left;
      vertical-align: middle;
    }
  }

  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--spacing-xs);
  }

  /** Deaktivierte Fenster bleiben sichtbar, treten aber zurueck. */
  &__row--inactive {
    opacity: 0.55;
  }

  &__sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
  }
}
```

- [ ] **Step 6: Tests laufen lassen, Erfolg bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/admin-mode-quick-access/admin-mode-quick-access.component.spec.ts
```

Erwartet: alle zehn Tests grün.

- [ ] **Step 7: Route eintragen**

In `frontend/src/app/app.routes.ts` **vor** dem Eintrag mit `path: 'admin'` einfügen:

```ts
  {
    path: 'admin/mode-quick-access',
    loadComponent: () => import('./pages/admin-mode-quick-access/admin-mode-quick-access.component')
      .then(m => m.AdminModeQuickAccessComponent),
    canActivate: [adminGuard],
    title: 'Modus-Schnellzugriff - Household Manager'
  },
```

- [ ] **Step 8: Menüpunkt eintragen**

In `frontend/src/app/components/header/header.component.ts` im `children`-Array der Admin-Gruppe nach dem Eintrag `{ path: '/admin/presence', label: 'Anwesenheit', minRole: 'ADMIN' }` ergänzen (dabei das Komma an der Vorzeile nicht vergessen):

```ts
        { path: '/admin/mode-quick-access', label: 'Modus-Schnellzugriff', minRole: 'ADMIN' }
```

- [ ] **Step 9: Vollständigen Frontend-Lauf machen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: genau die 3 bekannten Baseline-Fails (`AppComponent` ×2, `HeroComponent`), sonst grün. Bei einer `SmartDeviceListComponent`-Flake den Lauf wiederholen.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/app/pages/admin-mode-quick-access frontend/src/app/app.routes.ts frontend/src/app/components/header/header.component.ts
git commit -m "feat(admin): Seite zur Pflege der Modus-Zeitfenster"
```

---

## Task 9: Dokumentation und Gesamtverifikation

**Files:**
- Modify: `CLAUDE.md` (neuer Abschnitt)

- [ ] **Step 1: Backend-Tests des Features komplett laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest='ModeQuickAccess*Test,ModeResponseMapperTest,HouseModeQueryServiceTest,SetModeToolTest,SecurityRulesTest'
```

Erwartet: alle grün.

- [ ] **Step 2: Produktionsbuild des Frontends prüfen**

```bash
cd frontend && npx ng build --configuration production
```

Erwartet: Build läuft durch. Die Budget-**Warnung** zu `dashboard.component.scss` (~28 kB gegen die 16-kB-Warnschwelle) und die Leaflet-CommonJS-Warnung sind erwartete Baseline. Ein Budget-**ERROR** wäre neu — dann in `frontend/angular.json` die `anyComponentStyle`-Fehlerschwelle prüfen.

- [ ] **Step 3: CLAUDE.md ergänzen**

Füge in `CLAUDE.md` nach dem Abschnitt „### Tablet-Ansichten (Unterseiten des Wandtablets)" einen neuen Abschnitt ein:

```markdown
### Modus-Schnellzugriff nach Uhrzeit
- Ein Haus-Modus, für den ein Zeitfenster gepflegt ist, steht im **Tablet-Dashboard** während dieses Fensters direkt als Knopf neben der eingeklappten Modus-Leiste — und verschwindet, sobald er **an** ist (der aktive Modus erscheint ohnehin als Symbol in der Karte). Spec: `docs/superpowers/specs/2026-09-09-modus-schnellzugriff-design.md`
- **`ModeQuickAccessResolver` ist die einzige Definition von „jetzt fällig"** (Muster `TractiveHomeResolver`): `GET /v1/modes` reichert jeden Modus um `quickAccess` an, das Frontend rechnet **keine** Uhrzeit. Preis: der Wechsel greift erst mit dem nächsten 30-s-Modus-Refresh, und maßgeblich ist die Uhr des **Servers**
- **Der Resolver wirft nie:** ein DB-Fehler ergibt „nichts ist fällig" plus Warnung. Er reichert nur an — eine kaputte Konfigurationstabelle darf die Modus-Leiste des Wandtablets nicht mit einem 500 ausknipsen
- Tabelle `mode_quick_access` (Changeset `20260909-0053`): `entity_id` **UNIQUE** (ein Modus, ein Fenster), `from_time` **inklusive**, `to_time` **exklusiv**, `from > to` überspannt Mitternacht. **`from == to` wird mit 400 abgelehnt** — sonst wären „nie" und „immer" nicht unterscheidbar; der Resolver wertet es zusätzlich als leeres Fenster, damit eine von Hand eingetragene Zeile keinen Dauerknopf erzeugt
- **Kein Fremdschlüssel auf `entity_states`** (Muster `entity_tile_visibility`); stattdessen prüft die API gegen `HouseModeQueryService`, dass die Entity-ID ein Haus-Modus ist. **Kehrseite:** ein Fenster für einen aus `HouseModes.CATALOG` entfernten Modus bleibt wirkungslos stehen — die Admin-Liste zeigt dann die rohe Entity-ID statt eines Namens, damit es auffällt und löschbar bleibt
- Pflege unter Admin → Modus-Schnellzugriff (Route `admin/mode-quick-access`), API **`/v1/mode-quick-access`** — bewusst ein eigener Pfad statt `/v1/modes/quick-access`, weil unter `/v1/modes` schon `{entityId}` steht (die `/series`-vs-`/{type}`-Falle der Zählerstände). **Auch das Lesen ist ADMIN**, als methodenloser Matcher **vor** der generischen `GET /v1/**`-Regel; `SecurityRulesTest` hält beide Richtungen fest. Audit: `mode.quick-access.create/update/delete`
- Der Schnellzugriff erscheint nur, wenn **alle vier** Bedingungen gelten: Fenster offen, Modus aus, Tablet-Ansicht, Leiste eingeklappt (ausgeklappt stünde er doppelt). Das Markup ist ein **Geschwister** der Modus-Karte in `lumina__modes-area`, nicht ihr Kind — als Kind würde jeder Tipp die Leiste aufklappen; ein Test hält das fest. Geklickt wird das bestehende `toggleMode()`, damit die Aktivierungs-Checks von „Abwesend"/„Toni allein" auch hier greifen
- Mehrere gleichzeitig fällige Modi scrollen seitwärts statt umzubrechen (`flex-wrap: nowrap`) — dieselbe Lehre wie bei `.lumina__viewbar`
- **Ein Fenster je Modus:** zwei getrennte Zeiträume für denselben Modus (morgens und abends) sind nicht ausdrückbar, das UNIQUE hält das fest
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Modus-Schnellzugriff in CLAUDE.md"
```

- [ ] **Step 5: Rollout beschreiben (kein Code)**

Nach dem Deploy:

1. Auf `admin/mode-quick-access` ein Fenster für „Nachtmodus" anlegen, z. B. 20:00 bis 06:00.
2. Am Wandtablet prüfen: Der Knopf steht ab 20:00 neben der Modus-Karte, verschwindet beim Einschalten und kommt beim Ausschalten innerhalb des Fensters zurück (jeweils bis zu 30 Sekunden Verzögerung).

---

## Was dieser Plan bewusst NICHT tut

- **Keine Sonnenuntergangs-Kopplung.** Das Projekt hat keine Sonnenstandsdaten; die Fenster sind feste Uhrzeiten.
- **Kein Schnellzugriff in der Browser-Ansicht.** Die Website-Seite bleibt unverändert.
- **Kein Aufräumjob für verwaiste Fenster.** Ein Fenster ohne Modus bleibt sichtbar und muss von Hand gelöscht werden — automatisches Löschen würde eine Konfiguration wegwerfen, die nach einem Katalog-Rückbau wieder gültig werden kann.
