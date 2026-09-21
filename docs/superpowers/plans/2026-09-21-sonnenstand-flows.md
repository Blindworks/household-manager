# Sonnenstand für Flows — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Flows können auf Sonnenauf-/-untergang und bürgerliche Dämmerung reagieren — als Entität `sensor.sun`, als Trigger-Node `sun-trigger` (mit ±Versatz) und als Sonnenausdruck (`sunset-30`) in `time-condition`.

**Architecture:** Ein neues Paket `sun/` ist die einzige Stelle mit Sonnenwissen: `SunTimesService` rechnet mit `commons-suncalc` aus den Tractive-Home-Koordinaten (jeder Aufruf liest sie frisch, wirft nie, liefert `Optional`). Entität, Trigger und Bedingung fragen ausschließlich diesen Service. Keine DB-Änderung, keine neuen Endpunkte.

**Tech Stack:** Spring Boot 3.4 / Java 21, `org.shredzone.commons:commons-suncalc:3.11`, JUnit 5 + Mockito, Angular 19 (eine Katalogzeile, eine Hinweiszeile).

**Spec:** `docs/superpowers/specs/2026-09-21-sonnenstand-flows-design.md`

**Branch:** `feature/sun-flows` (bereits angelegt, Spec ist committet).

---

## Vorbemerkungen für den Ausführenden

- **Build:** Maven braucht JDK 21. Vor jedem Maven-Aufruf in der Bash:
  ```bash
  export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
  ```
  Aufrufe laufen aus `backend/`. `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen lokal **immer** fehl (keine Test-DB) — diese beiden ignorieren; alle anderen müssen grün sein.
- **Frontend-Prüfung:** `ng build --configuration production` bricht wegen eines bekannten SCSS-Budget-Fehlers im Dashboard ab (keine Regression). Typprüfung stattdessen mit `npx tsc --noEmit -p tsconfig.app.json` aus `frontend/`.
- **Commits:** deutsche Betreffzeile im Stil `feat(sun): …`, Umlaute in Commit-Messages als ae/oe/ue (Projektkonvention). Jede Commit-Message endet mit `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **Zeitzone in Tests:** immer `Clock.fixed(instant, ZoneId.of("Europe/Berlin"))`.

## Dateistruktur

| Datei | Verantwortung |
|---|---|
| `backend/pom.xml` | Abhängigkeit `commons-suncalc` |
| `backend/src/main/java/com/household/manager/sun/SunEvent.java` | Die vier Ereignis-Schlüsselwörter + Offset-Schranke (einzige Definition) |
| `backend/src/main/java/com/household/manager/sun/SunPhase.java` | Tagesphasen `day/dusk/night/dawn` |
| `backend/src/main/java/com/household/manager/sun/SunDayTimes.java` | Die vier Zeitpunkte eines Tages + Phasenregel (reine Funktion) |
| `backend/src/main/java/com/household/manager/sun/SunTimesService.java` | Einzige `commons-suncalc`-Stelle; Koordinaten aus Tractive-Home; wirft nie |
| `backend/src/main/java/com/household/manager/sun/SunTimeExpression.java` | Grammatik `HH:mm \| dawn\|sunrise\|sunset\|dusk[±N]` |
| `backend/src/main/java/com/household/manager/sun/SunEntityPublisher.java` | Minütlicher Scheduler → `sensor.sun` |
| `backend/src/main/java/com/household/manager/entitystate/EntitySource.java` | + `SUN` |
| `backend/src/main/java/com/household/manager/flowengine/nodes/SunTriggerHandler.java` | Trigger-Node mit Selbst-Neuplanung |
| `backend/src/main/java/com/household/manager/flowengine/nodes/TimeConditionNodeHandler.java` | Versteht Sonnenausdrücke |
| `backend/src/main/resources/application.properties` | `sun.publish-interval-ms` |
| `frontend/src/app/pages/flows/node-catalog.ts` | Label `sun-trigger` |
| `frontend/src/app/pages/admin-tractive/admin-tractive.component.html` | Hinweis auf die Kopplung |
| `docs/flows/flow-import-format.md`, `CLAUDE.md` | Doku |

---

### Task 1: Abhängigkeit und Grundtypen (`SunEvent`, `SunPhase`, `SunDayTimes`)

**Files:**
- Modify: `backend/pom.xml` (nach dem `commons-csv`-Block, ca. Zeile 92)
- Create: `backend/src/main/java/com/household/manager/sun/SunEvent.java`
- Create: `backend/src/main/java/com/household/manager/sun/SunPhase.java`
- Create: `backend/src/main/java/com/household/manager/sun/SunDayTimes.java`
- Test: `backend/src/test/java/com/household/manager/sun/SunDayTimesTest.java`
- Test: `backend/src/test/java/com/household/manager/sun/SunEventTest.java`

- [ ] **Step 1: Abhängigkeit eintragen**

In `backend/pom.xml` direkt hinter dem `commons-csv`-Dependency-Block einfügen:

```xml
        <!-- Sonnenauf-/-untergang und Daemmerung fuer Flows (nur im Paket sun/ importiert) -->
        <dependency>
            <groupId>org.shredzone.commons</groupId>
            <artifactId>commons-suncalc</artifactId>
            <version>3.11</version>
        </dependency>
```

- [ ] **Step 2: Failing Tests für die Grundtypen schreiben**

`backend/src/test/java/com/household/manager/sun/SunEventTest.java`:

```java
package com.household.manager.sun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SunEventTest {

    @Test
    void fromKeyAcceptsTheFourKeywordsCaseInsensitiveAndTrimmed() {
        assertEquals(Optional.of(SunEvent.DAWN), SunEvent.fromKey("dawn"));
        assertEquals(Optional.of(SunEvent.SUNRISE), SunEvent.fromKey(" sunrise "));
        assertEquals(Optional.of(SunEvent.SUNSET), SunEvent.fromKey("SUNSET"));
        assertEquals(Optional.of(SunEvent.DUSK), SunEvent.fromKey("dusk"));
    }

    @Test
    void fromKeyRejectsUnknownAndNull() {
        assertTrue(SunEvent.fromKey("noon").isEmpty());
        assertTrue(SunEvent.fromKey("").isEmpty());
        assertTrue(SunEvent.fromKey(null).isEmpty());
    }

    @Test
    void keysListsAllFourInStableOrder() {
        assertEquals(List.of("dawn", "sunrise", "sunset", "dusk"), SunEvent.keys());
    }
}
```

`backend/src/test/java/com/household/manager/sun/SunDayTimesTest.java`:

```java
package com.household.manager.sun;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SunDayTimesTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private static ZonedDateTime at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    // dawn 06:35, sunrise 07:07, sunset 19:10, dusk 19:42
    private static final SunDayTimes TIMES = new SunDayTimes(DAY, at("06:35"), at("07:07"), at("19:10"), at("19:42"));

    @Test
    void phaseIsDayBetweenSunriseInclusiveAndSunsetExclusive() {
        assertEquals(SunPhase.DAY, TIMES.phaseAt(at("07:07")));
        assertEquals(SunPhase.DAY, TIMES.phaseAt(at("12:00")));
        assertEquals(SunPhase.DUSK, TIMES.phaseAt(at("19:10")));
    }

    @Test
    void phaseIsDuskBetweenSunsetAndCivilDusk() {
        assertEquals(SunPhase.DUSK, TIMES.phaseAt(at("19:30")));
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("19:42")));
    }

    @Test
    void phaseIsDawnBetweenCivilDawnAndSunrise() {
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("06:34")));
        assertEquals(SunPhase.DAWN, TIMES.phaseAt(at("06:35")));
        assertEquals(SunPhase.DAWN, TIMES.phaseAt(at("07:06")));
    }

    @Test
    void phaseIsNightAroundMidnight() {
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("00:00")));
        assertEquals(SunPhase.NIGHT, TIMES.phaseAt(at("23:59")));
    }

    @Test
    void timeOfReturnsTheMatchingMoment() {
        assertEquals(at("06:35"), TIMES.timeOf(SunEvent.DAWN));
        assertEquals(at("07:07"), TIMES.timeOf(SunEvent.SUNRISE));
        assertEquals(at("19:10"), TIMES.timeOf(SunEvent.SUNSET));
        assertEquals(at("19:42"), TIMES.timeOf(SunEvent.DUSK));
    }

    @Test
    void phaseKeysAreLowercase() {
        assertEquals("day", SunPhase.DAY.key());
        assertEquals("dusk", SunPhase.DUSK.key());
        assertEquals("night", SunPhase.NIGHT.key());
        assertEquals("dawn", SunPhase.DAWN.key());
    }
}
```

- [ ] **Step 3: Tests laufen lassen — sie müssen scheitern (Kompilierfehler)**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest='SunEventTest,SunDayTimesTest'
```
Erwartet: `COMPILATION ERROR` (Klassen `SunEvent`, `SunPhase`, `SunDayTimes` unbekannt).

- [ ] **Step 4: Grundtypen implementieren**

`backend/src/main/java/com/household/manager/sun/SunEvent.java`:

```java
package com.household.manager.sun;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Die vier Sonnenereignisse, die Flows kennen. Einzige Definition der
 * Schluesselwoerter {@code dawn}/{@code sunrise}/{@code sunset}/{@code dusk} —
 * Trigger-Node und Ausdrucks-Parser fragen dieses Enum.
 *
 * <p>{@code dawn}/{@code dusk} sind Beginn bzw. Ende der <b>buergerlichen</b>
 * Daemmerung (Sonne 6 Grad unter dem Horizont).
 */
public enum SunEvent {
    DAWN("dawn"),
    SUNRISE("sunrise"),
    SUNSET("sunset"),
    DUSK("dusk");

    /** Groesster erlaubter Versatz in Minuten (beide Richtungen) — schuetzt vor Tippfehlern wie -3000. */
    public static final int MAX_OFFSET_MINUTES = 240;

    private final String key;

    SunEvent(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<SunEvent> fromKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(e -> e.key.equals(normalized)).findFirst();
    }

