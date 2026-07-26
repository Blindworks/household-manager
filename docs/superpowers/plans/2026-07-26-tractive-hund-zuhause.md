# Entität „Hund ist zu Hause" (Tractive) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine Entität `binary_sensor.tractive_<trackerId>_home` (`on`/`off`), die beantwortet, ob der Hund zu Hause ist — plus Badge auf der Hundetracker-Seite und Kachel auf dem Dashboard.

**Architecture:** Eine neue Klasse `TractiveHomeResolver` ist die einzige Definition von „zu Hause". Sie liefert `Optional<HomeVerdict>`; `Optional.empty()` heißt *keine Aussage* und führt dazu, dass gar kein Entity-Update gemeldet wird (der Entity-State-Layer behält damit den letzten Wert). `TractiveEntityMapper` und `TractivePetService` fragen dieselbe Instanz, damit Kachel und Flow-Trigger nie auseinanderlaufen. Die Besonderheit: Der Tracker wird zu Hause ausgeschaltet, was die API nicht meldet — deshalb schließt eine Heuristik aus „Positionsbericht still + gesunder Akku + Heimnähe" auf „zu Hause".

**Tech Stack:** Java 21, Spring Boot 3.4.1, Lombok, JUnit 5, Mockito, AssertJ; Angular 19 standalone, SCSS, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-07-26-tractive-hund-zuhause-design.md`

---

## Vorbedingungen (einmal pro Session)

Backend-Kommandos laufen aus `backend/`, und **`JAVA_HOME` muss vorher auf JDK 21 zeigen** — der Maschinen-Default ist JDK 17 und `mvn` bricht sonst sofort ab:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Bekannte, **vorbestehende** Fehlschläge, die nichts mit dieser Arbeit zu tun haben und ignoriert werden:

- Backend: `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` („Access denied for user 'root'@'localhost'" — lokale Test-DB fehlt). Deshalb werden unten immer gezielte `-Dtest=…`-Läufe verwendet.
- Frontend: 4 dauerhaft rote Tests (`HeaderComponent should create`, `AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`). Baseline ist „4 FAILED", nur *zusätzliche* Fehlschläge sind Regressionen.

## Dateiübersicht

**Neu (Backend):**
- `backend/src/main/java/com/household/manager/tractive/HomeVerdict.java` — Ergebnistyp: war das Tier zu Hause und woraus folgt das
- `backend/src/main/java/com/household/manager/tractive/TractiveHomeResolver.java` — die gesamte Entscheidungslogik, sonst nirgends
- `backend/src/test/java/com/household/manager/tractive/TractiveHomeResolverTest.java`

**Geändert (Backend):**
- `TractiveProperties.java` — drei neue Properties
- `backend/src/main/resources/application.properties` — dieselben drei
- `entitystate/mapper/TractiveEntityMapper.java` — Home-Entität + `isHomeEntity`
- `TractivePollingService.java` — Home-Entität von `markUnavailable()` ausnehmen
- `dto/TractivePetDto.java` + `TractivePetService.java` — Feld `atHome`
- zugehörige Tests

**Geändert (Frontend):**
- `models/tractive.model.ts` — Feld `atHome`
- `pages/pets/pets.component.html` / `.scss` — Badge
- `pages/dashboard/dashboard.component.ts` / `.html` / `.scss` — Kachel

**Geändert (Doku/Deployment):**
- `docker-compose.yml` — `TRACTIVE_HOME_LAT` / `TRACTIVE_HOME_LON`
- `CLAUDE.md` — Abschnitt „Tractive-Hundetracker"

---

## Task 1: Properties für die Home-Heuristik

Reine Konfiguration; die Werte werden ab Task 2 benutzt. Eigener Commit, damit Task 2 sich ganz auf die Logik konzentriert.

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractiveProperties.java`
- Modify: `backend/src/main/resources/application.properties:153-155`

- [ ] **Step 1: Properties ergänzen**

In `TractiveProperties.java` den Block ab `private String homeZoneName = "Zuhause";` ersetzen durch:

```java
    private String homeZoneName = "Zuhause";

    /**
     * Weiter Radius um den Home-Punkt fuer die Ausschalt-Heuristik. Der letzte
     * Positionsbericht vor dem Ausschalten stammt oft noch von unterwegs, deshalb
     * grosszuegiger als {@link #homeRadiusMeters}.
     */
    private double homeArrivalRadiusMeters = 500;

    /**
     * Ab wann ein ausbleibender Positionsbericht als "Tracker ausgeschaltet" gilt.
     * Konservativ gewaehlt: das reale Melde-Intervall im Tractive-Sparmodus ist
     * nicht verifiziert.
     */
    private long poweredOffAfterMinutes = 60;

    /**
     * Mindest-Akkustand, ab dem Stille als bewusstes Ausschalten gilt. Darunter ist
     * "Akku unterwegs leergelaufen" die wahrscheinlichere Erklaerung.
     */
    private int poweredOffMinBatteryPercent = 15;
```

- [ ] **Step 2: `application.properties` ergänzen**

Nach `tractive.home-radius-meters=100` (Zeile 155) anfügen:

```properties
tractive.home-arrival-radius-meters=500
tractive.powered-off-after-minutes=60
tractive.powered-off-min-battery-percent=15
```

- [ ] **Step 3: Kompilieren**

Run: `mvn -q compile`
Expected: BUILD SUCCESS, keine Ausgabe

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractiveProperties.java backend/src/main/resources/application.properties
git commit -m "feat(tractive): Properties fuer die Home-Erkennung"
```

---

## Task 2: `HomeVerdict` und `TractiveHomeResolver`

Das Herzstück. Alle sechs Regeln des Specs in einer Klasse, vollständig testgetrieben.

**Files:**
- Create: `backend/src/main/java/com/household/manager/tractive/HomeVerdict.java`
- Create: `backend/src/main/java/com/household/manager/tractive/TractiveHomeResolver.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveHomeResolverTest.java`

- [ ] **Step 1: Ergebnistyp `HomeVerdict` anlegen**

Der Ergebnistyp enthält keine Logik, nur Daten plus benannte Fabrikmethoden — deshalb kein eigener Test; er wird durch die Resolver-Tests vollständig abgedeckt.

`backend/src/main/java/com/household/manager/tractive/HomeVerdict.java`:

```java
package com.household.manager.tractive;