    /** Schluesselwoerter in Deklarationsreihenfolge (fuer den Node-Katalog). */
    public static List<String> keys() {
        return Arrays.stream(values()).map(SunEvent::key).toList();
    }
}
```

`backend/src/main/java/com/household/manager/sun/SunPhase.java`:

```java
package com.household.manager.sun;

import java.util.Locale;

/**
 * Tagesphase, wie sie {@code sensor.sun} als State meldet. Halboffene Fenster,
 * Regel in {@link SunDayTimes#phaseAt}.
 */
public enum SunPhase {
    DAY,
    DUSK,
    NIGHT,
    DAWN;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
```

`backend/src/main/java/com/household/manager/sun/SunDayTimes.java`:

```java
package com.household.manager.sun;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Die vier Sonnenzeiten eines Kalendertages (Haushaltszeit) und die daraus
 * abgeleitete Phasenregel — eine reine Funktion ohne Uhr, damit sie testbar ist.
 *
 * <p>Fenster sind halboffen wie {@code TimeWindow}: {@code [sunrise, sunset)} = DAY,
 * {@code [sunset, dusk)} = DUSK, {@code [dawn, sunrise)} = DAWN, sonst NIGHT.
 */
public record SunDayTimes(
        LocalDate date,
        ZonedDateTime dawn,
        ZonedDateTime sunrise,
        ZonedDateTime sunset,
        ZonedDateTime dusk
) {

    public SunDayTimes {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dawn, "dawn");
        Objects.requireNonNull(sunrise, "sunrise");
        Objects.requireNonNull(sunset, "sunset");
        Objects.requireNonNull(dusk, "dusk");
    }

    public ZonedDateTime timeOf(SunEvent event) {
        return switch (event) {
            case DAWN -> dawn;
            case SUNRISE -> sunrise;
            case SUNSET -> sunset;
            case DUSK -> dusk;
        };
    }

    public SunPhase phaseAt(ZonedDateTime moment) {
        if (!moment.isBefore(sunrise) && moment.isBefore(sunset)) {
            return SunPhase.DAY;
        }
        if (!moment.isBefore(sunset) && moment.isBefore(dusk)) {
            return SunPhase.DUSK;
        }
        if (!moment.isBefore(dawn) && moment.isBefore(sunrise)) {
            return SunPhase.DAWN;
        }
        return SunPhase.NIGHT;
    }
}
```

- [ ] **Step 5: Tests laufen lassen — grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest='SunEventTest,SunDayTimesTest'
```
Erwartet: `Tests run: 9, Failures: 0, Errors: 0` (kein Fehlerblock im Output).

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/household/manager/sun backend/src/test/java/com/household/manager/sun
git commit -m "feat(sun): Grundtypen fuer Sonnenereignisse und Tagesphasen, commons-suncalc als Abhaengigkeit" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: `SunTimesService` — einzige Definition der Sonnenzeiten

**Files:**
- Create: `backend/src/main/java/com/household/manager/sun/SunTimesService.java`
- Test: `backend/src/test/java/com/household/manager/sun/SunTimesServiceTest.java`

- [ ] **Step 1: Failing Test schreiben**

Berlin (52.52 N, 13.405 O) als Testort — bekannte Werte: 21.06.2026 Aufgang ≈ 04:43, Untergang ≈ 21:33 (CEST); 21.12.2026 Aufgang ≈ 08:15, Untergang ≈ 15:54 (CET). Toleranz ±3 Minuten, weil die Referenzwerte selbst minutengenau gerundet sind.

```java
package com.household.manager.sun;

import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SunTimesServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final TractiveHomeSettings BERLIN_HOME =
            new TractiveHomeSettings(52.52, 13.405, 100, 500, 60, 30, "Zuhause");
    private static final TractiveHomeSettings NO_HOME =
            new TractiveHomeSettings(null, null, 100, 500, 60, 30, "Zuhause");

    @Mock
    private TractiveHomeSettingsService homeSettings;

    private SunTimesService serviceAt(String isoLocalDateTime) {
        var instant = ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocalDateTime), BERLIN).toInstant();
        return new SunTimesService(homeSettings, Clock.fixed(instant, BERLIN));
    }

    private static void assertWithinMinutes(String expectedLocalTime, ZonedDateTime actual, int toleranceMinutes) {
        LocalTime expected = LocalTime.parse(expectedLocalTime);
        long diff = Math.abs(Duration.between(expected, actual.toLocalTime()).toMinutes());
        assertTrue(diff <= toleranceMinutes,
                () -> "erwartet " + expected + " +/-" + toleranceMinutes + " min, war " + actual.toLocalTime());
    }

    @Test
    void midsummerInBerlin() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunDayTimes t = serviceAt("2026-06-21T12:00").timesFor(LocalDate.of(2026, 6, 21)).orElseThrow();

        assertWithinMinutes("04:43", t.sunrise(), 3);
        assertWithinMinutes("21:33", t.sunset(), 3);
        assertEquals(BERLIN, t.sunrise().getZone());
        assertEquals(LocalDate.of(2026, 6, 21), t.date());
    }

    @Test
    void midwinterInBerlin() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunDayTimes t = serviceAt("2026-12-21T12:00").timesFor(LocalDate.of(2026, 12, 21)).orElseThrow();

        assertWithinMinutes("08:15", t.sunrise(), 3);
        assertWithinMinutes("15:54", t.sunset(), 3);
    }

    @Test
    void civilTwilightSurroundsSunriseAndSunsetOnTheSameDay() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunDayTimes t = serviceAt("2026-09-21T12:00").timesFor(LocalDate.of(2026, 9, 21)).orElseThrow();

        assertTrue(t.dawn().isBefore(t.sunrise()));
        assertTrue(t.sunrise().isBefore(t.sunset()));
        assertTrue(t.sunset().isBefore(t.dusk()));
        long duskMinutes = Duration.between(t.sunset(), t.dusk()).toMinutes();
        assertTrue(duskMinutes >= 25 && duskMinutes <= 70, "buergerliche Daemmerung war " + duskMinutes + " min");
        assertEquals(LocalDate.of(2026, 9, 21), t.dawn().toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 21), t.dusk().toLocalDate());
    }

    @Test
    void withoutHomeCoordinatesThereAreNoTimes() {
        when(homeSettings.getSettings()).thenReturn(NO_HOME);
        assertTrue(serviceAt("2026-09-21T12:00").timesFor(LocalDate.of(2026, 9, 21)).isEmpty());
    }

    @Test
    void readingNeverThrows() {
        when(homeSettings.getSettings()).thenThrow(new IllegalStateException("DB weg"));
        assertTrue(serviceAt("2026-09-21T12:00").timesFor(LocalDate.of(2026, 9, 21)).isEmpty());
    }

    @Test
    void phaseAtUsesTheDayOfTheMomentInHouseholdZone() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunTimesService service = serviceAt("2026-09-21T12:00");

        assertEquals(Optional.of(SunPhase.DAY), service.phaseAt(ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, BERLIN)));
        assertEquals(Optional.of(SunPhase.NIGHT), service.phaseAt(ZonedDateTime.of(2026, 9, 21, 23, 30, 0, 0, BERLIN)));
    }

    @Test
    void phaseAtIsEmptyWithoutHome() {
        when(homeSettings.getSettings()).thenReturn(NO_HOME);
        assertTrue(serviceAt("2026-09-21T12:00").phaseAt(ZonedDateTime.of(2026, 9, 21, 12, 0, 0, 0, BERLIN)).isEmpty());
    }

    @Test
    void elevationIsPositiveAtNoonAndNegativeAtMidnight() {
        when(homeSettings.getSettings()).thenReturn(BERLIN_HOME);
        SunTimesService service = serviceAt("2026-06-21T12:00");

        double noon = service.elevationAt(ZonedDateTime.of(2026, 6, 21, 13, 0, 0, 0, BERLIN)).orElseThrow();
        double midnight = service.elevationAt(ZonedDateTime.of(2026, 6, 21, 1, 0, 0, 0, BERLIN)).orElseThrow();
        assertTrue(noon > 55 && noon < 65, "Mittagshoehe war " + noon);
        assertTrue(midnight < 0, "Mitternachtshoehe war " + midnight);
    }

    @Test
    void elevationIsEmptyWithoutHome() {
        when(homeSettings.getSettings()).thenReturn(NO_HOME);
        assertTrue(serviceAt("2026-06-21T12:00").elevationAt(ZonedDateTime.of(2026, 6, 21, 13, 0, 0, 0, BERLIN)).isEmpty());
    }
}
```

- [ ] **Step 2: Test laufen lassen — Kompilierfehler**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SunTimesServiceTest
```
Erwartet: `COMPILATION ERROR` (`SunTimesService` unbekannt).

- [ ] **Step 3: Service implementieren**

`backend/src/main/java/com/household/manager/sun/SunTimesService.java`:

```java
package com.household.manager.sun;