/**
 * Urteil des {@link TractiveHomeResolver}: ist das Tier zu Hause, und woraus folgt das?
 *
 * <p>{@code basis} und {@code stale} sind kein Beiwerk – sie machen im Entity-Viewer und
 * im Flow-Debug nachvollziehbar, warum die Entitaet {@code on} sagt, obwohl es gar keinen
 * frischen GPS-Fix gibt.
 */
public record HomeVerdict(
        boolean atHome,
        Basis basis,
        boolean stale,
        Double distanceMeters,
        Long positionAgeMinutes
) {

    /** Woraus das Urteil folgt. */
    public enum Basis {
        /** Der Tracker laedt – eindeutig zu Hause, unabhaengig von der Position. */
        CHARGING,
        /** Abstand des letzten Positionsberichts zum Home-Punkt. */
        POSITION,
        /** Geschlossen aus: Bericht still + gesunder Akku + Heimnaehe. */
        POWERED_OFF
    }

    public static HomeVerdict charging() {
        return new HomeVerdict(true, Basis.CHARGING, false, null, null);
    }

    public static HomeVerdict fromPosition(boolean atHome, double distanceMeters,
                                           Long positionAgeMinutes, boolean stale) {
        return new HomeVerdict(atHome, Basis.POSITION, stale, distanceMeters, positionAgeMinutes);
    }

    public static HomeVerdict poweredOff(double distanceMeters, long positionAgeMinutes) {
        return new HomeVerdict(true, Basis.POWERED_OFF, true, distanceMeters, positionAgeMinutes);
    }
}
```

- [ ] **Step 2: Den fehlschlagenden Test schreiben**

`backend/src/test/java/com/household/manager/tractive/TractiveHomeResolverTest.java`:

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TractiveHomeResolverTest {

    /** Home-Punkt aller Tests. */
    private static final double HOME_LAT = 48.2082;
    private static final double HOME_LON = 16.3738;
    /** Rund 300 m noerdlich des Home-Punkts: ausserhalb 100 m, innerhalb 500 m. */
    private static final double NEAR_LAT = 48.2109;
    /** Rund 10 km entfernt. */
    private static final double FAR_LAT = 48.3000;
    private static final double FAR_LON = 16.5000;

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    private TractiveProperties propertiesWithHome() {
        TractiveProperties properties = new TractiveProperties();
        properties.setHomeLatitude(HOME_LAT);
        properties.setHomeLongitude(HOME_LON);
        properties.setHomeRadiusMeters(100);
        properties.setHomeArrivalRadiusMeters(500);
        properties.setPoweredOffAfterMinutes(60);
        properties.setPoweredOffMinBatteryPercent(15);
        return properties;
    }

    /** Positionsbericht mit einem Alter in Minuten relativ zu {@link #NOW}. */
    private TractivePositionDto positionAgedMinutes(double latitude, double longitude, long ageMinutes) {
        return new TractivePositionDto(List.of(latitude, longitude), 12.0, "GPS",
                NOW.minusSeconds(ageMinutes * 60).getEpochSecond());
    }

    private TractivePetSnapshot snapshot(TractivePositionDto position, TractiveHardwareDto hardware) {
        return new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                position, hardware, List.of());
    }

    private Optional<HomeVerdict> resolve(TractiveProperties properties, TractivePetSnapshot snapshot) {
        return new TractiveHomeResolver(properties).resolve(snapshot, NOW);
    }

    // --- Regel 1: ohne Home-Koordinaten keine Aussage -----------------------------------

    @Test
    void withoutHomeCoordinatesThereIsNoVerdict() {
        var snapshot = snapshot(positionAgedMinutes(HOME_LAT, HOME_LON, 1),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        assertThat(resolve(new TractiveProperties(), snapshot)).isEmpty();
    }

    // --- Regel 2: Laden gewinnt ---------------------------------------------------------

    @Test
    void chargingMeansAtHomeEvenWhenThePositionIsFarAway() {
        var snapshot = snapshot(positionAgedMinutes(FAR_LAT, FAR_LON, 1),
                new TractiveHardwareDto(50, "CHARGING"));

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.CHARGING);
        assertThat(verdict.stale()).isFalse();
    }

    // --- Regel 3: frische Position ------------------------------------------------------

    @Test
    void freshPositionInsideTheHomeRadiusMeansAtHome() {
        var snapshot = snapshot(positionAgedMinutes(HOME_LAT, HOME_LON, 5),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.stale()).isFalse();
        assertThat(verdict.distanceMeters()).isLessThan(1.0);
        assertThat(verdict.positionAgeMinutes()).isEqualTo(5L);
    }

    @Test
    void freshPositionOutsideTheHomeRadiusMeansAway() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 5),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
    }

    /** Der Rand zaehlt als innerhalb – konsistent zu GeoZone.contains. */
    @Test
    void aPositionExactlyOnTheHomeRadiusCountsAsAtHome() {
        TractiveProperties properties = propertiesWithHome();
        var position = positionAgedMinutes(NEAR_LAT, HOME_LON, 5);
        double distance = GeoZone.distanceMeters(HOME_LAT, HOME_LON, NEAR_LAT, HOME_LON);
        properties.setHomeRadiusMeters(distance);

        HomeVerdict verdict = resolve(properties, snapshot(position,
                new TractiveHardwareDto(87, "NOT_CHARGING"))).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
    }

    /** Ohne Zeitstempel laesst sich "still" nicht bestimmen – dann gilt der Bericht als frisch. */
    @Test
    void positionWithoutTimestampIsTreatedAsFresh() {
        var position = new TractivePositionDto(List.of(FAR_LAT, FAR_LON), 12.0, "GPS", null);

        HomeVerdict verdict = resolve(propertiesWithHome(),
                snapshot(position, new TractiveHardwareDto(87, "NOT_CHARGING"))).orElseThrow();

        assertThat(verdict.stale()).isFalse();
        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.positionAgeMinutes()).isNull();
    }

    // --- Regel 4: Stille als Ausschalten deuten -----------------------------------------

    @Test
    void silenceWithHealthyBatteryNearHomeMeansPoweredOffAtHome() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isTrue();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POWERED_OFF);
        assertThat(verdict.stale()).isTrue();
        assertThat(verdict.positionAgeMinutes()).isEqualTo(90L);
    }

    // --- Regel 5: Stille ohne die Belege -> letztes Positionsurteil ---------------------

    @Test
    void silenceWithEmptyBatteryFallsBackToTheLastKnownPosition() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(3, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.stale()).isTrue();
    }

    @Test
    void silenceFarFromHomeFallsBackToTheLastKnownPosition() {
        var snapshot = snapshot(positionAgedMinutes(FAR_LAT, FAR_LON, 240),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.atHome()).isFalse();
        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.stale()).isTrue();
    }

    /** Fail-safe: ohne Akkustand wird nicht auf "ausgeschaltet" geschlossen. */
    @Test
    void silenceWithoutBatteryLevelDoesNotInferPoweredOff() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(null, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.atHome()).isFalse();
    }

    @Test
    void silenceWithoutAnyHardwareReportDoesNotInferPoweredOff() {
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90), null);

        HomeVerdict verdict = resolve(propertiesWithHome(), snapshot).orElseThrow();

        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POSITION);
        assertThat(verdict.atHome()).isFalse();
    }

    /** Ein zu klein konfigurierter Ankunftsradius darf Regel 4 nicht unwirksam machen. */
    @Test
    void theArrivalRadiusNeverFallsBelowTheHomeRadius() {
        TractiveProperties properties = propertiesWithHome();
        properties.setHomeRadiusMeters(400);
        properties.setHomeArrivalRadiusMeters(10);
        var snapshot = snapshot(positionAgedMinutes(NEAR_LAT, HOME_LON, 90),
                new TractiveHardwareDto(87, "NOT_CHARGING"));

        HomeVerdict verdict = resolve(properties, snapshot).orElseThrow();

        assertThat(verdict.basis()).isEqualTo(HomeVerdict.Basis.POWERED_OFF);
    }

    // --- Regel 6: gar keine Daten -------------------------------------------------------

    @Test
    void withoutPositionAndWithoutChargingThereIsNoVerdict() {
        assertThat(resolve(propertiesWithHome(),
                snapshot(null, new TractiveHardwareDto(87, "NOT_CHARGING")))).isEmpty();
    }

    @Test
    void aPositionWithoutCoordinatesYieldsNoVerdict() {
        var position = new TractivePositionDto(null, null, null, 1800000000L);

        assertThat(resolve(propertiesWithHome(),
                snapshot(position, new TractiveHardwareDto(87, "NOT_CHARGING")))).isEmpty();
    }
}
```

- [ ] **Step 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `mvn -Dtest=TractiveHomeResolverTest test`
Expected: COMPILATION ERROR — `cannot find symbol: class TractiveHomeResolver`

- [ ] **Step 4: Den Resolver implementieren**

`backend/src/main/java/com/household/manager/tractive/TractiveHomeResolver.java`:

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Die einzige Definition von "zu Hause". Entity-Mapper und Haustier-API fragen
 * dieselbe Instanz, damit Dashboard-Kachel und Flow-Trigger nicht auseinanderlaufen.
 *
 * <p>Der Tracker wird zu Hause ausgeschaltet, und die Tractive-API kennt dafuer kein
 * Statusfeld – erkennbar ist es nur an einem ausbleibenden Positionsbericht. Weil
 * "Akku unterwegs leergelaufen" genauso aussieht, verlangt diese Deutung zwei
 * unabhaengige Belege: einen gesunden Akkustand und Heimnaehe im weiten Radius.
 *
 * <p>{@link Optional#empty()} bedeutet ueberall dasselbe: keine Aussage moeglich. Es wird
 * nie ein Zustand geraten – ein Alarm-Flow darf nicht bei jedem GPS-Aussetzer feuern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TractiveHomeResolver {

    private final TractiveProperties properties;

    /** Die Warnung ueber fehlende Home-Koordinaten soll nicht jede Minute im Log stehen. */
    private final AtomicBoolean missingHomeWarned = new AtomicBoolean();

    public Optional<HomeVerdict> resolve(TractivePetSnapshot snapshot, Instant now) {
        if (!hasHomeCoordinates()) {
            warnAboutMissingHomeOnce();
            return Optional.empty();
        }

        TractiveHardwareDto hardware = snapshot.hardware();
        if (hardware != null && hardware.isCharging()) {
            return Optional.of(HomeVerdict.charging());
        }

        TractivePositionDto position = snapshot.position();
        if (position == null || !position.hasCoordinates()) {
            return Optional.empty();
        }

        double distanceMeters = GeoZone.distanceMeters(
                properties.getHomeLatitude(), properties.getHomeLongitude(),
                position.latitude(), position.longitude());
        Long ageMinutes = positionAgeMinutes(position, now);
        boolean stale = ageMinutes != null && ageMinutes >= properties.getPoweredOffAfterMinutes();

        if (stale && looksPoweredOffAtHome(hardware, distanceMeters)) {
            return Optional.of(HomeVerdict.poweredOff(distanceMeters, ageMinutes));
        }
        return Optional.of(HomeVerdict.fromPosition(
                distanceMeters <= properties.getHomeRadiusMeters(), distanceMeters, ageMinutes, stale));
    }

    private boolean hasHomeCoordinates() {
        return properties.getHomeLatitude() != null && properties.getHomeLongitude() != null;
    }

    private void warnAboutMissingHomeOnce() {
        if (missingHomeWarned.compareAndSet(false, true)) {
            log.warn("tractive.home-latitude/-longitude sind nicht gesetzt – "
                    + "die Entitaet 'zu Hause' wird nicht gemeldet.");
        }
    }

    /** {@code null}, wenn der Bericht keinen Zeitstempel hat – dann gilt er als frisch. */
    private Long positionAgeMinutes(TractivePositionDto position, Instant now) {
        Instant reportedAt = position.reportedAt();
        if (reportedAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(reportedAt, now).toMinutes());
    }

    /**
     * Fail-safe: ohne Akkustand wird nicht auf "ausgeschaltet" geschlossen. Sonst wuerde
     * ein unterwegs verlorener Tracker als "zu Hause" gemeldet.
     */
    private boolean looksPoweredOffAtHome(TractiveHardwareDto hardware, double distanceMeters) {
        if (hardware == null || hardware.batteryLevel() == null) {
            return false;
        }
        return hardware.batteryLevel() >= properties.getPoweredOffMinBatteryPercent()
                && distanceMeters <= effectiveArrivalRadiusMeters();
    }

    /** Ein kleiner konfigurierter Ankunftsradius darf die Regel nicht unwirksam machen. */
    private double effectiveArrivalRadiusMeters() {
        return Math.max(properties.getHomeArrivalRadiusMeters(), properties.getHomeRadiusMeters());
    }
}
```

- [ ] **Step 5: Test laufen lassen und grün bestätigen**

Run: `mvn -Dtest=TractiveHomeResolverTest test`
Expected: `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/HomeVerdict.java backend/src/main/java/com/household/manager/tractive/TractiveHomeResolver.java backend/src/test/java/com/household/manager/tractive/TractiveHomeResolverTest.java
git commit -m "feat(tractive): TractiveHomeResolver als einzige Definition von zu Hause"
```

---

## Task 3: Home-Entität im `TractiveEntityMapper`

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/mapper/TractiveEntityMapper.java`
- Test: `backend/src/test/java/com/household/manager/entitystate/mapper/TractiveEntityMapperTest.java`

**Achtung:** Der Konstruktor des Mappers bekommt einen zweiten Parameter. Die `setUp`-Methode im bestehenden Test muss mitgezogen werden, sonst kompiliert die ganze Testklasse nicht.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `TractiveEntityMapperTest.java` zuerst die Imports ergänzen (zu den bestehenden hinzufügen):

```java
import com.household.manager.tractive.TractiveHomeResolver;
import java.time.Instant;
```

Dann `setUp()` ersetzen — der Mapper braucht jetzt Home-Koordinaten, sonst entsteht die neue Entität nie:

```java
    private TractiveEntityMapper mapper;

    @BeforeEach
    void setUp() {
        TractiveProperties properties = new TractiveProperties();
        properties.setHomeLatitude(48.2082);
        properties.setHomeLongitude(16.3738);
        properties.setHomeRadiusMeters(100);
        mapper = new TractiveEntityMapper(new TractiveZoneResolver(properties),
                new TractiveHomeResolver(properties));
    }
```

Und ans Ende der Klasse (vor der schließenden Klammer) diese Tests anfügen:

```java
    /** Ein frischer Bericht am Home-Punkt: die Entitaet steht auf 'on'. */
    @Test
    void homeEntityIsReportedForAFreshPositionAtHome() {
        long nowSeconds = Instant.now().getEpochSecond();
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", nowSeconds),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());

        var home = byId(mapper.map(snapshot), "binary_sensor.tractive_dev_9_home");

        assertEquals("on", home.state());
        assertEquals("Bello zu Hause", home.friendlyName());
        assertEquals("presence", home.attributes().get("deviceClass"));
        assertEquals("position", home.attributes().get("basis"));
        assertEquals(false, home.attributes().get("stale"));
        assertTrue(home.attributes().containsKey("distanceMeters"));
        assertTrue(home.attributes().containsKey("positionTime"));
    }

    @Test
    void chargingIsReportedAsAtHomeWithItsOwnBasis() {
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.3000, 16.5000), 12.0, "GPS",
                        Instant.now().getEpochSecond()),
                new TractiveHardwareDto(50, "CHARGING"), List.of());

        var home = byId(mapper.map(snapshot), "binary_sensor.tractive_dev_9_home");

        assertEquals("on", home.state());
        assertEquals("charging", home.attributes().get("basis"));
    }

    /** Ohne verwertbare Daten entsteht kein Update – der letzte Wert bleibt so stehen. */
    @Test
    void withoutAnyVerdictNoHomeEntityIsReported() {
        var snapshot = new TractivePetSnapshot(bello(), null,
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());

        List<EntityStateUpdate> updates = mapper.map(snapshot);

        assertTrue(updates.stream().noneMatch(u -> u.entityId().endsWith("_home")));
    }

    @Test
    void isHomeEntityRecognisesOnlyTheHomeEntity() {
        long nowSeconds = Instant.now().getEpochSecond();
        var snapshot = new TractivePetSnapshot(bello(),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", nowSeconds),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());
        List<EntityStateUpdate> updates = mapper.map(snapshot);

        assertTrue(mapper.isHomeEntity(byId(updates, "binary_sensor.tractive_dev_9_home")));
        assertFalse(mapper.isHomeEntity(byId(updates, "sensor.tractive_dev_9_location")));
        assertFalse(mapper.isHomeEntity(byId(updates, "sensor.tractive_dev_9_battery")));
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `mvn -Dtest=TractiveEntityMapperTest test`
Expected: COMPILATION ERROR — der Mapper-Konstruktor nimmt nur ein Argument, `isHomeEntity` existiert nicht

- [ ] **Step 3: Den Mapper erweitern**

`TractiveEntityMapper.java` vollständig ersetzen durch:

```java
package com.household.manager.entitystate.mapper;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.tractive.HomeVerdict;
import com.household.manager.tractive.TractiveHomeResolver;
import com.household.manager.tractive.TractivePetSnapshot;
import com.household.manager.tractive.TractiveZoneResolver;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mappt einen Tractive-Haustier-Snapshot auf Entity-Zustaende:
 * {@code sensor.tractive_<trackerId>_location} (State = Zonenname oder {@code away}),
 * {@code sensor.tractive_<trackerId>_battery},
 * {@code binary_sensor.tractive_<trackerId>_charging} und
 * {@code binary_sensor.tractive_<trackerId>_home}.
 */
@Component
@RequiredArgsConstructor
public class TractiveEntityMapper {

    /** Suffix der Home-Entitaet; hier definiert, weil diese Klasse alle Entity-IDs baut. */
    private static final String HOME_SUFFIX = "home";

    private final TractiveZoneResolver zoneResolver;
    private final TractiveHomeResolver homeResolver;

    public List<EntityStateUpdate> map(TractivePetSnapshot snapshot) {
        List<EntityStateUpdate> updates = new ArrayList<>();
        String ref = snapshot.trackerId();
        String name = snapshot.name();

        updates.add(locationUpdate(snapshot, ref, name));

        TractiveHardwareDto hardware = snapshot.hardware();
        if (hardware != null && hardware.batteryLevel() != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TRACTIVE, ref, "battery"))
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.TRACTIVE)
                    .sourceRef(ref)
                    .friendlyName(name + " Akku")
                    .state(String.valueOf(hardware.batteryLevel()))
                    .attributes(Map.of("deviceClass", "battery", "unit", "%"))
                    .build());
        }
        if (hardware != null && hardware.chargingState() != null) {
            updates.add(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.TRACTIVE, ref, "charging"))
                    .domain(EntityDomain.BINARY_SENSOR)
                    .source(EntitySource.TRACTIVE)
                    .sourceRef(ref)
                    .friendlyName(name + " laedt")
                    .state(hardware.isCharging() ? "on" : "off")
                    .attributes(Map.of("deviceClass", "battery_charging"))
                    .build());
        }

        // Ohne Urteil wird bewusst kein Update gemeldet: der Entity-State-Layer behaelt
        // dann den letzten Wert, statt einen Zustand zu raten.
        homeResolver.resolve(snapshot, Instant.now())
                .map(verdict -> homeUpdate(verdict, snapshot, ref, name))
                .ifPresent(updates::add);

        return updates;
    }

    /** True fuer die Home-Entitaet dieser Quelle; der Poller nimmt sie davon aus, unavailable zu werden. */
    public boolean isHomeEntity(EntityStateUpdate update) {
        return update.source() == EntitySource.TRACTIVE
                && update.entityId().equals(EntityIds.build(EntityDomain.BINARY_SENSOR,
                        EntitySource.TRACTIVE, update.sourceRef(), HOME_SUFFIX));
    }

    private EntityStateUpdate homeUpdate(HomeVerdict verdict, TractivePetSnapshot snapshot,
                                         String ref, String name) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("deviceClass", "presence");
        attributes.put("basis", verdict.basis().name().toLowerCase(Locale.ROOT));
        attributes.put("stale", verdict.stale());
        if (verdict.distanceMeters() != null) {
            attributes.put("distanceMeters", Math.round(verdict.distanceMeters()));
        }
        if (verdict.positionAgeMinutes() != null) {
            attributes.put("positionAgeMinutes", verdict.positionAgeMinutes());
        }
        TractivePositionDto position = snapshot.position();
        if (position != null && position.reportedAt() != null) {
            attributes.put("positionTime", position.reportedAt().toString());
        }
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.TRACTIVE, ref, HOME_SUFFIX))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.TRACTIVE)
                .sourceRef(ref)
                .friendlyName(name + " zu Hause")
                .state(verdict.atHome() ? "on" : "off")
                .attributes(attributes)
                .build();
    }

    private EntityStateUpdate locationUpdate(TractivePetSnapshot snapshot, String ref, String name) {
        TractivePositionDto position = snapshot.position();
        Map<String, Object> attributes = new HashMap<>();
        String state = TractiveZoneResolver.UNKNOWN;

        if (position != null && position.hasCoordinates()) {
            state = zoneResolver.resolve(position.latitude(), position.longitude(), snapshot.zones());
            attributes.put("latitude", position.latitude());
            attributes.put("longitude", position.longitude());
            if (position.accuracy() != null) {
                attributes.put("accuracy", position.accuracy());
            }
            if (position.sensorUsed() != null) {
                attributes.put("sensorUsed", position.sensorUsed());
            }
            if (position.reportedAt() != null) {
                attributes.put("positionTime", position.reportedAt().toString());
            }
        }
        return EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TRACTIVE, ref, "location"))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.TRACTIVE)
                .sourceRef(ref)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build();
    }
}
```

- [ ] **Step 4: Test laufen lassen und grün bestätigen**

Run: `mvn -Dtest=TractiveEntityMapperTest test`
Expected: `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/mapper/TractiveEntityMapper.java backend/src/test/java/com/household/manager/entitystate/mapper/TractiveEntityMapperTest.java
git commit -m "feat(tractive): Entitaet binary_sensor.tractive_<id>_home"
```

---

## Task 4: Home-Entität überlebt den Cloud-Ausfall

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/TractivePollingService.java:105-117`
- Test: `backend/src/test/java/com/household/manager/tractive/TractivePollingServiceTest.java`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `TractivePollingServiceTest.java` neben der bestehenden Konstante `LOCATION_UPDATE` eine zweite ergänzen:

```java
    private static final EntityStateUpdate HOME_UPDATE = EntityStateUpdate.builder()
            .entityId("binary_sensor.tractive_dev_9_home")
            .domain(EntityDomain.BINARY_SENSOR)
            .source(EntitySource.TRACTIVE)
            .sourceRef("dev-9")
            .friendlyName("Bello zu Hause")
            .state("on")
            .attributes(Map.of())
            .build();
```

Und ans Ende der Klasse diesen Test anfügen:

```java
    /**
     * Die Home-Entitaet darf bei einem Ausfall nicht 'unavailable' werden – der Tracker
     * ist zu Hause bewusst aus, und der letzte Wert ist genau die gewuenschte Aussage.
     */
    @Test
    void cloudFailureLeavesTheHomeEntityUntouched() {
        givenAuthenticated();
        givenOnePet();
        when(mapper.map(any())).thenReturn(List.of(LOCATION_UPDATE, HOME_UPDATE));
        when(mapper.isHomeEntity(LOCATION_UPDATE)).thenReturn(false);
        when(mapper.isHomeEntity(HOME_UPDATE)).thenReturn(true);

        service.poll();
        reset(entityStateService);
        when(apiClient.listTrackableObjects("tok", "u-1"))
                .thenThrow(new TractiveException("cloud down"));

        assertDoesNotThrow(() -> service.poll());

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, atLeastOnce()).reportState(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .noneMatch(update -> update.entityId().endsWith("_home")));
        assertTrue(captor.getAllValues().stream()
                .anyMatch(update -> update.entityId().endsWith("_location")
                        && "unavailable".equals(update.state())));
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `mvn -Dtest=TractivePollingServiceTest test`
Expected: COMPILATION ERROR — `cannot find symbol: method isHomeEntity`. (Falls `isHomeEntity` aus Task 3 schon existiert, läuft der Test stattdessen los und schlägt mit „expected true but was false" fehl, weil die Home-Entität auf `unavailable` gesetzt wurde.)

- [ ] **Step 3: `markUnavailable()` anpassen**

In `TractivePollingService.java` die Methode `markUnavailable()` ersetzen durch:

```java
    /**
     * Die Home-Entitaet ist bewusst ausgenommen: Sie behaelt ihren letzten Wert, weil der
     * Tracker zu Hause absichtlich aus ist und "keine Daten" dort der Normalfall ist.
     */
    private void markUnavailable() {
        for (EntityStateUpdate update : lastUpdates) {
            if (mapper.isHomeEntity(update)) {
                continue;
            }
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(update.entityId())
                    .domain(update.domain())
                    .source(update.source())
                    .sourceRef(update.sourceRef())
                    .friendlyName(update.friendlyName())
                    .state("unavailable")
                    .attributes(update.attributes())
                    .build());
        }
    }