import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.shredzone.commons.suncalc.SunPosition;
import org.shredzone.commons.suncalc.SunTimes;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Einzige Definition der Sonnenzeiten des Haushalts. Entitaet ({@code sensor.sun}),
 * Trigger-Node ({@code sun-trigger}) und Sonnenausdruecke in {@code time-condition}
 * fragen ausschliesslich diese Klasse, damit „Sonnenuntergang" nie zweimal
 * definiert ist. Nur dieses Paket importiert {@code commons-suncalc}.
 *
 * <p>Die Koordinaten sind das Hundetracker-Zuhause ({@link TractiveHomeSettingsService})
 * — bewusste Kopplung (Nutzerentscheidung 2026-09-21), bei jedem Aufruf frisch
 * gelesen, damit eine Aenderung im Admin beim naechsten Minutenlauf gilt.
 *
 * <p><b>Lesen wirft nie.</b> Ohne Koordinaten, bei einem Fehler der Einstellungen
 * oder wenn die Bibliothek fuer ein Ereignis {@code null} liefert (Polarfall), kommt
 * {@link Optional#empty()} — die Aufrufer laufen minuetlich bzw. im Flow-Executor,
 * eine Ausnahme hier duerfte sie nicht lahmlegen.
 */
@Service
@Slf4j
public class SunTimesService {

    private final TractiveHomeSettingsService homeSettings;
    private final Clock clock;

    public SunTimesService(TractiveHomeSettingsService homeSettings, Clock clock) {
        this.homeSettings = homeSettings;
        this.clock = clock;
    }

    /** Die vier Sonnenzeiten des Kalendertages {@code date} in Haushaltszeit. */
    public Optional<SunDayTimes> timesFor(LocalDate date) {
        Optional<TractiveHomeSettings> home = configuredHome();
        if (home.isEmpty()) {
            return Optional.empty();
        }
        try {
            ZoneId zone = clock.getZone();
            SunTimes visual = compute(date, home.get(), SunTimes.Twilight.VISUAL, zone);
            SunTimes civil = compute(date, home.get(), SunTimes.Twilight.CIVIL, zone);
            if (visual.getRise() == null || visual.getSet() == null
                    || civil.getRise() == null || civil.getSet() == null) {
                log.warn("Sonnenzeiten fuer {} unvollstaendig (Polarfall?) – keine Sonnenzeiten", date);
                return Optional.empty();
            }
            return Optional.of(new SunDayTimes(date, civil.getRise(), visual.getRise(), visual.getSet(), civil.getSet()));
        } catch (RuntimeException ex) {
            log.warn("Sonnenzeiten fuer {} nicht berechenbar: {}", date, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Phase zum Zeitpunkt {@code moment}, bewertet gegen die Sonnenzeiten seines Kalendertages (Haushaltszeit). */
    public Optional<SunPhase> phaseAt(ZonedDateTime moment) {
        LocalDate day = moment.withZoneSameInstant(clock.getZone()).toLocalDate();
        return timesFor(day).map(times -> times.phaseAt(moment));
    }

    /** Sonnenhoehe in Grad ueber dem Horizont (negativ = unter dem Horizont). */
    public Optional<Double> elevationAt(ZonedDateTime moment) {
        Optional<TractiveHomeSettings> home = configuredHome();
        if (home.isEmpty()) {
            return Optional.empty();
        }
        try {
            SunPosition position = SunPosition.compute()
                    .on(moment)
                    .at(home.get().homeLatitude(), home.get().homeLongitude())
                    .execute();
            return Optional.of(position.getAltitude());
        } catch (RuntimeException ex) {
            log.warn("Sonnenhoehe nicht berechenbar: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TractiveHomeSettings> configuredHome() {
        try {
            TractiveHomeSettings settings = homeSettings.getSettings();
            return settings.hasHomeCoordinates() ? Optional.of(settings) : Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Zuhause-Koordinaten nicht lesbar – keine Sonnenzeiten: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static SunTimes compute(LocalDate date, TractiveHomeSettings home, SunTimes.Twilight twilight, ZoneId zone) {
        return SunTimes.compute()
                .timezone(zone)
                .on(date)
                .at(home.homeLatitude(), home.homeLongitude())
                .twilight(twilight)
                .oneDay()
                .execute();
    }
}
```

- [ ] **Step 4: Test laufen lassen — grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SunTimesServiceTest
```
Erwartet: `Tests run: 9, Failures: 0`. Scheitert `midsummerInBerlin` mit einer Abweichung > 3 min, zuerst prüfen, ob `.timezone(zone)` vor `.on(date)` steht (sonst wird Mitternacht UTC als Tagesbeginn genommen und `oneDay()` findet u. U. den Untergang des Vortags).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/sun/SunTimesService.java backend/src/test/java/com/household/manager/sun/SunTimesServiceTest.java
git commit -m "feat(sun): SunTimesService als einzige Definition der Sonnenzeiten (Koordinaten aus dem Tractive-Zuhause, wirft nie)" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Entität `sensor.sun` (`SunEntityPublisher`)

**Files:**
- Modify: `backend/src/main/java/com/household/manager/entitystate/EntitySource.java` (ans Ende des Enums)
- Create: `backend/src/main/java/com/household/manager/sun/SunEntityPublisher.java`
- Modify: `backend/src/main/resources/application.properties` (hinter dem `presence.*`-Block)
- Test: `backend/src/test/java/com/household/manager/sun/SunEntityPublisherTest.java`

- [ ] **Step 1: `EntitySource.SUN` ergänzen**

In `EntitySource.java` hinter `CHARGING` (Komma nach `CHARGING` ergänzen):

```java
    CHARGING,
    /** Sonnenstand am Haushaltsstandort (sensor.sun; berechnet, keine externe Quelle). */
    SUN
```

- [ ] **Step 2: Failing Test schreiben**

```java
package com.household.manager.sun;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SunEntityPublisherTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    @Mock
    private SunTimesService sunTimes;
    @Mock
    private EntityStateService entityStateService;
    @Mock
    private EntityStateResponseMapper responseMapper;

    private static ZonedDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    private static SunDayTimes timesOf(LocalDate day) {
        return new SunDayTimes(day, at(day, "06:35"), at(day, "07:07"), at(day, "19:10"), at(day, "19:42"));
    }

    private SunEntityPublisher publisherAt(String time) {
        Clock clock = Clock.fixed(at(TODAY, time).toInstant(), BERLIN);
        return new SunEntityPublisher(sunTimes, entityStateService, responseMapper, clock);
    }

    private EntityStateUpdate reported() {
        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportState(captor.capture());
        return captor.getValue();
    }

    @BeforeEach
    void stubTimes() {
        lenient().when(sunTimes.timesFor(TODAY)).thenReturn(Optional.of(timesOf(TODAY)));
        lenient().when(sunTimes.timesFor(TOMORROW)).thenReturn(Optional.of(timesOf(TOMORROW)));
        lenient().when(sunTimes.elevationAt(any())).thenReturn(Optional.of(31.26));
    }

    @Test
    void reportsPhaseAndTodayTimesAsAttributes() {
        publisherAt("12:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("sensor.sun", update.entityId());
        assertEquals(EntityDomain.SENSOR, update.domain());
        assertEquals(EntitySource.SUN, update.source());
        assertEquals("day", update.state());
        assertEquals("06:35", update.attributes().get("dawn"));
        assertEquals("07:07", update.attributes().get("sunrise"));
        assertEquals("19:10", update.attributes().get("sunset"));
        assertEquals("19:42", update.attributes().get("dusk"));
        assertEquals(31.3, update.attributes().get("elevation"));
        assertFalse(update.attributes().containsKey("deviceClass"));
    }

    @Test
    void nextEventsPointToTodayWhileStillAheadOtherwiseTomorrow() {
        publisherAt("12:00").publish();

        Map<String, Object> attrs = reported().attributes();
        assertEquals(at(TOMORROW, "07:07").toLocalDateTime().toString(), attrs.get("nextSunrise"));
        assertEquals(at(TODAY, "19:10").toLocalDateTime().toString(), attrs.get("nextSunset"));
    }

    @Test
    void phaseFollowsTheClock() {
        publisherAt("19:20").publish();
        assertEquals("dusk", reported().state());
    }

    @Test
    void withoutHomeReportsUnavailableAndKeepsStoredAttributes() {
        when(sunTimes.timesFor(TODAY)).thenReturn(Optional.empty());
        EntityState existing = new EntityState();
        existing.setEntityId("sensor.sun");
        existing.setState("day");
        existing.setAttributes("{\"sunrise\":\"07:07\"}");
        when(entityStateService.getByEntityId("sensor.sun")).thenReturn(Optional.of(existing));
        when(responseMapper.parseAttributes("{\"sunrise\":\"07:07\"}")).thenReturn(Map.of("sunrise", "07:07"));

        publisherAt("12:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("unavailable", update.state());
        assertEquals("07:07", update.attributes().get("sunrise"));
    }

    @Test
    void withoutHomeAndWithoutEntityCreatesItAsUnavailable() {
        when(sunTimes.timesFor(TODAY)).thenReturn(Optional.empty());
        when(entityStateService.getByEntityId("sensor.sun")).thenReturn(Optional.empty());

        publisherAt("12:00").publish();

        EntityStateUpdate update = reported();
        assertEquals("unavailable", update.state());
        assertTrue(update.attributes().isEmpty());
    }

    @Test
    void alreadyUnavailableIsNotReportedAgain() {
        when(sunTimes.timesFor(TODAY)).thenReturn(Optional.empty());
        EntityState existing = new EntityState();
        existing.setEntityId("sensor.sun");
        existing.setState("unavailable");
        when(entityStateService.getByEntityId("sensor.sun")).thenReturn(Optional.of(existing));

        publisherAt("12:00").publish();

        verify(entityStateService, never()).reportState(any());
    }

    @Test
    void publishNeverThrows() {
        when(sunTimes.timesFor(TODAY)).thenThrow(new IllegalStateException("kaputt"));
        assertDoesNotThrow(() -> publisherAt("12:00").publish());
    }
}
```

- [ ] **Step 3: Test laufen lassen — Kompilierfehler**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SunEntityPublisherTest
```
Erwartet: `COMPILATION ERROR` (`SunEntityPublisher` unbekannt).

- [ ] **Step 4: Publisher implementieren**

`backend/src/main/java/com/household/manager/sun/SunEntityPublisher.java`:

```java
package com.household.manager.sun;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Spiegelt den Sonnenstand als Entitaet {@code sensor.sun} in den Entity-State-Layer:
 * State = Phase ({@code day}/{@code dusk}/{@code night}/{@code dawn}), Attribute die
 * heutigen Sonnenzeiten, die naechsten Auf-/Untergaenge und die Sonnenhoehe.
 *
 * <p>Laeuft minuetlich; der Entity-State-Layer feuert {@code EntityStateChangedEvent}
 * nur bei Wertaenderung, also viermal am Tag — die uebrigen Laeufe kosten nichts.
 *
 * <p>Ohne konfiguriertes Zuhause wird die Entitaet {@code unavailable} <b>mit
 * erhaltenen Attributen</b> (Muster Zigbee/Blink: {@code EntityStateWriter.upsert}
 * ueberschreibt sie sonst komplett) — nie eine geratene Phase. Sie wird auch dann
 * angelegt, damit im Entity-Katalog sichtbar ist, dass etwas fehlt.
 *
 * <p>Bewusst kein {@code deviceClass} (siehe Blink-Absatz in CLAUDE.md).
 */
@Component
@Slf4j
public class SunEntityPublisher {

    static final String ENTITY_ID = "sensor.sun";
    static final String FRIENDLY_NAME = "Sonne";
    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final SunTimesService sunTimes;
    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;
    private final Clock clock;

    public SunEntityPublisher(SunTimesService sunTimes,
                              EntityStateService entityStateService,
                              EntityStateResponseMapper responseMapper,
                              Clock clock) {
        this.sunTimes = sunTimes;
        this.entityStateService = entityStateService;
        this.responseMapper = responseMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sun.publish-interval-ms:60000}",
               initialDelayString = "${sun.publish-initial-delay-ms:15000}")
    public void publish() {
        try {
            publishNow();
        } catch (RuntimeException ex) {
            log.warn("Sonnenstand konnte nicht gemeldet werden: {}", ex.getMessage());
        }
    }

    private void publishNow() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        Optional<SunDayTimes> today = sunTimes.timesFor(now.toLocalDate());
        if (today.isEmpty()) {
            markUnavailable();
            return;
        }
        SunDayTimes times = today.get();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("dawn", HH_MM.format(times.dawn()));
        attributes.put("sunrise", HH_MM.format(times.sunrise()));
        attributes.put("sunset", HH_MM.format(times.sunset()));
        attributes.put("dusk", HH_MM.format(times.dusk()));
        nextOf(SunEvent.SUNRISE, times, now).ifPresent(v -> attributes.put("nextSunrise", v));
        nextOf(SunEvent.SUNSET, times, now).ifPresent(v -> attributes.put("nextSunset", v));
        sunTimes.elevationAt(now).ifPresent(e -> attributes.put("elevation", Math.round(e * 10.0) / 10.0));
        report(times.phaseAt(now).key(), attributes);
    }

    /** Das naechste Vorkommen nach jetzt: heute, wenn es noch bevorsteht, sonst morgen. */
    private Optional<String> nextOf(SunEvent event, SunDayTimes today, ZonedDateTime now) {
        ZonedDateTime todayAt = today.timeOf(event);
        if (todayAt.isAfter(now)) {
            return Optional.of(todayAt.toLocalDateTime().toString());
        }
        return sunTimes.timesFor(today.date().plusDays(1))
                .map(t -> t.timeOf(event).toLocalDateTime().toString());
    }

    private void markUnavailable() {
        Optional<EntityState> existing = entityStateService.getByEntityId(ENTITY_ID);
        if (existing.isPresent() && STATE_UNAVAILABLE.equals(existing.get().getState())) {
            return;
        }
        Map<String, Object> kept = existing
                .map(e -> responseMapper.parseAttributes(e.getAttributes()))
                .orElse(Map.of());
        report(STATE_UNAVAILABLE, kept);
    }

    private void report(String state, Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(ENTITY_ID)
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.SUN)
                .sourceRef("sun")
                .friendlyName(FRIENDLY_NAME)
                .state(state)
                .attributes(attributes)
                .build());
    }
}
```

- [ ] **Step 5: Properties ergänzen**

In `backend/src/main/resources/application.properties` hinter dem `presence.*`-Block:

```properties

# Sonnenstand (sensor.sun): Phase und Sonnenzeiten aus dem Tractive-Zuhause, minuetlich gespiegelt
sun.publish-interval-ms=60000
sun.publish-initial-delay-ms=15000
```

- [ ] **Step 6: Test laufen lassen — grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SunEntityPublisherTest
```
Erwartet: `Tests run: 7, Failures: 0`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/household/manager/entitystate/EntitySource.java backend/src/main/java/com/household/manager/sun/SunEntityPublisher.java backend/src/main/resources/application.properties backend/src/test/java/com/household/manager/sun/SunEntityPublisherTest.java
git commit -m "feat(sun): Entitaet sensor.sun mit Tagesphase, Sonnenzeiten und Sonnenhoehe; ohne Zuhause unavailable mit erhaltenen Attributen" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Trigger-Node `sun-trigger`

**Files:**
- Create: `backend/src/main/java/com/household/manager/flowengine/nodes/SunTriggerHandler.java`
- Test: `backend/src/test/java/com/household/manager/flowengine/nodes/SunTriggerHandlerTest.java`
- Modify: `backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java` (ein Test dazu)

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunDayTimes;
import com.household.manager.sun.SunTimesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SunTriggerHandlerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    @Mock
    private SunTimesService sunTimes;
    @Mock
    private TaskScheduler taskScheduler;

    private final ConcurrentMap<String, Object> state = new ConcurrentHashMap<>();
    private final List<FlowMessage> emitted = new ArrayList<>();
    private final ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
    private final ArgumentCaptor<Instant> instants = ArgumentCaptor.forClass(Instant.class);
    private ScheduledFuture<?> future;
    private NodeContext ctx;

    private static ZonedDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    private static SunDayTimes timesOf(LocalDate day) {
        return new SunDayTimes(day, at(day, "06:35"), at(day, "07:07"), at(day, "19:10"), at(day, "19:42"));
    }

    private SunTriggerHandler handlerAt(String time) {
        return new SunTriggerHandler(sunTimes, Clock.fixed(at(TODAY, time).toInstant(), BERLIN));
    }

    private static NodeConfig config(String event, Object offset) {
        return offset == null ? new NodeConfig(Map.of("event", event))
                : new NodeConfig(Map.of("event", event, "offsetMinutes", offset));
    }

    @BeforeEach
    void setUp() {
        future = mock(ScheduledFuture.class);
        ctx = new NodeContext() {
            public long flowId() { return 7L; }
            public String nodeId() { return "sun1"; }
            public ConcurrentMap<String, Object> state() { return state; }
            public void emit(int port, FlowMessage message) { emitted.add(message); }
            public TaskScheduler scheduler() { return taskScheduler; }
            public void debug(String label, FlowMessage message) { }
        };
        lenient().when(sunTimes.timesFor(any(LocalDate.class)))
                .thenAnswer(inv -> Optional.of(timesOf(inv.getArgument(0))));
        lenient().doReturn(future).when(taskScheduler).schedule(tasks.capture(), instants.capture());
    }

    @Test
    void schedulesTodaysEventWhenStillAhead() {
        handlerAt("12:00").register(config("sunset", null), ctx);
        assertEquals(at(TODAY, "19:10").toInstant(), instants.getValue());
    }

    @Test
    void appliesNegativeOffset() {
        handlerAt("12:00").register(config("sunset", -30), ctx);
        assertEquals(at(TODAY, "18:40").toInstant(), instants.getValue());
    }

    @Test
    void offsetMayArriveAsString() {
        handlerAt("12:00").register(config("dusk", "15"), ctx);
        assertEquals(at(TODAY, "19:57").toInstant(), instants.getValue());
    }

    @Test
    void rollsOverToTomorrowWhenTodaysEventIsPast() {
        handlerAt("20:00").register(config("sunset", null), ctx);
        assertEquals(at(TODAY.plusDays(1), "19:10").toInstant(), instants.getValue());
    }

    @Test
    void negativeOffsetThatIsAlreadyPastRollsOverToo() {
        // 18:50 liegt zwischen sunset-30 (18:40) und sunset (19:10): heute ist vorbei, morgen zaehlt.
        handlerAt("18:50").register(config("sunset", -30), ctx);
        assertEquals(at(TODAY.plusDays(1), "18:40").toInstant(), instants.getValue());
    }

    @Test
    void firingEmitsMessageAndReschedulesForTheNextDay() {
        handlerAt("12:00").register(config("sunrise", null), ctx);
        // Registrierung um 12:00: Aufgang heute ist vorbei -> morgen 07:07.
        assertEquals(at(TODAY.plusDays(1), "07:07").toInstant(), instants.getValue());

        tasks.getValue().run();

        assertEquals(1, emitted.size());
        FlowMessage msg = emitted.get(0);
        assertEquals("sunrise", msg.get("sunEvent"));
        assertEquals(0, msg.get("offsetMinutes"));
        assertEquals("sun1", msg.get("triggerNodeId"));
        assertEquals(at(TODAY.plusDays(1), "07:07").toLocalDateTime(), msg.get("scheduledFor"));
        // Neu geplant fuer uebermorgen — nicht erneut fuer den gerade gefeuerten Zeitpunkt.
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        assertEquals(at(TODAY.plusDays(2), "07:07").toInstant(), instants.getValue());
    }

    @Test
    void cleanupCancelsFutureAndPreventsRescheduling() {
        Runnable cleanup = handlerAt("12:00").register(config("sunset", null), ctx);

        cleanup.run();
        verify(future).cancel(false);

        tasks.getValue().run();
        assertTrue(emitted.isEmpty());
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void withoutHomeRetriesInAnHourInsteadOfFailing() {
        when(sunTimes.timesFor(any(LocalDate.class))).thenReturn(Optional.empty());
        SunTriggerHandler handler = handlerAt("12:00");

        assertDoesNotThrow(() -> handler.register(config("sunset", null), ctx));
        assertEquals(at(TODAY, "13:00").toInstant(), instants.getValue());
        assertTrue(emitted.isEmpty());

        // Beim Wiederholungsversuch ist das Zuhause da -> echtes Ereignis geplant.
        when(sunTimes.timesFor(any(LocalDate.class))).thenAnswer(inv -> Optional.of(timesOf(inv.getArgument(0))));
        tasks.getValue().run();
        assertEquals(at(TODAY, "19:10").toInstant(), instants.getValue());
        assertTrue(emitted.isEmpty());
    }

    @Test
    void validateAcceptsEventWithAndWithoutOffset() {
        SunTriggerHandler h = handlerAt("12:00");
        assertTrue(h.validate(config("sunset", null)).isEmpty());
        assertTrue(h.validate(config("dawn", -240)).isEmpty());
        assertTrue(h.validate(config("dusk", "240")).isEmpty());
    }

    @Test
    void validateRejectsMissingOrUnknownEvent() {
        SunTriggerHandler h = handlerAt("12:00");
        assertFalse(h.validate(NodeConfig.empty()).isEmpty());
        assertFalse(h.validate(config("noon", null)).isEmpty());
    }

    @Test
    void validateRejectsOffsetOutOfRangeOrNonInteger() {
        SunTriggerHandler h = handlerAt("12:00");
        assertFalse(h.validate(config("sunset", 241)).isEmpty());
        assertFalse(h.validate(config("sunset", -3000)).isEmpty());
        assertFalse(h.validate(config("sunset", "halb")).isEmpty());
        assertFalse(h.validate(config("sunset", 1.5)).isEmpty());
    }

    @Test
    void watchesNoEntity() {
        assertTrue(handlerAt("12:00").watchedEntityId(NodeConfig.empty()).isEmpty());
    }
}
```

In `NodeCatalogFieldsTest.java` einen Test ergänzen (hinter `scheduleTriggerHasCronField`):

```java
    @Test
    void sunTriggerHasEventEnumAndOptionalOffset() {
        var h = new SunTriggerHandler(null, null);
        assertEquals(NodeFieldType.ENUM, field(h.fields(), "event").type());
        assertEquals(List.of("dawn", "sunrise", "sunset", "dusk"), field(h.fields(), "event").options());
        assertTrue(field(h.fields(), "event").required());
        assertEquals(NodeFieldType.NUMBER, field(h.fields(), "offsetMinutes").type());
        assertFalse(field(h.fields(), "offsetMinutes").required());
        assertEquals(List.of("Ausgang"), h.portLabels());
        assertEquals("sun-trigger", h.type());
    }
```

- [ ] **Step 2: Tests laufen lassen — Kompilierfehler**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest='SunTriggerHandlerTest,NodeCatalogFieldsTest'
```
Erwartet: `COMPILATION ERROR` (`SunTriggerHandler` unbekannt).

- [ ] **Step 3: Handler implementieren**

`backend/src/main/java/com/household/manager/flowengine/nodes/SunTriggerHandler.java`:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunDayTimes;
import com.household.manager.sun.SunEvent;
import com.household.manager.sun.SunTimesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Trigger „zu einem Sonnenereignis, optional mit Versatz". Plant sich nach jedem
 * Feuern selbst fuer das naechste Vorkommen neu, deshalb greifen geaenderte
 * Koordinaten spaetestens am Folgetag. Geplant wird ein {@link Instant}
 * (zeitumstellungssicher), gerechnet mit dem {@link Clock}-Bean.
 *
 * <p>Ohne konfiguriertes Zuhause bricht der Deploy nicht ab: der Node versucht es
 * stuendlich erneut (Warnung im Log) — der Flow soll sich anlegen lassen, bevor die
 * Koordinaten stehen (dieselbe Toleranz wie {@code helper-set}).
 *
 * <p>Verpasste Ereignisse waehrend eines Backend-Ausfalls werden nicht nachgefeuert
 * (wie {@code schedule-trigger}); wer den Zustand braucht, baut auf {@code sensor.sun}.
 */
@Component
@Slf4j
public class SunTriggerHandler implements TriggerNodeHandler {

    static final String EVENT = "event";
    static final String OFFSET = "offsetMinutes";
    static final Duration RETRY_WITHOUT_HOME = Duration.ofMinutes(60);
    private static final String STATE_FUTURE = "future";
    private static final String STATE_CANCELLED = "cancelled";

    private final SunTimesService sunTimes;
    private final Clock clock;

    public SunTriggerHandler(SunTimesService sunTimes, Clock clock) {
        this.sunTimes = sunTimes;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "sun-trigger";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public Optional<String> watchedEntityId(NodeConfig config) {
        return Optional.empty();
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.enumField(EVENT, "Ereignis", true, SunEvent.keys()),
                NodeFieldDescriptor.field(OFFSET, "Versatz in Minuten (-240 … 240)", NodeFieldType.NUMBER, false));
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string(EVENT).isEmpty()) {
            errors.add(EVENT + " fehlt");
        } else if (SunEvent.fromKey(config.string(EVENT).get()).isEmpty()) {
            errors.add(EVENT + " ist keines von " + SunEvent.keys() + ": '" + config.string(EVENT).get() + "'");
        }
        parseOffset(config, errors);
        return errors;
    }

    @Override
    public Runnable register(NodeConfig config, NodeContext ctx) {
        SunEvent event = SunEvent.fromKey(config.string(EVENT).orElseThrow()).orElseThrow();
        int offset = parseOffset(config, new ArrayList<>()).orElse(0);
        ctx.state().remove(STATE_CANCELLED);
        scheduleNext(event, offset, ZonedDateTime.now(clock), ctx);
        return () -> {
            ctx.state().put(STATE_CANCELLED, Boolean.TRUE);
            cancelCurrent(ctx);
        };
    }

    /**
     * Plant das erste Vorkommen von {@code event + offset} nach {@code after}.
     * Ohne Sonnenzeiten (kein Zuhause) stattdessen einen Wiederholungsversuch.
     */
    private void scheduleNext(SunEvent event, int offset, ZonedDateTime after, NodeContext ctx) {
        if (isCancelled(ctx)) {
            return;
        }
        Optional<ZonedDateTime> next = nextOccurrence(event, offset, after);
        Runnable task;
        Instant at;
        if (next.isPresent()) {
            ZonedDateTime scheduledFor = next.get();
            at = scheduledFor.toInstant();
            task = () -> fire(event, offset, scheduledFor, ctx);
        } else {
            log.warn("sun-trigger (Flow {}, Node {}): keine Sonnenzeiten (kein Zuhause konfiguriert?) – naechster Versuch in {} min",
                    ctx.flowId(), ctx.nodeId(), RETRY_WITHOUT_HOME.toMinutes());
            at = after.toInstant().plus(RETRY_WITHOUT_HOME);
            task = () -> scheduleNext(event, offset, ZonedDateTime.now(clock), ctx);
        }
        ScheduledFuture<?> future = ctx.scheduler().schedule(task, at);
        ctx.state().put(STATE_FUTURE, future);
        if (isCancelled(ctx)) {
            // Cleanup lief zwischen Pruefung und Einplanung: kein Geister-Timer hinterlassen.
            future.cancel(false);
        }
    }

    private void fire(SunEvent event, int offset, ZonedDateTime scheduledFor, NodeContext ctx) {
        if (isCancelled(ctx)) {
            return;
        }
        ctx.emit(0, FlowMessage.of(Map.of(
                "sunEvent", event.key(),
                "offsetMinutes", offset,
                "scheduledFor", scheduledFor.toLocalDateTime(),
                "timestamp", LocalDateTime.now(clock),
                "triggerNodeId", ctx.nodeId())));
        // Nie vor dem gerade gefeuerten Zeitpunkt weitersuchen — der Scheduler darf
        // Millisekunden frueh dran sein, sonst wuerde dasselbe Ereignis erneut geplant.
        ZonedDateTime now = ZonedDateTime.now(clock);
        scheduleNext(event, offset, now.isAfter(scheduledFor) ? now : scheduledFor, ctx);
    }

    /** Heute, morgen und uebermorgen decken auch einen negativen Versatz ab, der das heutige Ereignis schon vorbeischiebt. */
    Optional<ZonedDateTime> nextOccurrence(SunEvent event, int offset, ZonedDateTime after) {
        LocalDate day = after.withZoneSameInstant(clock.getZone()).toLocalDate();
        for (int d = 0; d <= 2; d++) {
            Optional<SunDayTimes> times = sunTimes.timesFor(day.plusDays(d));
            if (times.isEmpty()) {
                return Optional.empty();
            }
            ZonedDateTime candidate = times.get().timeOf(event).plusMinutes(offset);
            if (candidate.isAfter(after)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isCancelled(NodeContext ctx) {
        return Boolean.TRUE.equals(ctx.state().get(STATE_CANCELLED));
    }

    private static void cancelCurrent(NodeContext ctx) {
        if (ctx.state().get(STATE_FUTURE) instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    private static Optional<Integer> parseOffset(NodeConfig config, List<String> errors) {
        Optional<String> raw = config.string(OFFSET).map(String::trim).filter(s -> !s.isEmpty());
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        int offset;
        try {
            offset = Integer.parseInt(raw.get());
        } catch (NumberFormatException ex) {
            errors.add(OFFSET + " ist keine ganze Zahl: '" + raw.get() + "'");
            return Optional.empty();
        }
        if (Math.abs(offset) > SunEvent.MAX_OFFSET_MINUTES) {
            errors.add(OFFSET + " muss zwischen -" + SunEvent.MAX_OFFSET_MINUTES + " und "
                    + SunEvent.MAX_OFFSET_MINUTES + " liegen: " + offset);
            return Optional.empty();
        }
        return Optional.of(offset);
    }
}
```

Hinweis: `parseOffset` liest bewusst über `config.string` + `Integer.parseInt` statt `config.integer`, weil `integer()` einen Wert `1.5` (Number) klaglos auf `1` kürzt — der Test `validateRejectsOffsetOutOfRangeOrNonInteger` hält fest, dass `1.5` abgelehnt wird. `String.valueOf(1.5)` ist `"1.5"`, was `parseInt` verwirft.

- [ ] **Step 4: Tests laufen lassen — grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest='SunTriggerHandlerTest,NodeCatalogFieldsTest'
```
Erwartet: alle grün. Sollte `firingEmitsMessageAndReschedulesForTheNextDay` mit `ClassCastException` für `offsetMinutes` scheitern, prüfen, dass `Map.of(...)` den `int offset` (autoboxed `Integer`) enthält, nicht einen String.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/SunTriggerHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/SunTriggerHandlerTest.java backend/src/test/java/com/household/manager/flowengine/nodes/NodeCatalogFieldsTest.java
git commit -m "feat(flows): Trigger-Node sun-trigger mit Versatz und Selbst-Neuplanung" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Sonnenausdrücke — `SunTimeExpression`

**Files:**
- Create: `backend/src/main/java/com/household/manager/sun/SunTimeExpression.java`
- Test: `backend/src/test/java/com/household/manager/sun/SunTimeExpressionTest.java`

- [ ] **Step 1: Failing Test schreiben**

```java
package com.household.manager.sun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SunTimeExpressionTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    @Mock
    private SunTimesService sunTimes;

    private static ZonedDateTime at(String time) {
        return DAY.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    private static final SunDayTimes TIMES = new SunDayTimes(DAY, at("06:35"), at("07:07"), at("19:10"), at("19:42"));

    @Test
    void parsesFixedTime() {
        assertEquals(new SunTimeExpression.Fixed(LocalTime.of(5, 0)), SunTimeExpression.parse("05:00"));
        assertEquals(new SunTimeExpression.Fixed(LocalTime.of(23, 30)), SunTimeExpression.parse(" 23:30 "));
    }

    @Test
    void parsesSunEventWithAndWithoutOffset() {
        assertEquals(new SunTimeExpression.Relative(SunEvent.SUNSET, 0), SunTimeExpression.parse("sunset"));
        assertEquals(new SunTimeExpression.Relative(SunEvent.SUNSET, -30), SunTimeExpression.parse("sunset-30"));
        assertEquals(new SunTimeExpression.Relative(SunEvent.DAWN, 15), SunTimeExpression.parse("dawn+15"));
        assertEquals(new SunTimeExpression.Relative(SunEvent.DUSK, 0), SunTimeExpression.parse("DUSK"));
    }

    @Test
    void rejectsGarbageSpacesInsideAndOffsetOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("noon"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("25:00"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("sunset -30"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("sunset-300"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse("sunset+"));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SunTimeExpression.parse(null));
    }

    @Test
    void fixedResolvesWithoutAskingTheService() {
        Optional<LocalTime> t = SunTimeExpression.parse("05:00").resolve(DAY, sunTimes);
        assertEquals(Optional.of(LocalTime.of(5, 0)), t);
    }

    @Test
    void relativeResolvesAgainstThatDaysTimesPlusOffset() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(TIMES));
        assertEquals(Optional.of(LocalTime.of(18, 40)), SunTimeExpression.parse("sunset-30").resolve(DAY, sunTimes));
        assertEquals(Optional.of(LocalTime.of(6, 35)), SunTimeExpression.parse("dawn").resolve(DAY, sunTimes));
    }

    @Test
    void relativeIsEmptyWithoutHome() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.empty());
        assertTrue(SunTimeExpression.parse("sunset").resolve(DAY, sunTimes).isEmpty());
    }
}
```

- [ ] **Step 2: Test laufen lassen — Kompilierfehler**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SunTimeExpressionTest
```
Erwartet: `COMPILATION ERROR`.

- [ ] **Step 3: Parser implementieren**

`backend/src/main/java/com/household/manager/sun/SunTimeExpression.java`:

```java
package com.household.manager.sun;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ein Zeitpunkt im Tagesverlauf — entweder fest ({@code HH:mm}) oder relativ zu
 * einem Sonnenereignis ({@code sunset-30}, {@code dawn}). Grammatik:
 *
 * <pre>
 * HH:mm  |  dawn | sunrise | sunset | dusk  [ +N | -N ]     (N Minuten, |N| ≤ 240)
 * </pre>
 *
 * Leerzeichen um den Ausdruck werden toleriert, innerhalb nicht ({@code sunset -30}
 * ist ein Fehler). Genutzt von {@code time-condition}.
 */
public sealed interface SunTimeExpression permits SunTimeExpression.Fixed, SunTimeExpression.Relative {

    Pattern RELATIVE = Pattern.compile("^(dawn|sunrise|sunset|dusk)([+-]\\d{1,4})?$");

    record Fixed(LocalTime time) implements SunTimeExpression {
        @Override
        public Optional<LocalTime> resolve(LocalDate date, SunTimesService sunTimes) {
            return Optional.of(time);
        }
    }

    record Relative(SunEvent event, int offsetMinutes) implements SunTimeExpression {
        @Override
        public Optional<LocalTime> resolve(LocalDate date, SunTimesService sunTimes) {
            return sunTimes.timesFor(date)
                    .map(times -> times.timeOf(event).plusMinutes(offsetMinutes).toLocalTime());
        }
    }

    /** Die Uhrzeit dieses Ausdrucks am Tag {@code date}; leer, wenn keine Sonnenzeiten vorliegen. */
    Optional<LocalTime> resolve(LocalDate date, SunTimesService sunTimes);

    /** @throws IllegalArgumentException mit lesbarem Text, wenn der Ausdruck nicht der Grammatik entspricht */
    static SunTimeExpression parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Zeitausdruck fehlt");
        }
        String text = raw.trim();
        Matcher m = RELATIVE.matcher(text.toLowerCase(Locale.ROOT));
        if (m.matches()) {
            SunEvent event = SunEvent.fromKey(m.group(1)).orElseThrow();
            int offset = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if (Math.abs(offset) > SunEvent.MAX_OFFSET_MINUTES) {
                throw new IllegalArgumentException("Versatz muss zwischen -" + SunEvent.MAX_OFFSET_MINUTES
                        + " und " + SunEvent.MAX_OFFSET_MINUTES + " Minuten liegen: '" + text + "'");
            }
            return new Relative(event, offset);
        }
        try {
            return new Fixed(LocalTime.parse(text));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("'" + text + "' ist weder eine Uhrzeit HH:mm noch "
                    + "dawn/sunrise/sunset/dusk mit optionalem Versatz (z. B. sunset-30)");
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen — grün**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=SunTimeExpressionTest
```
Erwartet: `Tests run: 6, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/sun/SunTimeExpression.java backend/src/test/java/com/household/manager/sun/SunTimeExpressionTest.java
git commit -m "feat(sun): Grammatik fuer feste Uhrzeiten und Sonnenausdruecke mit Versatz" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: `time-condition` versteht Sonnenausdrücke

**Files:**
- Modify: `backend/src/main/java/com/household/manager/flowengine/nodes/TimeConditionNodeHandler.java`
- Modify: `backend/src/test/java/com/household/manager/flowengine/nodes/TimeConditionNodeHandlerTest.java`

- [ ] **Step 1: Bestehenden Test auf den neuen Konstruktor umstellen und neue Fälle ergänzen**

`TimeConditionNodeHandlerTest.java` **vollständig** ersetzen durch:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunDayTimes;
import com.household.manager.sun.SunTimesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeConditionNodeHandlerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 11);
    private static final FlowMessage MSG = FlowMessage.of(Map.of("entityId", "event.x"));

    @Mock
    private SunTimesService sunTimes;

    private final List<String> debugLabels = new ArrayList<>();
    private final NodeContext ctx = new NodeContext() {
        public long flowId() { return 1L; }
        public String nodeId() { return "t"; }
        public ConcurrentMap<String, Object> state() { return new ConcurrentHashMap<>(); }
        public void emit(int port, FlowMessage message) { }
        public TaskScheduler scheduler() { return null; }
        public void debug(String label, FlowMessage message) { debugLabels.add(label); }
    };

    private static ZonedDateTime at(LocalDate day, String time) {
        return day.atTime(LocalTime.parse(time)).atZone(BERLIN);
    }

    // dawn 06:20, sunrise 06:52, sunset 19:35, dusk 20:08
    private static final SunDayTimes TIMES = new SunDayTimes(DAY, at(DAY, "06:20"), at(DAY, "06:52"), at(DAY, "19:35"), at(DAY, "20:08"));

    private TimeConditionNodeHandler at(String localTime) {
        var instant = at(DAY, localTime).toInstant();
        return new TimeConditionNodeHandler(Clock.fixed(instant, BERLIN), sunTimes);
    }

    private static NodeConfig window(String from, String to) {
        return new NodeConfig(Map.of("from", from, "to", to));
    }

    private static int portOf(NodeResult result) {
        assertEquals(1, result.outputs().size());
        return result.outputs().keySet().iterator().next();
    }

    // --- bestehendes Verhalten mit festen Uhrzeiten (unveraendert) ---

    @Test
    void insideWindowEmitsOnTruePort() {
        NodeResult result = at("12:00").handle(MSG, window("05:00", "20:00"), ctx);
        assertEquals(0, portOf(result));
        assertEquals(List.of(MSG), result.outputs().get(0));
    }

    @Test
    void outsideWindowEmitsOnFalsePort() {
        assertEquals(1, portOf(at("21:00").handle(MSG, window("05:00", "20:00"), ctx)));
        assertEquals(1, portOf(at("04:30").handle(MSG, window("05:00", "20:00"), ctx)));
    }

    @Test
    void startInclusiveEndExclusive() {
        assertEquals(0, portOf(at("05:00").handle(MSG, window("05:00", "20:00"), ctx)));
        assertEquals(1, portOf(at("20:00").handle(MSG, window("05:00", "20:00"), ctx)));
    }

    @Test
    void windowOverMidnightIsTrueOnBothSides() {
        assertEquals(0, portOf(at("23:15").handle(MSG, window("22:00", "06:00"), ctx)));
        assertEquals(0, portOf(at("03:00").handle(MSG, window("22:00", "06:00"), ctx)));
        assertEquals(1, portOf(at("12:00").handle(MSG, window("22:00", "06:00"), ctx)));
    }

    @Test
    void evaluatesInClockZoneNotUtc() {
        // 19:30 Berlin = 17:30 UTC im September; in UTC laege das noch im Fenster 05:00-18:00.
        assertEquals(1, portOf(at("19:30").handle(MSG, window("05:00", "18:00"), ctx)));
    }

    @Test
    void validateAcceptsWellFormedWindow() {
        assertTrue(at("12:00").validate(window("05:00", "20:00")).isEmpty());
        assertTrue(at("12:00").validate(window("22:00", "06:00")).isEmpty());
    }

    @Test
    void validateRejectsMissingBounds() {
        List<String> errors = at("12:00").validate(NodeConfig.empty());
        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(e -> e.contains("from")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("to")));
    }

    @Test
    void validateRejectsUnparsableTime() {
        assertFalse(at("12:00").validate(window("5 Uhr", "20:00")).isEmpty());
        assertFalse(at("12:00").validate(window("05:00", "25:00")).isEmpty());
    }

    @Test
    void validateRejectsEmptyWindow() {
        List<String> errors = at("12:00").validate(window("08:00", "08:00"));
        assertEquals(1, errors.size());
    }

    @Test
    void catalogDescribesTwoStringFieldsAndTruthyFalsyPorts() {
        var h = at("12:00");
        assertEquals("time-condition", h.type());
        assertEquals(2, h.outputPorts());
        assertEquals(List.of("wahr", "falsch"), h.portLabels());
        assertEquals(2, h.fields().size());
        assertTrue(h.fields().stream().allMatch(f -> f.type() == NodeFieldType.STRING && f.required()));
    }

    // --- Sonnenausdruecke ---

    @Test
    void sunExpressionsResolveAgainstTodaysTimes() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(TIMES));
        // Fenster "sunset-30" (19:05) bis "23:00"
        assertEquals(0, portOf(at("19:05").handle(MSG, window("sunset-30", "23:00"), ctx)));
        assertEquals(0, portOf(at("22:00").handle(MSG, window("sunset-30", "23:00"), ctx)));
        assertEquals(1, portOf(at("19:04").handle(MSG, window("sunset-30", "23:00"), ctx)));
        assertEquals(1, portOf(at("23:00").handle(MSG, window("sunset-30", "23:00"), ctx)));
    }

    @Test
    void darkWindowFromDuskToDawnSpansMidnight() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.of(TIMES));
        assertEquals(0, portOf(at("20:08").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(0, portOf(at("02:00").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(1, portOf(at("06:20").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(1, portOf(at("12:00").handle(MSG, window("dusk", "dawn"), ctx)));
    }

    @Test
    void unresolvableSunExpressionIsFalseAndLeavesDebugTrace() {
        when(sunTimes.timesFor(DAY)).thenReturn(Optional.empty());
        assertEquals(1, portOf(at("02:00").handle(MSG, window("dusk", "dawn"), ctx)));
        assertEquals(1, debugLabels.size());
        assertTrue(debugLabels.get(0).contains("Sonnenzeit"));
    }

    @Test
    void validateAcceptsSunExpressionsAndMixedWindows() {
        assertTrue(at("12:00").validate(window("dusk", "dawn")).isEmpty());
        assertTrue(at("12:00").validate(window("sunset-30", "23:00")).isEmpty());
        assertTrue(at("12:00").validate(window("07:00", "sunrise+60")).isEmpty());
    }

    @Test
    void validateRejectsBadSunExpressionsAndIdenticalExpressions() {
        assertFalse(at("12:00").validate(window("sunset -30", "23:00")).isEmpty());
        assertFalse(at("12:00").validate(window("sunset-300", "23:00")).isEmpty());
        assertFalse(at("12:00").validate(window("noon", "23:00")).isEmpty());
        assertEquals(1, at("12:00").validate(window("sunset", "sunset")).size());
        assertEquals(1, at("12:00").validate(window("sunset-30", "sunset-30")).size());
    }
}
```

- [ ] **Step 2: Test laufen lassen — Kompilierfehler**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest=TimeConditionNodeHandlerTest
```
Erwartet: `COMPILATION ERROR` (Konstruktor mit zwei Argumenten existiert nicht).

- [ ] **Step 3: Handler umbauen**

`TimeConditionNodeHandler.java` **vollständig** ersetzen durch:

```java
package com.household.manager.flowengine.nodes;

import com.household.manager.common.TimeWindow;
import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.NodeHandler;
import com.household.manager.flowengine.NodeResult;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunTimeExpression;
import com.household.manager.sun.SunTimesService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bedingungs-Node: liegt die aktuelle Uhrzeit im Fenster {@code [from, to)}?
 * Port 0 = wahr, Port 1 = falsch. Die Fensterregel (Mitternacht ueberspannend,
 * leeres Fenster bei {@code from == to}) ist {@link TimeWindow} — dieselbe wie
 * beim Modus-Schnellzugriff.
 *
 * <p>{@code from}/{@code to} sind {@link SunTimeExpression}s: feste Uhrzeit
 * ({@code HH:mm}) oder Sonnenereignis mit Versatz ({@code sunset-30}, {@code dawn}).
 * Beide werden fuer den <b>heutigen</b> Tag aufgeloest. Ist ein Sonnenausdruck nicht
 * aufloesbar (kein Zuhause konfiguriert), gilt die Bedingung als <b>falsch</b> —
 * „nicht pruefbar" darf nicht als „erfuellt" gelten, sonst schaltete „Licht wenn
 * dunkel" mittags.
 *
 * <p>Gerechnet wird mit dem {@link Clock}-Bean (Haushaltszeit), nicht mit der
 * Systemzone: die Cron-Trigger haengen noch an {@code systemDefault}, dieser Node
 * soll die UTC-Falle nicht wiederholen.
 *
 * <p>Zur Laufzeit wirft der Node nie — Grammatik und Nicht-Leere des Fensters prueft
 * {@link #validate(NodeConfig)} beim Deploy.
 */
@Component
public class TimeConditionNodeHandler implements NodeHandler {

    static final String FROM = "from";
    static final String TO = "to";

    private final Clock clock;
    private final SunTimesService sunTimes;

    public TimeConditionNodeHandler(Clock clock, SunTimesService sunTimes) {
        this.clock = clock;
        this.sunTimes = sunTimes;
    }

    @Override
    public String type() {
        return "time-condition";
    }

    @Override
    public int outputPorts() {
        return 2;
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        Optional<SunTimeExpression> from = parseExpression(config, FROM, errors);
        Optional<SunTimeExpression> to = parseExpression(config, TO, errors);
        if (from.isPresent() && to.isPresent() && from.get().equals(to.get())) {
            errors.add("from und to duerfen nicht gleich sein (leeres Fenster)");
        }
        return errors;
    }

    @Override
    public NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        LocalDate today = LocalDate.now(clock);
        Optional<LocalTime> from = requireExpression(config, FROM).resolve(today, sunTimes);
        Optional<LocalTime> to = requireExpression(config, TO).resolve(today, sunTimes);
        if (from.isEmpty() || to.isEmpty()) {
            ctx.debug("time-condition: Sonnenzeit nicht bestimmbar (kein Zuhause konfiguriert?) – gilt als falsch", message);
            return NodeResult.port(1, message);
        }
        TimeWindow window = new TimeWindow(from.get(), to.get());
        boolean inside = window.contains(LocalTime.now(clock));
        return NodeResult.port(inside ? 0 : 1, message);
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.field(FROM, "Von (HH:mm oder dawn/sunrise/sunset/dusk±Min, inklusive)", NodeFieldType.STRING, true),
                NodeFieldDescriptor.field(TO, "Bis (HH:mm oder dawn/sunrise/sunset/dusk±Min, exklusive)", NodeFieldType.STRING, true));
    }

    @Override
    public List<String> portLabels() {
        return List.of("wahr", "falsch");
    }

    private static Optional<SunTimeExpression> parseExpression(NodeConfig config, String key, List<String> errors) {
        Optional<String> raw = config.string(key).map(String::trim).filter(s -> !s.isEmpty());
        if (raw.isEmpty()) {
            errors.add(key + " fehlt");
            return Optional.empty();
        }
        try {
            return Optional.of(SunTimeExpression.parse(raw.get()));
        } catch (IllegalArgumentException ex) {
            errors.add(key + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private static SunTimeExpression requireExpression(NodeConfig config, String key) {
        return SunTimeExpression.parse(config.string(key).orElseThrow());
    }
}
```

- [ ] **Step 4: Tests laufen lassen — grün, inklusive aller Flow-Engine-Tests**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test -Dtest='TimeConditionNodeHandlerTest,NodeCatalogFieldsTest,FlowValidatorTest'
```
Erwartet: alle grün.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/household/manager/flowengine/nodes/TimeConditionNodeHandler.java backend/src/test/java/com/household/manager/flowengine/nodes/TimeConditionNodeHandlerTest.java
git commit -m "feat(flows): time-condition versteht Sonnenausdruecke (sunset-30, dusk, dawn+15); nicht aufloesbar gilt als falsch" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Gesamter Backend-Testlauf

**Files:** keine Änderungen erwartet.

- [ ] **Step 1: Alle Backend-Tests laufen lassen**

```bash
cd backend && export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && mvn -q test 2>&1 | tail -40
```
Erwartet: nur `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` rot (fehlende Test-DB, vorbestehend). **Jeder andere rote Test ist eine Regression dieser Arbeit** — insbesondere Tests, die `TimeConditionNodeHandler` per Konstruktor bauen (nur der eigene Test tut das) oder die Node-Typen zählen.

- [ ] **Step 2: Falls etwas anderes rot ist: beheben, erneut laufen lassen, dann committen**

```bash
git add -A backend && git commit -m "fix(sun): Testregressionen nach Sonnenstand-Umbau behoben" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```
(Nur, wenn Step 1 Änderungen nötig machte.)

---

### Task 8: Frontend — Katalog-Label und Hinweis auf die Kopplung

**Files:**
- Modify: `frontend/src/app/pages/flows/node-catalog.ts` (LABELS-Map)
- Modify: `frontend/src/app/pages/admin-tractive/admin-tractive.component.html` (hinter dem `warning`-Absatz, vor `<div id="home-map">`)

- [ ] **Step 1: Label ergänzen**

In `node-catalog.ts` in der `LABELS`-Map hinter `'schedule-trigger': 'Zeitplan',` einfügen:

```ts
  'sun-trigger': 'Sonnenstand',
```

- [ ] **Step 2: Hinweis auf der Admin-Seite**

In `admin-tractive.component.html` direkt vor `<div id="home-map" class="map"></div>` einfügen:

```html
    <p class="hint">
      Diese Koordinaten sind auch der Standort für die Sonnenzeiten der Flows
      (Entität <code>sensor.sun</code>, Node <code>sun-trigger</code>, Sonnenausdrücke im
      Zeitfenster-Node). Ohne Zuhause bleiben sie <code>unavailable</code>.
    </p>
```

- [ ] **Step 3: Typprüfung**

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```
Erwartet: keine Ausgabe (Exit 0).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/pages/flows/node-catalog.ts frontend/src/app/pages/admin-tractive/admin-tractive.component.html
git commit -m "feat(frontend): Katalog-Label fuer sun-trigger und Hinweis auf die Sonnenzeiten-Kopplung im Tractive-Zuhause" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Doku — Flow-Import-Format und CLAUDE.md

**Files:**
- Modify: `docs/flows/flow-import-format.md` (hinter `### schedule-trigger`, und den `### time-condition`-Abschnitt ersetzen)
- Modify: `CLAUDE.md` (hinter dem Abschnitt „Flow-Engine: Zeitfenster-Node `time-condition`")

- [ ] **Step 1: `sun-trigger` in `flow-import-format.md` dokumentieren**

Direkt hinter dem `### schedule-trigger`-Abschnitt (vor `### entity-condition`) einfügen:

```markdown
### `sun-trigger` — Sonnenstand (Trigger, 1 Ausgang)
Feuert zu einem Sonnenereignis am Haushaltsstandort (= Koordinaten des Hundetracker-Zuhauses,
Admin → Hundetracker-Zuhause), optional mit Versatz. Plant sich nach jedem Feuern selbst neu.

| config | Pflicht | Wert |
|--------|---------|------|
| `event` | ja | `dawn` (Beginn bürgerl. Morgendämmerung) / `sunrise` / `sunset` / `dusk` (Ende bürgerl. Abenddämmerung) |
| `offsetMinutes` | nein | ganze Zahl −240 … 240, Default 0; `-30` = 30 min **vor** dem Ereignis |

Die Message trägt `sunEvent`, `offsetMinutes`, `scheduledFor`, `timestamp`, `triggerNodeId`.
Ohne konfiguriertes Zuhause bricht der Deploy nicht ab — der Trigger versucht es stündlich
erneut (Warnung im Log). Verpasste Ereignisse während eines Backend-Ausfalls werden **nicht**
nachgefeuert (wie `schedule-trigger`); wer den Zustand braucht, nimmt `sensor.sun`.

> **Entität `sensor.sun`:** State `day` / `dusk` / `night` / `dawn`, Attribute `dawn`, `sunrise`,
> `sunset`, `dusk` (heute, `HH:mm`), `nextSunrise`, `nextSunset`, `elevation`. Ein
> `entity-state-trigger` mit `operator: "=="`, `value: "dusk"` ist „bei Sonnenuntergang";
> `entity-condition` mit `!= day` ist „Sonne ist unten". Versatz **nach** dem Ereignis über
> `delay`; Versatz **davor** nur mit `sun-trigger`. Ohne Zuhause ist die Entität `unavailable`
> (kein Trigger auf `value: "unavailable"` — tote-Trigger-Falle).
```

- [ ] **Step 2: `time-condition`-Abschnitt ersetzen**

Den bestehenden Abschnitt `### time-condition — Zeitfenster …` bis vor `### delay` ersetzen durch:

```markdown
### `time-condition` — Zeitfenster (2 Ausgänge: 0 = wahr, 1 = falsch)
Prüft, ob die AKTUELLE Uhrzeit (Haushaltszeit Europe/Berlin) in einem Tagesfenster liegt.

| config | Pflicht | Wert |
|--------|---------|------|
| `from` | ja | Beginn — gehört zum Fenster |
| `to` | ja | Ende — gehört **nicht** zum Fenster |

Beide Werte sind Zeitausdrücke: `HH:mm` **oder** `dawn` / `sunrise` / `sunset` / `dusk` mit
optionalem Versatz in Minuten (`sunset-30`, `dawn+15`, |Versatz| ≤ 240, kein Leerzeichen
im Ausdruck). Sonnenausdrücke werden für den heutigen Tag am Haushaltsstandort aufgelöst.

Halboffenes Intervall `[from, to)`. Liegt `to` vor `from`, überspannt das Fenster
Mitternacht (`"from": "dusk", "to": "dawn"` = dunkel). Identische Ausdrücke werden beim Deploy
abgelehnt — „nie" und „immer" wären sonst nicht unterscheidbar. Für „tagsüber A, sonst B"
genügt **ein** Node: Port 0 für das Fenster, Port 1 für den Rest des Tages.

> **Ohne konfiguriertes Zuhause** ist ein Sonnenausdruck nicht auflösbar; die Bedingung gilt
> dann als **falsch** (Port 1) und die Node schreibt einen Debug-Eintrag. „Nicht prüfbar" darf
> nicht als „erfüllt" gelten.
```

- [ ] **Step 3: CLAUDE.md-Abschnitt ergänzen**

Hinter dem Abschnitt `### Flow-Engine: Zeitfenster-Node \`time-condition\`` (vor `### Wandtablet (Präsenzerkennung)`) einfügen:

```markdown
### Sonnenstand für Flows (`sensor.sun`, `sun-trigger`, Sonnenausdrücke)
- Spec: `docs/superpowers/specs/2026-09-21-sonnenstand-flows-design.md`. Paket `backend/.../sun/`. **`SunTimesService` ist die einzige Definition der Sonnenzeiten** (Muster `TractiveHomeResolver`): Entität, Trigger-Node und `time-condition` fragen dieselbe Klasse; nur dieses Paket importiert `commons-suncalc`. `dawn`/`dusk` = bürgerliche Dämmerung (6° unter dem Horizont)
- **Die Koordinaten sind das Hundetracker-Zuhause** (`TractiveHomeSettingsService`, bei jedem Aufruf frisch gelesen) — bewusste Kopplung (Nutzerentscheidung 2026-09-21, Hinweis auf der Admin-Seite `admin/tractive`). Wer das Hunde-Zuhause verschiebt, verschiebt die Sonnenzeiten; für Auf-/Untergang sind 50 km aber nur 1–2 Minuten. Ohne Zuhause: `sensor.sun` `unavailable` (mit erhaltenen Attributen), `sun-trigger` versucht es stündlich erneut, Sonnenausdrücke in `time-condition` werten als **falsch**. Lesen wirft nie
- **Entität `sensor.sun`** (`SunEntityPublisher`, minütlich, `EntitySource.SUN`): State `day`/`dusk`/`night`/`dawn` (halboffen wie `TimeWindow`), Attribute `dawn`/`sunrise`/`sunset`/`dusk` (heute), `nextSunrise`/`nextSunset` (nächstes nach jetzt), `elevation`. Feuert viermal am Tag; Versatz **nach** dem Ereignis über `delay`, **davor** nur mit `sun-trigger`. Ein Neustart über eine Flanke feuert den Übergang verspätet nach — Arbeitsteilung: **Entität für Zustand, Trigger für Zeitpunkt**. Kein `deviceClass` (siehe Blink)
- **`sun-trigger`** (`SunTriggerHandler`): `event` + `offsetMinutes` (±240), plant per `Instant` (zeitumstellungssicher) und nach jedem Feuern selbst neu; sucht heute/morgen/übermorgen, damit ein negativer Versatz das heutige Ereignis nicht verliert; sucht nach dem Feuern **nie vor `scheduledFor`**, sonst würde ein Millisekunden zu früher Scheduler dasselbe Ereignis doppelt planen. Cleanup setzt ein `cancelled`-Flag **und** storniert das Future; `scheduleNext` prüft das Flag nach dem Einplanen erneut — sonst hinterließe ein Re-Deploy zur Flanke einen Geister-Timer. Verpasste Ereignisse bei Backend-Ausfall werden nicht nachgefeuert (wie Cron)
- **Sonnenausdrücke** (`SunTimeExpression`, Grammatik `HH:mm | dawn|sunrise|sunset|dusk[±N]`, kein Leerzeichen innen): `time-condition` löst `from`/`to` für **heute** auf und baut daraus das unveränderte `TimeWindow`. `ModeQuickAccessResolver`/`mode_quick_access` bleiben bei festen `HH:mm`
- `offsetMinutes` wird über `String` + `Integer.parseInt` geprüft, nicht über `NodeConfig.integer()` — Letzteres kürzt `1.5` klaglos auf `1`
- **Rollout-Falle** wie bei `time-condition`: Flows mit `sun-trigger` oder Sonnenausdrücken lassen sich erst nach dem PROD-Deploy deployen; vorher Koordinaten unter Admin → Hundetracker-Zuhause prüfen
```

- [ ] **Step 4: Commit**

```bash
git add docs/flows/flow-import-format.md CLAUDE.md
git commit -m "docs(flows): sun-trigger, sensor.sun und Sonnenausdruecke im Flow-Import-Format und in CLAUDE.md" -m "Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: Abschluss

- [ ] **Step 1: Plan-Checkboxen abhaken und `git status` prüfen** — Arbeitsverzeichnis sauber, Branch `feature/sun-flows`.
- [ ] **Step 2: Übergabe an `superpowers:finishing-a-development-branch`** (Merge nach `main` mit Merge-Commit, wie im Projekt üblich).
- [ ] **Step 3: Nach dem PROD-Deploy** (nicht Teil dieses Plans): Koordinaten prüfen, dann die gewünschten Flows via flow-mcp anlegen (`flow_node_types` zeigt `sun-trigger`; `flow_list_entities` zeigt `sensor.sun`).