```

- [ ] **Step 4: Test laufen lassen und grün bestätigen**

Run: `mvn -Dtest=TractivePollingServiceTest test`
Expected: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/TractivePollingService.java backend/src/test/java/com/household/manager/tractive/TractivePollingServiceTest.java
git commit -m "fix(tractive): Home-Entitaet behaelt bei Cloud-Ausfall ihren letzten Wert"
```

---

## Task 5: `atHome` in der Haustier-API

**Files:**
- Modify: `backend/src/main/java/com/household/manager/tractive/dto/TractivePetDto.java`
- Modify: `backend/src/main/java/com/household/manager/tractive/TractivePetService.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractivePetServiceTest.java`
- Test: `backend/src/test/java/com/household/manager/tractive/TractiveControllerTest.java:26-27` (baut das DTO direkt und bricht sonst)

**Achtung:** `TractivePetService` bekommt einen dritten Konstruktorparameter — im Test wird der Konstruktor direkt aufgerufen, alle drei Stellen müssen mitgezogen werden.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `TractivePetServiceTest.java` die Imports ergänzen:

```java
import java.time.Instant;
```

Das `@Mock`-Feld für den Resolver hinzufügen — hier wird bewusst **kein** Mock verwendet, sondern die echte Klasse, damit der Test die tatsächliche Definition von „zu Hause" prüft. Direkt unter `private TractiveZoneResolver zoneResolver;` einfügen:

```java
    /** Bewusst die echte Klasse: der Test soll die reale Home-Definition pruefen. */
    private TractiveHomeResolver homeResolver() {
        TractiveProperties properties = new TractiveProperties();
        properties.setHomeLatitude(48.2082);
        properties.setHomeLongitude(16.3738);
        properties.setHomeRadiusMeters(100);
        return new TractiveHomeResolver(properties);
    }
```

Alle drei bestehenden `new TractivePetService(pollingService, zoneResolver)`-Aufrufe ersetzen durch:

```java
new TractivePetService(pollingService, zoneResolver, homeResolver())
```

Im Test `petsAreReturnedForTheMap` muss der Positions-Zeitstempel frisch sein, sonst greift die Stille-Regel. Die Zeile

```java
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L),
```

ersetzen durch:

```java
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS",
                        Instant.now().getEpochSecond()),
```

und am Ende desselben Tests ergänzen:

```java
        assertThat(pet.atHome()).isTrue();
```

Danach ans Ende der Klasse anfügen:

```java
    @Test
    void withoutAnyVerdictAtHomeIsNull() {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                null, null, List.of());
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets.get(0).atHome()).isNull();
    }

    @Test
    void aPetFarFromHomeIsNotAtHome() {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                new TractivePositionDto(List.of(48.3000, 16.5000), 12.0, "GPS",
                        Instant.now().getEpochSecond()),
                new TractiveHardwareDto(87, "NOT_CHARGING"), List.of());
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));
        when(zoneResolver.resolve(48.3000, 16.5000, snapshot.zones())).thenReturn("away");

        List<TractivePetDto> pets =
                new TractivePetService(pollingService, zoneResolver, homeResolver()).listPets();

        assertThat(pets.get(0).atHome()).isFalse();
    }
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `mvn -Dtest=TractivePetServiceTest test`
Expected: COMPILATION ERROR — Konstruktor mit drei Argumenten und Methode `atHome()` existieren nicht

- [ ] **Step 3: DTO erweitern**

`TractivePetDto.java` vollständig ersetzen durch:

```java
package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Gebuendelte Sicht eines Haustiers fuer die Kartenseite. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TractivePetDto(
        String trackerId,
        String name,
        Double latitude,
        Double longitude,
        Double accuracy,
        String sensorUsed,
        Instant lastSeen,
        Integer batteryPercent,
        Boolean charging,
        String zone,
        /** {@code null}, wenn keine Aussage moeglich ist – dann zeigt die UI nichts an. */
        Boolean atHome
) {
}
```

- [ ] **Step 4: Service erweitern**

`TractivePetService.java` vollständig ersetzen durch:

```java
package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Baut den letzten bekannten Stand der Haustiere fuer die Kartenseite. */
@Service
@RequiredArgsConstructor
public class TractivePetService {

    private final TractivePollingService pollingService;
    private final TractiveZoneResolver zoneResolver;
    private final TractiveHomeResolver homeResolver;

    public List<TractivePetDto> listPets() {
        // Ein gemeinsamer Zeitpunkt: sonst koennten zwei Tiere desselben Abrufs
        // unterschiedliche Stille-Schwellen sehen.
        Instant now = Instant.now();
        return pollingService.latestSnapshots().stream()
                .map(snapshot -> toDto(snapshot, now))
                .toList();
    }

    private TractivePetDto toDto(TractivePetSnapshot snapshot, Instant now) {
        TractivePositionDto position = snapshot.position();
        TractiveHardwareDto hardware = snapshot.hardware();
        boolean hasPosition = position != null && position.hasCoordinates();

        return new TractivePetDto(
                snapshot.trackerId(),
                snapshot.name(),
                hasPosition ? position.latitude() : null,
                hasPosition ? position.longitude() : null,
                hasPosition ? position.accuracy() : null,
                hasPosition ? position.sensorUsed() : null,
                position != null ? position.reportedAt() : null,
                hardware != null ? hardware.batteryLevel() : null,
                hardware != null ? hardware.isCharging() : null,
                hasPosition
                        ? zoneResolver.resolve(position.latitude(), position.longitude(), snapshot.zones())
                        : TractiveZoneResolver.UNKNOWN,
                homeResolver.resolve(snapshot, now).map(HomeVerdict::atHome).orElse(null));
    }
}
```

- [ ] **Step 5: Test laufen lassen und grün bestätigen**

Run: `mvn -Dtest=TractivePetServiceTest test`
Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS

- [ ] **Step 6: `TractiveControllerTest` an die neue DTO-Signatur anpassen**

Die Klasse baut das DTO direkt und kompiliert sonst nicht. In `TractiveControllerTest.java` die Zeilen 26–27

```java
        var pet = new TractivePetDto("dev-9", "Bello", 48.2082, 16.3738, 12.0, "GPS",
                Instant.ofEpochSecond(1800000000L), 87, false, "Garten");
```

ersetzen durch:

```java
        var pet = new TractivePetDto("dev-9", "Bello", 48.2082, 16.3738, 12.0, "GPS",
                Instant.ofEpochSecond(1800000000L), 87, false, "Garten", true);
```

Und die Erwartungskette in Zeile 40 (`.andExpect(jsonPath("$[0].zone").value("Garten"));`) ersetzen durch:

```java
                .andExpect(jsonPath("$[0].zone").value("Garten"))
                .andExpect(jsonPath("$[0].atHome").value(true));
```

- [ ] **Step 7: Alle Tractive-Tests gemeinsam laufen lassen**

Run: `mvn -Dtest='Tractive*Test,GeoZoneTest' test`
Expected: BUILD SUCCESS, keine Failures

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/household/manager/tractive/dto/TractivePetDto.java backend/src/main/java/com/household/manager/tractive/TractivePetService.java backend/src/test/java/com/household/manager/tractive/TractivePetServiceTest.java backend/src/test/java/com/household/manager/tractive/TractiveControllerTest.java
git commit -m "feat(tractive): atHome in der Haustier-API"
```

---

## Task 6: Badge auf der Hundetracker-Seite

**Files:**
- Modify: `frontend/src/app/models/tractive.model.ts`
- Modify: `frontend/src/app/pages/pets/pets.component.html:30-42`
- Modify: `frontend/src/app/pages/pets/pets.component.scss`

Rein darstellend, kein neuer Zustand und keine neue Logik — hier gibt es nichts zu testen, was der Compiler nicht schon prüft. Verifiziert wird über den Build und die unveränderte Test-Baseline.

- [ ] **Step 1: Modell erweitern**

In `tractive.model.ts` vor der schließenden Klammer von `TractivePet` ergänzen:

```typescript
  /** Zonenname, 'away' ausserhalb aller Zonen oder 'unknown' ohne Position. */
  zone: string;
  /** undefined, wenn keine Aussage moeglich ist – dann wird kein Badge gezeigt. */
  atHome?: boolean;
}
```

(Die bestehende `zone`-Zeile bleibt, nur `atHome` kommt dazu.)

- [ ] **Step 2: Badge ins Template**

In `pets.component.html` den `<article class="pet-card">`-Block (Zeilen 31–41) ersetzen durch:

```html
      <article class="pet-card" *ngFor="let pet of pets()">
        <h2>{{ pet.name }}</h2>
        <p class="home-badge" *ngIf="pet.atHome != null"
           [class.home-badge--away]="!pet.atHome">
          {{ pet.atHome ? 'Zu Hause' : 'Unterwegs' }}
        </p>
        <p class="zone" [class.away]="pet.zone === 'away'">{{ zoneLabel(pet) }}</p>
        <p *ngIf="pet.batteryPercent != null">
          Akku: {{ pet.batteryPercent }} %
          <span *ngIf="pet.charging"> (laedt)</span>
        </p>
        <p *ngIf="pet.lastSeen" class="muted">
          Zuletzt gesehen: {{ pet.lastSeen | date:'short' }}
        </p>
      </article>
```

- [ ] **Step 3: Styles ergänzen**

In `pets.component.scss` vor `.zone` einfügen:

```scss
.home-badge {
  display: inline-block;
  margin: 0 0 0.5rem;
  padding: 0.15rem 0.6rem;
  border-radius: 999px;
  font-size: 0.85rem;
  font-weight: 600;
  color: #14532d;
  background: #bbf7d0;

  &--away {
    color: #7c2d12;
    background: #fed7aa;
  }
}
```

- [ ] **Step 4: Build prüfen**

Run (aus `frontend/`): `npm run build`
Expected: „Application bundle generation complete", keine Fehler

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/models/tractive.model.ts frontend/src/app/pages/pets/pets.component.html frontend/src/app/pages/pets/pets.component.scss
git commit -m "feat(pets): Zu-Hause-Badge auf der Hundetracker-Seite"
```

---

## Task 7: Dashboard-Kachel

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.html:296` (nach der Türschloss-Kachel)
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.scss`

**Wichtig:** Das Markup kommt direkt ins Dashboard-Template, **keine** Kind-Komponente. Die `lumina`-Klassen sind in `dashboard.component.scss` gekapselt; eine Kind-Komponente würde lautlos ungestylt rendern.

- [ ] **Step 1: Datenanbindung in der Komponente**

In `dashboard.component.ts` bei den übrigen Service-Importen ergänzen:

```typescript
import { TractiveService } from '../../services/tractive.service';
import { TractivePet } from '../../models/tractive.model';
```

Bei den `inject`-Feldern (neben `nukiService`) ergänzen:

```typescript
  private readonly tractiveService = inject(TractiveService);
```

Bei den Subscription-Feldern (neben `nukiSubscription`) ergänzen:

```typescript
  private petSubscription?: Subscription;
```

Bei den Konstanten (neben `NUKI_REFRESH_MS`) ergänzen:

```typescript
  private static readonly PETS_REFRESH_MS = 60000;
```

Bei den öffentlichen Feldern (neben `nukiLocks`) ergänzen:

```typescript
  /** Haustiere fuer die Zu-Hause-Kachel; leer = Kachel wird nicht gerendert. */
  pets: TractivePet[] = [];
```

In `ngOnInit()` nach `this.startNukiRefresh();` ergänzen:

```typescript
    this.startPetRefresh();
```

In `ngOnDestroy()` nach `this.nukiSubscription?.unsubscribe();` ergänzen:

```typescript
    this.petSubscription?.unsubscribe();
```

Direkt hinter der Methode `startNukiRefresh()` einfügen:

```typescript
  private startPetRefresh(): void {
    this.petSubscription = interval(DashboardComponent.PETS_REFRESH_MS)
      .pipe(
        startWith(0),
        // Ladefehler behalten die zuletzt bekannten Tiere (null = kein Update).
        switchMap(() => this.tractiveService.getPets().pipe(catchError(() => of<TractivePet[] | null>(null))))
      )
      .subscribe(pets => {
        if (pets) {
          this.pets = pets;
        }
      });
  }

  /** Nur Tiere mit einer Aussage; ohne sie bleibt die Kachel leer statt zu raten. */
  get petsWithVerdict(): TractivePet[] {
    return this.pets.filter(pet => pet.atHome != null);
  }

  petStatusLabel(pet: TractivePet): string {
    return pet.atHome ? 'Zu Hause' : 'Unterwegs';
  }

  petStatusIcon(pet: TractivePet): string {
    return pet.atHome ? 'home' : 'pets';
  }
```

- [ ] **Step 2: Kachel ins Template**

In `dashboard.component.html` direkt nach dem schließenden `</div>` der Türschloss-Kachel (Zeile 296) und vor `<div class="lumina__modes-area">` einfügen:

```html
    <div class="lumina-card lumina__pets" *ngIf="petsWithVerdict.length > 0">
      <div class="lumina__secured-icon">
        <span class="material-symbols-outlined">pets</span>
      </div>
      <div class="lumina__pets-info">
        <h4 class="lumina__label lumina__label--secondary">Hund</h4>
        <p class="lumina__secured-detail" *ngFor="let pet of petsWithVerdict"
           [class.lumina__pet--away]="!pet.atHome">
          <span class="material-symbols-outlined">{{ petStatusIcon(pet) }}</span>
          {{ pet.name }} • {{ petStatusLabel(pet) }}
        </p>
      </div>
    </div>
```

- [ ] **Step 3: Styles ergänzen**

In `dashboard.component.scss` direkt hinter dem `.lumina__lock-info`-Block einfügen:

```scss
.lumina__pets {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 24px;
  border-radius: var(--radius-full);
}

.lumina__pets-info {
  min-width: 0;
  flex: 1;
}

.lumina__pets-info .lumina__secured-detail {
  display: flex;
  align-items: center;
  gap: 6px;

  .material-symbols-outlined {
    font-size: 16px;
  }
}

.lumina__pet--away {
  color: var(--tertiary);
}
```

- [ ] **Step 4: Build prüfen**

Run (aus `frontend/`): `npm run build`
Expected: „Application bundle generation complete", keine Fehler

- [ ] **Step 5: Frontend-Tests gegen die Baseline prüfen**

Run (aus `frontend/`): `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: genau 4 FAILED (`HeaderComponent should create`, `AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`) — mehr wäre eine Regression. Bricht der Lauf mit „SmartDeviceListComponent … Cannot read properties of undefined (reading 'subscribe')" ab, ist das die bekannte Karma-Flake: einfach erneut laufen lassen.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/pages/dashboard/dashboard.component.ts frontend/src/app/pages/dashboard/dashboard.component.html frontend/src/app/pages/dashboard/dashboard.component.scss
git commit -m "feat(dashboard): Kachel Hund zu Hause"
```

---

## Task 8: Deployment und Dokumentation

Ohne diesen Schritt entsteht die Entität im Betrieb nie — die Home-Koordinaten sind heute nirgends gesetzt.

**Files:**
- Modify: `docker-compose.yml`
- Modify: `CLAUDE.md` (Abschnitt „Tractive-Hundetracker")

- [ ] **Step 1: Umgebungsvariablen in `docker-compose.yml`**

Im `environment`-Block des Backend-Service bei den übrigen Variablen ergänzen:

```yaml
      # Definiert, was "zu Hause" heisst. Ohne diese beiden Werte entsteht die
      # Entitaet binary_sensor.tractive_<id>_home nicht.
      TRACTIVE_HOME_LAT: ${TRACTIVE_HOME_LAT}
      TRACTIVE_HOME_LON: ${TRACTIVE_HOME_LON}
```

- [ ] **Step 2: `CLAUDE.md` ergänzen**

Im Abschnitt „### Tractive-Hundetracker" nach dem Aufzählungspunkt, der mit „Entitäten pro Tracker" beginnt, diesen Punkt einfügen:

```markdown
- **„Ist der Hund zu Hause?"** ist `binary_sensor.tractive_<trackerId>_home` (`on`/`off`, `deviceClass: presence`). `TractiveHomeResolver` ist die **einzige** Definition von „zu Hause" — Entity-Mapper und `/v1/tractive/pets` fragen dieselbe Klasse. Reihenfolge der Regeln: keine Home-Koordinaten ⇒ keine Entität; `charging` ⇒ zu Hause; frischer Positionsbericht ⇒ Distanz ≤ `home-radius-meters`; Bericht still (≥ `powered-off-after-minutes`) **und** Akku ≥ `powered-off-min-battery-percent` **und** Distanz ≤ `home-arrival-radius-meters` ⇒ zu Hause (`basis=powered_off`); sonst letztes Positionsurteil mit `stale=true`. Ohne jede Aussage wird **kein Update gemeldet**, der letzte Wert bleibt stehen
- **Der Tracker wird zu Hause ausgeschaltet, und die API kennt dafür kein Statusfeld** — erkennbar nur an einem ausbleibenden Positionsbericht. Weil „Akku unterwegs leergelaufen" identisch aussieht, verlangt die Deutung zwei unabhängige Belege (gesunder Akku + Heimnähe im weiten Radius) und ist fail-safe: fehlt `batteryLevel`, greift sie nicht. **Offen:** ob `device_hw_report` bei ausgeschaltetem Tracker überhaupt noch einen Akkustand liefert. Tut es das nicht, greift die Regel nie und der zuletzt gesehene Akkustand müsste im Poller zwischengespeichert werden — erster Prüfpunkt bei der Verifikation
- `tractive.home-latitude`/`-longitude` haben jetzt eine **Doppelrolle**: Fallback-Zone für den Location-Sensor *und* verbindliche Home-Definition. `TRACTIVE_HOME_LAT`/`TRACTIVE_HOME_LON` müssen im Deployment gesetzt sein, sonst fehlen Entität, Badge und Dashboard-Kachel — sichtbar nur an einer Warnung im Log
- Die Home-Entität wird bei einem Cloud-Ausfall **nicht** `unavailable` (die übrigen schon): zu Hause ist „keine Daten" der Normalzustand, und der letzte Wert ist genau die gewünschte Aussage
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml CLAUDE.md
git commit -m "docs(tractive): Home-Entitaet dokumentieren und Envs ergaenzen"
```

---

## Task 9: Abschlussprüfung

- [ ] **Step 1: Vollständiger Backend-Testlauf**

Run (aus `backend/`): `mvn test`
Expected: BUILD FAILURE mit **genau** zwei Fehlschlägen — `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` („Access denied for user 'root'@'localhost'"). Jeder weitere Fehlschlag ist eine echte Regression und muss behoben werden.

- [ ] **Step 2: Frontend-Build**

Run (aus `frontend/`): `npm run build`
Expected: „Application bundle generation complete"

- [ ] **Step 3: Offene Punkte an den Nutzer melden**

Nicht automatisch verifizierbar und deshalb ausdrücklich zu berichten:

1. `TRACTIVE_HOME_LAT` / `TRACTIVE_HOME_LON` müssen mit den echten Koordinaten belegt werden — bis dahin existiert die Entität nicht.
2. `powered-off-after-minutes=60` ist geraten. Nach einigen Tagen Betrieb im Entity-Viewer `positionAgeMinutes` beobachten und den Wert auf knapp über das reale Melde-Intervall senken.
3. Prüfen, ob `device_hw_report` bei ausgeschaltetem Tracker noch einen Akkustand liefert. Wenn nicht, greift die Ausschalt-Erkennung nie.
