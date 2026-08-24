# Tablet-Ansicht „Verbrauch" — Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine vierte Wandtablet-Unteransicht unter `/tablet/consumption`, die Strom-, Gas- und Wasserverbrauch je Woche oder Monat als Balkendiagramme nebeneinander zeigt.

**Architecture:** Ein neuer, lesender Backend-Endpunkt `GET /v1/meter-readings/series` aggregiert die wöchentlichen Zählerstände serverseitig zu Verbrauchspunkten (Differenz aufeinanderfolgender Ablesungen, gruppiert nach Woche oder Monat). Das Frontend bekommt einen dünnen HTTP-Service und eine Seite in der bestehenden `app-tablet-shell`. Keine Schemaänderung, keine Liquibase-Migration, keine Änderung an `SecurityConfig`.

**Tech Stack:** Spring Boot 3.4.1 / Java 21 / Lombok / JUnit 5 + Mockito im Backend; Angular 19 standalone / SCSS / ngx-echarts (BarChart) / Karma + Jasmine im Frontend.

**Spec:** `docs/superpowers/specs/2026-08-24-tablet-verbrauch-design.md`

---

## Umgebung (gilt für alle Tasks)

**Backend-Kommandos** immer aus `backend/`, und **vorher** die JDK-Variable setzen — der Standard dieser Maschine ist JDK 17 und `mvn` bricht sonst mit „JAVA_HOME ... not defined correctly" ab:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
```

Die Tests `HouseholdManagerApplicationTests.contextLoads` und `HealthControllerTest` schlagen auf dieser Maschine mit „Access denied for user 'root'@'localhost'" fehl — die Test-DB ist lokal nicht erreichbar. **Das ist die Baseline, keine Regression.** Deshalb laufen im Plan gezielt einzelne Testklassen statt `mvn test`.

**Frontend-Kommandos** aus `frontend/`. Voller Lauf:

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

Baseline: **3 vorbestehende Fails** (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`) plus gelegentlich ein Flake in `SmartDeviceListComponent` (`afterAll`: „Cannot read properties of undefined (reading 'subscribe')"). Nur *zusätzliche* Fails sind echte Regressionen. Einzelne Suite gezielt laufen lassen geht über `--include`:

```bash
npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-consumption.component.spec.ts'
```

**Branch:** `feature/tablet-verbrauch` (existiert bereits, Spec ist dort committet).

---

## Dateistruktur

### Backend (neu)

| Datei | Verantwortung |
|---|---|
| `backend/src/main/java/com/household/manager/service/ConsumptionRange.java` | Enum der wählbaren Zeiträume (8/26/52 Wochen, 6/12/24 Monate) inkl. ihrer Auflösung und Periodenzahl |
| `backend/src/main/java/com/household/manager/dto/ConsumptionPoint.java` | Ein Balken: Periodenbeginn, Beschriftung, Verbrauch, Schätzwert-Flag |
| `backend/src/main/java/com/household/manager/dto/MeterConsumptionSeries.java` | Eine Kachel: Zählertyp, Einheit, Punkte |
| `backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java` | Differenzbildung + Gruppierung je Typ, Fehlerisolierung je Typ |
| `backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java` | Unit-Tests des Services (Mockito, kein Spring-Kontext) |

### Backend (geändert)

| Datei | Änderung |
|---|---|
| `backend/src/main/java/com/household/manager/repository/MeterReadingRepository.java` | Neue abgeleitete Methode `findByMeterTypeOrderByReadingDateAsc` |
| `backend/src/main/java/com/household/manager/controller/MeterReadingController.java` | Neuer `GET /series`-Endpunkt |
| `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java` | Zwei Tests für die beiden Richtungen |

### Frontend (neu)

| Datei | Verantwortung |
|---|---|
| `frontend/src/app/models/meter-consumption-series.model.ts` | Typen der Serie plus `ConsumptionResolution`/`ConsumptionRange` |
| `frontend/src/app/services/meter-consumption-series.service.ts` | Dünner HTTP-Wrapper um den neuen Endpunkt |
| `frontend/src/app/shared/consumption-view.util.ts` | Reine Funktionen: Zeitraumoptionen je Auflösung, Vorperiodenvergleich, Wertformatierung |
| `frontend/src/app/shared/consumption-view.util.spec.ts` | Tests der reinen Funktionen |
| `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.ts` | Seitenkomponente |
| `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.html` | Markup |
| `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.scss` | Styles inkl. Höhenkette |
| `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.spec.ts` | Komponententests inkl. Höhenketten-Test |

### Frontend (geändert)

| Datei | Änderung |
|---|---|
| `frontend/src/app/shared/tablet-views.ts` | Vierter Eintrag „Verbrauch" |
| `frontend/src/app/app.routes.ts` | Route `tablet/consumption` |

---

## Task 1: `ConsumptionRange` — Zeiträume und ihre Auflösung

**Files:**
- Create: `backend/src/main/java/com/household/manager/service/ConsumptionRange.java`
- Test: `backend/src/test/java/com/household/manager/service/ConsumptionRangeTest.java`

Das Enum trägt beides: die Auflösung (Woche oder Monat) und die Anzahl der Perioden. `SeriesRange` bleibt unangetastet — es beschreibt Tagesfenster und kann Wochen-/Monatszahlen nicht ausdrücken.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

Datei `backend/src/test/java/com/household/manager/service/ConsumptionRangeTest.java`:

```java
package com.household.manager.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumptionRangeTest {

    @Test
    void wochenzeitraeumeTragenDieAufloesungWoche() {
        assertThat(ConsumptionRange.WEEKS_26.getResolution()).isEqualTo(ConsumptionResolution.WEEK);
        assertThat(ConsumptionRange.WEEKS_26.getPeriods()).isEqualTo(26);
    }

    @Test
    void monatszeitraeumeTragenDieAufloesungMonat() {
        assertThat(ConsumptionRange.MONTHS_12.getResolution()).isEqualTo(ConsumptionResolution.MONTH);
        assertThat(ConsumptionRange.MONTHS_12.getPeriods()).isEqualTo(12);
    }

    /**
     * Der Startpunkt bestimmt, wie weit zurueck Ablesungen geladen werden. Bei Wochen
     * exakt N Wochen, bei Monaten der Erste des Monats vor N-1 Monaten - sonst fehlte
     * dem aeltesten Monatsbalken sein Anfang und er stuende zu niedrig da.
     */
    @Test
    void berechnetDenFensterbeginnJeAufloesung() {
        LocalDate heute = LocalDate.of(2026, 8, 24);

        assertThat(ConsumptionRange.WEEKS_8.windowStart(heute)).isEqualTo(LocalDate.of(2026, 6, 29));
        assertThat(ConsumptionRange.MONTHS_6.windowStart(heute)).isEqualTo(LocalDate.of(2026, 3, 1));
    }
}
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ConsumptionRangeTest
```

Erwartet: Kompilierfehler — `ConsumptionRange` und `ConsumptionResolution` existieren nicht.

- [ ] **Step 3: Enums anlegen**

Datei `backend/src/main/java/com/household/manager/service/ConsumptionResolution.java`:

```java
package com.household.manager.service;

/** Laenge einer Verbrauchsperiode: eine Ablesewoche oder ein Kalendermonat. */
public enum ConsumptionResolution {
    WEEK,
    MONTH
}
```

Datei `backend/src/main/java/com/household/manager/service/ConsumptionRange.java`:

```java
package com.household.manager.service;

import lombok.Getter;

import java.time.LocalDate;

/**
 * Waehlbarer Zeitraum der Verbrauchsansicht: Auflösung plus Anzahl der Perioden.
 *
 * <p>Bewusst nicht {@link SeriesRange}: das beschreibt bei Temperatur und Luftqualitaet
 * Fenster von Tagen und kann Wochen- oder Monatszahlen nicht ausdruecken. Es bedient
 * bereits zwei Serien-Services; eine dritte, andersartige Bedeutung hineinzuzwingen
 * waere der teurere Weg.
 */
@Getter
public enum ConsumptionRange {
    WEEKS_8(ConsumptionResolution.WEEK, 8),
    WEEKS_26(ConsumptionResolution.WEEK, 26),
    WEEKS_52(ConsumptionResolution.WEEK, 52),
    MONTHS_6(ConsumptionResolution.MONTH, 6),
    MONTHS_12(ConsumptionResolution.MONTH, 12),
    MONTHS_24(ConsumptionResolution.MONTH, 24);

    private final ConsumptionResolution resolution;
    private final int periods;

    ConsumptionRange(ConsumptionResolution resolution, int periods) {
        this.resolution = resolution;
        this.periods = periods;
    }

    /**
     * Beginn des Ladefensters. Bei Monaten der Erste des aeltesten gezeigten Monats -
     * ein mitten im Monat beginnendes Fenster liesse den aeltesten Balken zu niedrig
     * erscheinen, weil ihm die ersten Wochen fehlten.
     */
    public LocalDate windowStart(LocalDate today) {
        return resolution == ConsumptionResolution.WEEK
                ? today.minusWeeks(periods)
                : today.minusMonths(periods - 1L).withDayOfMonth(1);
    }
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=ConsumptionRangeTest
```

Erwartet: BUILD SUCCESS, 3 Tests grün.

- [ ] **Step 5: Committen**

```bash
git add backend/src/main/java/com/household/manager/service/ConsumptionRange.java backend/src/main/java/com/household/manager/service/ConsumptionResolution.java backend/src/test/java/com/household/manager/service/ConsumptionRangeTest.java
git commit -m "feat(consumption): Zeitraum-Enum fuer die Verbrauchsansicht"
```

---

## Task 2: DTOs der Serie

**Files:**
- Create: `backend/src/main/java/com/household/manager/dto/ConsumptionPoint.java`
- Create: `backend/src/main/java/com/household/manager/dto/MeterConsumptionSeries.java`

Reine Datenträger ohne Logik — deshalb kein eigener Test; sie werden in Task 3 durch die Service-Tests mitbelegt.

- [ ] **Step 1: `ConsumptionPoint` anlegen**

Datei `backend/src/main/java/com/household/manager/dto/ConsumptionPoint.java`:

```java
package com.household.manager.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ein Balken der Verbrauchsansicht.
 *
 * @param periodStart Beginn der Periode (Ablesedatum bei Wochen, Monatserster bei Monaten)
 * @param label       Beschriftung der X-Achse, z. B. "KW 33" oder "Aug 26"
 * @param consumption Verbrauch in der Einheit der Serie
 * @param estimated   true, sobald mindestens eine beitragende Ablesung ein Schaetzwert war
 */
public record ConsumptionPoint(
        LocalDate periodStart,
        String label,
        BigDecimal consumption,
        boolean estimated
) {
}
```

- [ ] **Step 2: `MeterConsumptionSeries` anlegen**

Datei `backend/src/main/java/com/household/manager/dto/MeterConsumptionSeries.java`:

```java
package com.household.manager.dto;

import com.household.manager.model.entity.MeterType;

import java.util.List;

/**
 * Die Verbrauchsreihe genau eines Zaehlertyps - eine Kachel der Tablet-Ansicht.
 *
 * @param meterType Zaehlertyp
 * @param unit      Einheit der Werte ("kWh" bei Strom, "m³" bei Gas und Wasser)
 * @param points    Balken, aeltester zuerst
 */
public record MeterConsumptionSeries(
        MeterType meterType,
        String unit,
        List<ConsumptionPoint> points
) {
}
```

- [ ] **Step 3: Kompilieren**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q compile
```

Erwartet: BUILD SUCCESS.

- [ ] **Step 4: Committen**

```bash
git add backend/src/main/java/com/household/manager/dto/ConsumptionPoint.java backend/src/main/java/com/household/manager/dto/MeterConsumptionSeries.java
git commit -m "feat(consumption): DTOs der Verbrauchsreihe"
```

---

## Task 3: `MeterConsumptionSeriesService` — Wochen-Aggregation

**Files:**
- Modify: `backend/src/main/java/com/household/manager/repository/MeterReadingRepository.java`
- Create: `backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java`
- Test: `backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java`

Erst nur `resolution=WEEK`. Die Monatsgruppierung kommt in Task 4.

Der Service lädt **alle** Ablesungen eines Typs aufsteigend, bildet die Differenzen paarweise und filtert danach aufs Fenster. Grund für „alle statt Fenster": für den ersten Balken im Fenster braucht es die Ablesung **davor**, sonst fehlt der älteste Balken. Bei wöchentlicher Erfassung sind das wenige hundert Zeilen je Typ.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Datei `backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.MeterReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MeterConsumptionSeriesServiceTest {

    @Mock
    private MeterReadingRepository repository;

    private MeterConsumptionSeriesService service;

    /** Fester "heute"-Bezug, damit die Fenstergrenzen im Test nicht mitwandern. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);

    @BeforeEach
    void setUp() {
        service = new MeterConsumptionSeriesService(repository, () -> TODAY);
        when(repository.findByMeterTypeOrderByReadingDateAsc(any())).thenReturn(List.of());
    }

    private static MeterReading reading(LocalDate date, String value, boolean estimated) {
        return MeterReading.builder()
                .meterType(MeterType.ELECTRICITY)
                .readingValue(new BigDecimal(value))
                .readingDate(date.atStartOfDay())
                .estimated(estimated)
                .build();
    }

    private void stromAblesungen(MeterReading... readings) {
        when(repository.findByMeterTypeOrderByReadingDateAsc(MeterType.ELECTRICITY))
                .thenReturn(List.of(readings));
    }

    private MeterConsumptionSeries strom(ConsumptionRange range) {
        return service.getSeries(range).stream()
                .filter(s -> s.meterType() == MeterType.ELECTRICITY)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Keine Stromserie in der Antwort"));
    }

    @Test
    void bildetProAblesungEinenBalkenAusDerDifferenzZurVorablesung() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", false),
                reading(LocalDate.of(2026, 8, 21), "1075", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.WEEKS_8);

        assertThat(series.unit()).isEqualTo("kWh");
        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("38");
        assertThat(series.points().get(1).consumption()).isEqualByComparingTo("37");
    }

    /**
     * Die allererste Ablesung hat keinen Vorgaenger, aus dem sich ein Verbrauch
     * bilden liesse - sie darf keinen Balken erzeugen.
     */
    @Test
    void laesstDieAllererstAblesungWeg() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1037", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 8, 21));
    }

    @Test
    void beschriftetWochenbalkenMitDerKalenderwoche() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points().get(0).label()).isEqualTo("KW 33");
    }

    @Test
    void kennzeichnetGeschaetzteAblesungen() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "1038", true));

        assertThat(strom(ConsumptionRange.WEEKS_8).points().get(0).estimated()).isTrue();
    }

    /**
     * Zaehlertausch oder -reset: ein Minusbalken waere eine Falschaussage. Die API
     * verhindert solche Werte beim Anlegen, der CSV-Import ist der Weg, auf dem sie
     * trotzdem in der Tabelle landen koennen.
     */
    @Test
    void verwirftNegativeDifferenzen() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 7), "1000", false),
                reading(LocalDate.of(2026, 8, 14), "12", false),
                reading(LocalDate.of(2026, 8, 21), "50", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 8, 21));
    }

    /**
     * Das Fenster von WEEKS_8 beginnt am 2026-06-29. Die Januar-Differenz faellt
     * heraus; die Differenz vom 14.08. bleibt drin, auch wenn ihre Vorablesung weit
     * davor liegt - der Balken gehoert zum Ablesedatum.
     */
    @Test
    void laesstAblesungenVorDemFensterWeg() {
        stromAblesungen(
                reading(LocalDate.of(2026, 1, 9), "500", false),
                reading(LocalDate.of(2026, 1, 16), "540", false),
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1038", false));

        assertThat(strom(ConsumptionRange.WEEKS_8).points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 21));
    }

    /**
     * Ein Typ ohne Ablesungen erzeugt keine leere Serie - das Frontend soll keine
     * leeren Diagramme zeichnen muessen.
     */
    @Test
    void laesstTypenOhneAblesungenAus() {
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1038", false));

        assertThat(service.getSeries(ConsumptionRange.WEEKS_8))
                .extracting(MeterConsumptionSeries::meterType)
                .containsExactly(MeterType.ELECTRICITY);
    }

    /** Ein kaputter Typ darf die anderen nicht mitreissen. */
    @Test
    void isoliertFehlerJeZaehlertyp() {
        when(repository.findByMeterTypeOrderByReadingDateAsc(MeterType.GAS))
                .thenThrow(new RuntimeException("DB weg"));
        stromAblesungen(
                reading(LocalDate.of(2026, 8, 14), "1000", false),
                reading(LocalDate.of(2026, 8, 21), "1038", false));

        assertThat(service.getSeries(ConsumptionRange.WEEKS_8))
                .extracting(MeterConsumptionSeries::meterType)
                .containsExactly(MeterType.ELECTRICITY);
    }

    @Test
    void nenntDieEinheitJeZaehlertyp() {
        when(repository.findByMeterTypeOrderByReadingDateAsc(MeterType.WATER)).thenReturn(List.of(
                MeterReading.builder().meterType(MeterType.WATER)
                        .readingValue(new BigDecimal("100"))
                        .readingDate(LocalDate.of(2026, 8, 14).atStartOfDay()).build(),
                MeterReading.builder().meterType(MeterType.WATER)
                        .readingValue(new BigDecimal("102"))
                        .readingDate(LocalDate.of(2026, 8, 21).atStartOfDay()).build()));

        assertThat(service.getSeries(ConsumptionRange.WEEKS_8))
                .filteredOn(s -> s.meterType() == MeterType.WATER)
                .extracting(MeterConsumptionSeries::unit)
                .containsExactly("m³");
    }
}
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=MeterConsumptionSeriesServiceTest
```

Erwartet: Kompilierfehler — `MeterConsumptionSeriesService` und `findByMeterTypeOrderByReadingDateAsc` existieren nicht.

- [ ] **Step 3: Repository-Methode ergänzen**

In `backend/src/main/java/com/household/manager/repository/MeterReadingRepository.java`, direkt nach `findByMeterTypeOrderByReadingDateDesc`, einfügen:

```java
    /**
     * Find all meter readings for a specific meter type, oldest first.
     * <p>
     * Used by the consumption series: consumption is the difference between two
     * consecutive readings, which is only expressible in ascending order.
     *
     * @param meterType the type of meter to filter by
     * @return list of meter readings for the specified type, sorted by date (oldest first)
     */
    List<MeterReading> findByMeterTypeOrderByReadingDateAsc(MeterType meterType);
```

- [ ] **Step 4: Service anlegen**

Datei `backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java`:

```java
package com.household.manager.service;

import com.household.manager.dto.ConsumptionPoint;
import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.MeterReadingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Aggregiert die woechentlich erfassten Zaehlerstaende zu Verbrauchsreihen fuer die
 * Tablet-Ansicht.
 *
 * <p>Der Verbrauch ist die Differenz zweier aufeinanderfolgender Ablesungen. Das
 * {@code consumption}-Feld der bestehenden API taugt dafuer nicht: es ist nur bei der
 * jeweils neuesten Ablesung eines Typs gefuellt, und die Liste ist aufs laufende
 * Kalenderjahr beschraenkt.
 *
 * <p>Jeder Zaehlertyp wird fuer sich ausgewertet - faellt einer aus, kommen die
 * anderen trotzdem (Muster {@code TemperatureSeriesService}).
 */
@Service
@Slf4j
public class MeterConsumptionSeriesService {

    private final MeterReadingRepository repository;
    /** Injizierbar, damit Tests ein festes "heute" setzen koennen. */
    private final Supplier<LocalDate> today;

    public MeterConsumptionSeriesService(MeterReadingRepository repository) {
        this(repository, LocalDate::now);
    }

    MeterConsumptionSeriesService(MeterReadingRepository repository, Supplier<LocalDate> today) {
        this.repository = repository;
        this.today = today;
    }

    @Transactional(readOnly = true)
    public List<MeterConsumptionSeries> getSeries(ConsumptionRange range) {
        LocalDate from = range.windowStart(today.get());
        List<MeterConsumptionSeries> result = new ArrayList<>();
        for (MeterType type : MeterType.values()) {
            seriesFor(type, range, from).ifPresent(result::add);
        }
        return result;
    }

    private Optional<MeterConsumptionSeries> seriesFor(MeterType type, ConsumptionRange range,
                                                       LocalDate from) {
        try {
            List<ConsumptionPoint> points = points(type, range, from);
            return points.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new MeterConsumptionSeries(type, unitOf(type), points));
        } catch (Exception e) {
            log.warn("Verbrauchsreihe fuer {} konnte nicht gebildet werden: {}", type, e.toString());
            return Optional.empty();
        }
    }

    private List<ConsumptionPoint> points(MeterType type, ConsumptionRange range, LocalDate from) {
        List<MeterReading> readings = repository.findByMeterTypeOrderByReadingDateAsc(type);
        List<ConsumptionPoint> weekly = new ArrayList<>();

        for (int i = 1; i < readings.size(); i++) {
            MeterReading current = readings.get(i);
            BigDecimal consumption = current.getReadingValue()
                    .subtract(readings.get(i - 1).getReadingValue());
            LocalDate date = current.getReadingDate().toLocalDate();

            if (consumption.signum() < 0) {
                // Zaehlertausch oder -reset: ein Minusbalken waere eine Falschaussage.
                log.warn("Negative Differenz bei {} am {} verworfen: {}", type, date, consumption);
                continue;
            }
            if (date.isBefore(from)) {
                continue;
            }
            weekly.add(new ConsumptionPoint(date, weekLabel(date), consumption,
                    current.isEstimated()));
        }
        return weekly;
    }

    private static String weekLabel(LocalDate date) {
        return "KW " + date.get(WeekFields.ISO.weekOfWeekBasedYear());
    }

    private static String unitOf(MeterType type) {
        return type == MeterType.ELECTRICITY ? "kWh" : "m³";
    }
}
```

- [ ] **Step 5: Tests laufen lassen, grün bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=MeterConsumptionSeriesServiceTest
```

Erwartet: BUILD SUCCESS, 9 Tests grün.

- [ ] **Step 6: Committen**

```bash
git add backend/src/main/java/com/household/manager/repository/MeterReadingRepository.java backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java
git commit -m "feat(consumption): Wochenreihe aus Zaehlerstaenden bilden"
```

---

## Task 4: Monats-Aggregation

**Files:**
- Modify: `backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java`
- Test: `backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java`

Eine Woche gehört in den Monat ihres **Ablesedatums**. Ein Monatsbalken gilt als geschätzt, sobald **mindestens eine** beitragende Woche geschätzt war.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Ans Ende von `MeterConsumptionSeriesServiceTest` (vor die schließende Klammer) einfügen:

```java
    @Test
    void fasstWochenZuKalendermonatenZusammen() {
        stromAblesungen(
                reading(LocalDate.of(2026, 7, 3), "1000", false),
                reading(LocalDate.of(2026, 7, 10), "1010", false),
                reading(LocalDate.of(2026, 7, 17), "1030", false),
                reading(LocalDate.of(2026, 8, 7), "1075", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        assertThat(series.points()).hasSize(2);
        assertThat(series.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(series.points().get(0).label()).isEqualTo("Jul 26");
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("30");
        assertThat(series.points().get(1).periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(series.points().get(1).consumption()).isEqualByComparingTo("45");
    }

    /**
     * Eine Ablesewoche liegt oft quer ueber den Monatswechsel. Sie zaehlt vollstaendig
     * in den Monat ihres Ablesedatums - der Balken entspricht so weiterhin echten
     * Ablesungen.
     */
    @Test
    void schlaegtEineWocheUeberDemMonatswechselDemAblesemonatZu() {
        stromAblesungen(
                reading(LocalDate.of(2026, 6, 26), "1000", false),
                reading(LocalDate.of(2026, 7, 3), "1020", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        assertThat(series.points()).hasSize(1);
        assertThat(series.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(series.points().get(0).consumption()).isEqualByComparingTo("20");
    }

    /**
     * Sonst verschwaende eine geschaetzte Woche in einem sonst echten Monat spurlos.
     */
    @Test
    void markiertEinenMonatSobaldEineWocheGeschaetztWar() {
        stromAblesungen(
                reading(LocalDate.of(2026, 7, 3), "1000", false),
                reading(LocalDate.of(2026, 7, 10), "1010", true),
                reading(LocalDate.of(2026, 7, 17), "1030", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        assertThat(series.points()).hasSize(1);
        assertThat(series.points().get(0).estimated()).isTrue();
    }

    @Test
    void erzeugtKeineMonatsbalkenOhneAblesung() {
        stromAblesungen(
                reading(LocalDate.of(2026, 5, 1), "900", false),
                reading(LocalDate.of(2026, 5, 8), "920", false),
                reading(LocalDate.of(2026, 8, 7), "1075", false));

        MeterConsumptionSeries series = strom(ConsumptionRange.MONTHS_6);

        // Mai (20) und August (155) - Juni und Juli fehlen ganz, statt als 0 dazustehen.
        assertThat(series.points())
                .extracting(p -> p.periodStart())
                .containsExactly(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 8, 1));
    }
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=MeterConsumptionSeriesServiceTest
```

Erwartet: 4 Fehlschläge — die Monatsreihe liefert noch Wochenbalken (`periodStart` ist das Ablesedatum, nicht der Monatserste).

- [ ] **Step 3: Gruppierung implementieren**

In `MeterConsumptionSeriesService` die Methode `points` ersetzen und die drei neuen Methoden ergänzen. `points` heißt danach:

```java
    private List<ConsumptionPoint> points(MeterType type, ConsumptionRange range, LocalDate from) {
        List<ConsumptionPoint> weekly = weeklyPoints(type, from);
        return range.getResolution() == ConsumptionResolution.WEEK ? weekly : toMonths(weekly);
    }

    /** Ein Punkt je Ablesung: die Differenz zur Vorablesung. */
    private List<ConsumptionPoint> weeklyPoints(MeterType type, LocalDate from) {
        List<MeterReading> readings = repository.findByMeterTypeOrderByReadingDateAsc(type);
        List<ConsumptionPoint> weekly = new ArrayList<>();

        for (int i = 1; i < readings.size(); i++) {
            MeterReading current = readings.get(i);
            BigDecimal consumption = current.getReadingValue()
                    .subtract(readings.get(i - 1).getReadingValue());
            LocalDate date = current.getReadingDate().toLocalDate();

            if (consumption.signum() < 0) {
                // Zaehlertausch oder -reset: ein Minusbalken waere eine Falschaussage.
                log.warn("Negative Differenz bei {} am {} verworfen: {}", type, date, consumption);
                continue;
            }
            if (date.isBefore(from)) {
                continue;
            }
            weekly.add(new ConsumptionPoint(date, weekLabel(date), consumption,
                    current.isEstimated()));
        }
        return weekly;
    }

    /**
     * Fasst Wochenbalken zu Kalendermonaten zusammen. Eine Woche gehoert in den Monat
     * ihres Ablesedatums, auch wenn sie ueber den Monatswechsel reicht.
     *
     * <p>Monate ohne Ablesung entstehen nicht - eine erfundene Null waere von echten
     * null Verbrauch nicht zu unterscheiden.
     */
    private static List<ConsumptionPoint> toMonths(List<ConsumptionPoint> weekly) {
        Map<LocalDate, ConsumptionPoint> byMonth = new LinkedHashMap<>();
        for (ConsumptionPoint week : weekly) {
            LocalDate month = week.periodStart().withDayOfMonth(1);
            byMonth.merge(month,
                    new ConsumptionPoint(month, monthLabel(month), week.consumption(),
                            week.estimated()),
                    (a, b) -> new ConsumptionPoint(a.periodStart(), a.label(),
                            a.consumption().add(b.consumption()),
                            // Ein einziger Schaetzwert genuegt, um den Monat zu markieren.
                            a.estimated() || b.estimated()));
        }
        return new ArrayList<>(byMonth.values());
    }

    private static String monthLabel(LocalDate month) {
        return MONTH_LABEL.format(month);
    }
```

Zusätzlich als Feld der Klasse, direkt unter der Klassendeklaration:

```java
    /** "Jul 26" - kurz genug fuer eine Drittelspalte auf dem Wandtablet. */
    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yy", Locale.GERMAN);
```

Und die Importe ergänzen:

```java
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
```

- [ ] **Step 4: Tests laufen lassen, grün bestätigen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=MeterConsumptionSeriesServiceTest
```

Erwartet: BUILD SUCCESS, 13 Tests grün.

Falls `Jul 26` nicht passt (die JDK-Locale-Daten schreiben je nach Version „Jul." mit Punkt): die Erwartung im Test an die tatsächliche Ausgabe anpassen, **nicht** den Formatter verbiegen — die Beschriftung ist reine Anzeige.

- [ ] **Step 5: Committen**

```bash
git add backend/src/main/java/com/household/manager/service/MeterConsumptionSeriesService.java backend/src/test/java/com/household/manager/service/MeterConsumptionSeriesServiceTest.java
git commit -m "feat(consumption): Wochen zu Kalendermonaten zusammenfassen"
```

---

## Task 5: Endpunkt und Sicherheitsregel

**Files:**
- Modify: `backend/src/main/java/com/household/manager/controller/MeterReadingController.java`
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`

Der Endpunkt braucht **keine** neue Zeile in `SecurityConfig` — er fällt unter die generische Regel `GET /v1/**` → KIOSK. Genau das halten die Tests fest, damit eine spätere Umsortierung der Matcher dem Wandtablet nicht unbemerkt den Zugriff kappt.

`MeterReadingController` steht **nicht** im Slice von `SecurityRulesTest`. Nach der dort dokumentierten Konvention belegt ein **404 statt 403**, dass die Autorisierungsregel durchlässt.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Ans Ende von `SecurityRulesTest` (vor die schließende Klammer) einfügen:

```java
    /**
     * Die Verbrauchsreihe des Wandtablets braucht bewusst keine eigene Regel: das GET
     * faellt auf die generische Regel GET /v1/** -> KIOSK. 404 statt 403 belegt, dass
     * die Regel durchlaesst (der Controller steht nicht in diesem Slice).
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfDieVerbrauchsreiheLesen() throws Exception {
        mockMvc.perform(get("/v1/meter-readings/series?range=WEEKS_26"))
                .andExpect(status().isNotFound());
    }

    /**
     * Schreiben bleibt MEMBER (anyRequest-Regel) - das Wandtablet darf Zaehlerstaende
     * lesen, aber keine erfassen.
     */
    @Test
    @WithMockUser(roles = "KIOSK")
    void kioskDarfKeineAblesungAnlegen() throws Exception {
        mockMvc.perform(post("/v1/meter-readings")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meterType\":\"ELECTRICITY\",\"readingValue\":1,"
                                + "\"readingDate\":\"2026-08-21T00:00:00\"}"))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 2: Tests laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SecurityRulesTest
```

Erwartet: beide neuen Tests **grün** — die Regeln stehen bereits, die Tests halten sie nur fest. Schlägt `kioskDarfDieVerbrauchsreiheLesen` mit 403 fehl, ist die Matcher-Reihenfolge in `SecurityConfig` das Problem und muss geklärt werden, bevor es weitergeht.

- [ ] **Step 3: Endpunkt ergänzen**

In `MeterReadingController` nach `getMeterReadingsByType` einfügen:

```java
    /**
     * Consumption series for the wall tablet view, aggregated per week or month.
     * <p>
     * GET /api/v1/meter-readings/series?range=WEEKS_26
     * <p>
     * The resolution (week or month) is part of the range value, so a single
     * parameter cannot express an impossible combination such as "8 weeks, monthly".
     *
     * @param range the selected time window, defaults to 26 weeks
     * @return one series per meter type that has readings in the window
     */
    @GetMapping("/series")
    public ResponseEntity<List<MeterConsumptionSeries>> getConsumptionSeries(
            @RequestParam(required = false, defaultValue = "WEEKS_26") ConsumptionRange range) {
        log.debug("Received request for consumption series, range: {}", range);
        return ResponseEntity.ok(meterConsumptionSeriesService.getSeries(range));
    }
```

Das Feld ergänzen (bei den anderen `private final`-Feldern):

```java
    private final MeterConsumptionSeriesService meterConsumptionSeriesService;
```

Und die Importe:

```java
import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.service.ConsumptionRange;
import com.household.manager.service.MeterConsumptionSeriesService;
```

**Wichtig:** `@GetMapping("/series")` muss **vor** `@GetMapping("/{type}")` im Quelltext stehen? Nein — Spring bevorzugt bei gleichrangigen Kandidaten das literale Pfadsegment gegenüber der Pfadvariable, die Reihenfolge im Quelltext spielt keine Rolle. Task 6 belegt das mit einem Test.

- [ ] **Step 4: Kompilieren und Tests laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=SecurityRulesTest+MeterConsumptionSeriesServiceTest
```

Erwartet: BUILD SUCCESS.

- [ ] **Step 5: Committen**

```bash
git add backend/src/main/java/com/household/manager/controller/MeterReadingController.java backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
git commit -m "feat(consumption): Serien-Endpunkt unter /v1/meter-readings/series"
```

---

## Task 6: Controller-Test — `/series` darf nicht als Zählertyp gelesen werden

**Files:**
- Create: `backend/src/test/java/com/household/manager/controller/MeterReadingSeriesControllerTest.java`

`GET /v1/meter-readings/{type}` und `GET /v1/meter-readings/series` konkurrieren um denselben Pfad. Spring löst das zugunsten des literalen Segments auf — aber das ist eine Annahme über Framework-Verhalten, und wenn sie kippt, liefert die Ansicht still einen 400 („No enum constant MeterType.series"). Ein Test hält sie fest.

- [ ] **Step 1: Den Test schreiben**

Datei `backend/src/test/java/com/household/manager/controller/MeterReadingSeriesControllerTest.java`:

```java
package com.household.manager.controller;

import com.household.manager.dto.ConsumptionPoint;
import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.importer.MeterReadingCsvImporter;
import com.household.manager.model.entity.MeterType;
import com.household.manager.service.ConsumptionRange;
import com.household.manager.service.MeterConsumptionSeriesService;
import com.household.manager.service.MeterReadingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MeterReadingController.class)
@AutoConfigureMockMvc(addFilters = false)
class MeterReadingSeriesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeterReadingService meterReadingService;
    @MockitoBean
    private MeterReadingCsvImporter meterReadingCsvImporter;
    @MockitoBean
    private MeterConsumptionSeriesService meterConsumptionSeriesService;

    private void stubSeries() {
        when(meterConsumptionSeriesService.getSeries(any())).thenReturn(List.of(
                new MeterConsumptionSeries(MeterType.ELECTRICITY, "kWh", List.of(
                        new ConsumptionPoint(LocalDate.of(2026, 8, 21), "KW 34",
                                new BigDecimal("38.20"), false)))));
    }

    /**
     * "/series" und "/{type}" konkurrieren um denselben Pfad. Spring bevorzugt das
     * literale Segment - kippt das, liefert die Ansicht still einen 400
     * ("No enum constant MeterType.series"), ohne dass es jemandem auffiele.
     */
    @Test
    void liestSeriesAlsEigenenPfadUndNichtAlsZaehlertyp() throws Exception {
        stubSeries();

        mockMvc.perform(get("/v1/meter-readings/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].meterType").value("ELECTRICITY"))
                .andExpect(jsonPath("$[0].unit").value("kWh"))
                .andExpect(jsonPath("$[0].points[0].label").value("KW 34"))
                .andExpect(jsonPath("$[0].points[0].periodStart").value("2026-08-21"))
                .andExpect(jsonPath("$[0].points[0].estimated").value(false));
    }

    @Test
    void nutztOhneParameterDenStandardzeitraum() throws Exception {
        stubSeries();

        mockMvc.perform(get("/v1/meter-readings/series")).andExpect(status().isOk());

        verify(meterConsumptionSeriesService).getSeries(ConsumptionRange.WEEKS_26);
    }

    @Test
    void reichtDenGewaehltenZeitraumDurch() throws Exception {
        stubSeries();

        mockMvc.perform(get("/v1/meter-readings/series?range=MONTHS_12"))
                .andExpect(status().isOk());

        verify(meterConsumptionSeriesService).getSeries(ConsumptionRange.MONTHS_12);
    }
}
```

- [ ] **Step 2: Test laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest=MeterReadingSeriesControllerTest
```

Erwartet: BUILD SUCCESS, 3 Tests grün. Schlägt der erste mit 400 fehl, muss der Endpunkt einen eigenen Pfad bekommen (z. B. `/v1/meter-consumption-series`) — und dann auch in `SecurityRulesTest` und im Frontend-Service nachgezogen werden.

- [ ] **Step 3: Committen**

```bash
git add backend/src/test/java/com/household/manager/controller/MeterReadingSeriesControllerTest.java
git commit -m "test(consumption): /series bleibt ein eigener Pfad, kein Zaehlertyp"
```

---

## Task 7: Frontend-Modell und HTTP-Service

**Files:**
- Create: `frontend/src/app/models/meter-consumption-series.model.ts`
- Create: `frontend/src/app/services/meter-consumption-series.service.ts`

- [ ] **Step 1: Modell anlegen**

Datei `frontend/src/app/models/meter-consumption-series.model.ts`:

```typescript
import { MeterType } from './meter-reading.model';

/** Laenge einer Verbrauchsperiode. Spiegelt das Backend-Enum ConsumptionResolution. */
export type ConsumptionResolution = 'WEEK' | 'MONTH';

/** Waehlbarer Zeitraum. Spiegelt das Backend-Enum ConsumptionRange. */
export type ConsumptionRange =
  | 'WEEKS_8'
  | 'WEEKS_26'
  | 'WEEKS_52'
  | 'MONTHS_6'
  | 'MONTHS_12'
  | 'MONTHS_24';

/** Ein Balken der Verbrauchsansicht. */
export interface ConsumptionPoint {
  /** ISO-Datum (YYYY-MM-DD): Ablesedatum bei Wochen, Monatserster bei Monaten. */
  readonly periodStart: string;
  /** Beschriftung der X-Achse, z. B. "KW 33" oder "Aug 26". */
  readonly label: string;
  readonly consumption: number;
  /** True, sobald mindestens eine beitragende Ablesung ein Schaetzwert war. */
  readonly estimated: boolean;
}

/** Die Verbrauchsreihe genau eines Zaehlertyps - eine Kachel der Ansicht. */
export interface MeterConsumptionSeries {
  readonly meterType: MeterType;
  /** "kWh" bei Strom, "m³" bei Gas und Wasser. */
  readonly unit: string;
  /** Balken, aeltester zuerst. */
  readonly points: ConsumptionPoint[];
}
```

- [ ] **Step 2: Service anlegen**

Datei `frontend/src/app/services/meter-consumption-series.service.ts`:

```typescript
import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ConsumptionRange, MeterConsumptionSeries } from '../models/meter-consumption-series.model';

/** REST-Service fuer die aggregierten Verbrauchsreihen der Tablet-Ansicht. */
@Injectable({ providedIn: 'root' })
export class MeterConsumptionSeriesService {
  private readonly http = inject(HttpClient);
  private readonly url = '/api/v1/meter-readings/series';

  getSeries(range: ConsumptionRange): Observable<MeterConsumptionSeries[]> {
    const params = new HttpParams().set('range', range);
    return this.http.get<MeterConsumptionSeries[]>(this.url, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Verbrauchs-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Verbrauchsdaten.'));
  }
}
```

- [ ] **Step 3: Kompilieren**

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```

Erwartet: keine Ausgabe (Exit 0).

- [ ] **Step 4: Committen**

```bash
git add frontend/src/app/models/meter-consumption-series.model.ts frontend/src/app/services/meter-consumption-series.service.ts
git commit -m "feat(consumption): Frontend-Modell und HTTP-Service"
```

---

## Task 8: `consumption-view.util.ts` — Zeiträume und Vorperiodenvergleich

**Files:**
- Create: `frontend/src/app/shared/consumption-view.util.ts`
- Test: `frontend/src/app/shared/consumption-view.util.spec.ts`

Reine Funktionen ohne Angular — dieselbe Bauform wie `pet-food-level.util.ts` und `walk-format.util.ts`. Hier liegt die einzige Definition der Zeitraumoptionen und der Vergleichslogik.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Datei `frontend/src/app/shared/consumption-view.util.spec.ts`:

```typescript
import {
  RANGE_OPTIONS,
  compareToPrevious,
  defaultRangeFor,
  formatConsumption
} from './consumption-view.util';
import { ConsumptionPoint } from '../models/meter-consumption-series.model';

describe('consumption-view.util', () => {
  function point(consumption: number): ConsumptionPoint {
    return { periodStart: '2026-08-21', label: 'KW 34', consumption, estimated: false };
  }

  describe('RANGE_OPTIONS', () => {
    it('bietet je Aufloesung drei Zeitraeume', () => {
      expect(RANGE_OPTIONS.WEEK.map(o => o.value)).toEqual(['WEEKS_8', 'WEEKS_26', 'WEEKS_52']);
      expect(RANGE_OPTIONS.MONTH.map(o => o.value)).toEqual(['MONTHS_6', 'MONTHS_12', 'MONTHS_24']);
    });
  });

  describe('defaultRangeFor', () => {
    // Beim Wechsel der Aufloesung soll der Default der NEUEN Aufloesung gelten,
    // nicht der gleiche Index - sonst spraenge man von "8 Wochen" auf "6 Monate".
    it('nennt je Aufloesung ihren Standardzeitraum', () => {
      expect(defaultRangeFor('WEEK')).toBe('WEEKS_26');
      expect(defaultRangeFor('MONTH')).toBe('MONTHS_12');
    });
  });

  describe('compareToPrevious', () => {
    it('nennt die Veraenderung gegenueber der Vorwoche in Prozent', () => {
      expect(compareToPrevious([point(100), point(112)], 'WEEK'))
        .toBe('+12 % ggü. Vorwoche');
    });

    it('nennt einen Rueckgang mit Minuszeichen', () => {
      expect(compareToPrevious([point(100), point(88)], 'WEEK'))
        .toBe('-12 % ggü. Vorwoche');
    });

    it('spricht bei Monaten vom Vormonat', () => {
      expect(compareToPrevious([point(100), point(112)], 'MONTH'))
        .toBe('+12 % ggü. Vormonat');
    });

    // Bei einem einzigen Punkt gibt es nichts zu vergleichen - "+0 %" waere gelogen.
    it('gibt bei weniger als zwei Punkten nichts zurueck', () => {
      expect(compareToPrevious([point(100)], 'WEEK')).toBeNull();
      expect(compareToPrevious([], 'WEEK')).toBeNull();
    });

    // Division durch null: aus 0 auf irgendwas ist keine Prozentaussage.
    it('gibt nichts zurueck, wenn die Vorperiode null war', () => {
      expect(compareToPrevious([point(0), point(38)], 'WEEK')).toBeNull();
    });

    it('rundet auf ganze Prozent', () => {
      expect(compareToPrevious([point(100), point(103.4)], 'WEEK'))
        .toBe('+3 % ggü. Vorwoche');
    });
  });

  describe('formatConsumption', () => {
    it('zeigt eine Nachkommastelle mit deutschem Komma', () => {
      expect(formatConsumption(38.24, 'kWh')).toBe('38,2 kWh');
    });

    it('zeigt bei fehlendem Wert einen Platzhalter', () => {
      expect(formatConsumption(null, 'kWh')).toBe('–');
    });
  });
});
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/consumption-view.util.spec.ts'
```

Erwartet: Fehlschlag — das Modul existiert nicht.

- [ ] **Step 3: Util implementieren**

Datei `frontend/src/app/shared/consumption-view.util.ts`:

```typescript
import {
  ConsumptionPoint,
  ConsumptionRange,
  ConsumptionResolution
} from '../models/meter-consumption-series.model';

/** Ein waehlbarer Zeitraum mit seiner Beschriftung. */
export interface RangeOption {
  readonly value: ConsumptionRange;
  readonly label: string;
}

/**
 * Die waehlbaren Zeitraeume je Aufloesung. Einzige Definition - Komponente und
 * Template lesen dieselbe Konstante.
 *
 * Die Aufloesung schaltet die Zeitraeume um, statt beide unabhaengig zu lassen:
 * "8 Wochen monatlich" ergaebe ein Diagramm mit zwei Balken.
 */
export const RANGE_OPTIONS: Record<ConsumptionResolution, readonly RangeOption[]> = {
  WEEK: [
    { value: 'WEEKS_8', label: '8 Wochen' },
    { value: 'WEEKS_26', label: '26 Wochen' },
    { value: 'WEEKS_52', label: '52 Wochen' }
  ],
  MONTH: [
    { value: 'MONTHS_6', label: '6 Monate' },
    { value: 'MONTHS_12', label: '12 Monate' },
    { value: 'MONTHS_24', label: '24 Monate' }
  ]
};

const DEFAULT_RANGE: Record<ConsumptionResolution, ConsumptionRange> = {
  WEEK: 'WEEKS_26',
  MONTH: 'MONTHS_12'
};

/**
 * Standardzeitraum einer Aufloesung. Beim Umschalten gilt bewusst der Default der
 * NEUEN Aufloesung und nicht der gleiche Index - sonst landete man von "8 Wochen"
 * bei "6 Monaten" und die Ansicht spraenge auf einen ganz anderen Massstab.
 */
export function defaultRangeFor(resolution: ConsumptionResolution): ConsumptionRange {
  return DEFAULT_RANGE[resolution];
}

const PREVIOUS_LABEL: Record<ConsumptionResolution, string> = {
  WEEK: 'Vorwoche',
  MONTH: 'Vormonat'
};

/**
 * Veraenderung des letzten Werts gegenueber dem vorletzten, z. B. "+12 % ggü. Vorwoche".
 *
 * Gibt null zurueck, wenn es nichts zu vergleichen gibt: bei weniger als zwei Punkten
 * oder wenn die Vorperiode 0 war. Ein "+0 %" oder "+∞ %" waere in beiden Faellen eine
 * Aussage, die die Daten nicht hergeben.
 */
export function compareToPrevious(
  points: readonly ConsumptionPoint[],
  resolution: ConsumptionResolution
): string | null {
  if (points.length < 2) {
    return null;
  }
  const previous = points[points.length - 2].consumption;
  const current = points[points.length - 1].consumption;
  if (previous === 0) {
    return null;
  }
  const percent = Math.round(((current - previous) / previous) * 100);
  const sign = percent >= 0 ? '+' : '';
  return `${sign}${percent} % ggü. ${PREVIOUS_LABEL[resolution]}`;
}

/** Verbrauchswert mit Einheit, eine Nachkommastelle, deutsches Komma. */
export function formatConsumption(value: number | null, unit: string): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '–';
  }
  return `${value.toLocaleString('de-DE', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1
  })} ${unit}`;
}
```

- [ ] **Step 4: Tests laufen lassen, grün bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/consumption-view.util.spec.ts'
```

Erwartet: „Executed 10 of 10 SUCCESS".

- [ ] **Step 5: Committen**

```bash
git add frontend/src/app/shared/consumption-view.util.ts frontend/src/app/shared/consumption-view.util.spec.ts
git commit -m "feat(consumption): Zeitraumoptionen und Vorperiodenvergleich als reine Util"
```

---

## Task 9: Seitenkomponente

**Files:**
- Create: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.ts`
- Create: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.html`
- Create: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.scss`
- Test: `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.spec.ts`

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Datei `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.spec.ts`:

```typescript
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { TabletConsumptionComponent } from './tablet-consumption.component';
import { MeterConsumptionSeriesService } from '../../services/meter-consumption-series.service';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { MeterConsumptionSeries } from '../../models/meter-consumption-series.model';
import { MeterType } from '../../models/meter-reading.model';

describe('TabletConsumptionComponent', () => {
  let fixture: ComponentFixture<TabletConsumptionComponent>;
  let component: TabletConsumptionComponent;
  let serviceSpy: jasmine.SpyObj<MeterConsumptionSeriesService>;
  let weatherSpy: jasmine.SpyObj<WeatherService>;

  const strom: MeterConsumptionSeries = {
    meterType: MeterType.ELECTRICITY,
    unit: 'kWh',
    points: [
      { periodStart: '2026-08-14', label: 'KW 33', consumption: 34, estimated: false },
      { periodStart: '2026-08-21', label: 'KW 34', consumption: 38.08, estimated: true }
    ]
  };
  const wasser: MeterConsumptionSeries = {
    meterType: MeterType.WATER,
    unit: 'm³',
    points: [
      { periodStart: '2026-08-21', label: 'KW 34', consumption: 2.4, estimated: false }
    ]
  };

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj('MeterConsumptionSeriesService', ['getSeries']);
    serviceSpy.getSeries.and.returnValue(of([strom, wasser]));

    weatherSpy = jasmine.createSpyObj('WeatherService', ['getOverview']);
    weatherSpy.getOverview.and.returnValue(
      of({ current: { temperature: 18, icon: 1 } } as unknown as WeatherOverview));

    await TestBed.configureTestingModule({
      imports: [TabletConsumptionComponent],
      providers: [
        // app-tablet-shell nutzt routerLink fuer die Ansichtsleiste und das Wetter
        // fuer die Kopfzeile.
        provideRouter([]),
        { provide: MeterConsumptionSeriesService, useValue: serviceSpy },
        { provide: WeatherService, useValue: weatherSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(TabletConsumptionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  it('lädt beim Start den Standardzeitraum WEEKS_26', () => {
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('WEEKS_26');
  });

  it('baut je Zaehlertyp eine Kachel mit deutschem Namen', () => {
    expect(component.tiles.map(t => t.name)).toEqual(['Strom', 'Wasser']);
  });

  it('stellt den letzten Wert mit Einheit in den Kachelkopf', () => {
    expect(component.tiles[0].currentLabel).toBe('38,1 kWh');
    expect(component.tiles[1].currentLabel).toBe('2,4 m³');
  });

  it('nennt die Veraenderung gegenueber der Vorperiode', () => {
    expect(component.tiles[0].comparison).toBe('+12 % ggü. Vorwoche');
  });

  it('laesst den Vergleich bei nur einem Punkt weg', () => {
    expect(component.tiles[1].comparison).toBeNull();
  });

  it('faerbt geschaetzte Balken blasser als echte', () => {
    const options = component.tiles[0].options as {
      series: { data: { value: [string, number]; itemStyle: { opacity: number } }[] }[];
    };
    const [echt, geschaetzt] = options.series[0].data;
    expect(echt.itemStyle.opacity).toBe(1);
    expect(geschaetzt.itemStyle.opacity).toBeLessThan(1);
  });

  it('meldet, ob ueberhaupt ein Schaetzwert im Bild ist', () => {
    expect(component.tiles[0].hasEstimated).toBeTrue();
    expect(component.tiles[1].hasEstimated).toBeFalse();
  });

  it('lädt bei einem Zeitraumwechsel genau einmal nach', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEKS_52');
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('WEEKS_52');
    expect(component.activeRange).toBe('WEEKS_52');
  });

  it('lädt nicht nach, wenn der aktive Zeitraum erneut gewaehlt wird', () => {
    serviceSpy.getSeries.calls.reset();
    component.setRange('WEEKS_26');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  /**
   * Beim Wechsel der Aufloesung gilt der Default der NEUEN Aufloesung, nicht der
   * gleiche Index - sonst spraenge die Ansicht von "8 Wochen" auf "6 Monate".
   */
  it('setzt beim Aufloesungswechsel den Standardzeitraum der neuen Aufloesung', () => {
    component.setRange('WEEKS_8');
    serviceSpy.getSeries.calls.reset();

    component.setResolution('MONTH');

    expect(component.activeResolution).toBe('MONTH');
    expect(component.activeRange).toBe('MONTHS_12');
    expect(serviceSpy.getSeries).toHaveBeenCalledOnceWith('MONTHS_12');
  });

  it('tauscht beim Aufloesungswechsel die Zeitraumknoepfe aus', () => {
    component.setResolution('MONTH');
    expect(component.ranges.map(r => r.value))
      .toEqual(['MONTHS_6', 'MONTHS_12', 'MONTHS_24']);
  });

  it('lädt nicht nach, wenn die aktive Aufloesung erneut gewaehlt wird', () => {
    serviceSpy.getSeries.calls.reset();
    component.setResolution('WEEK');
    expect(serviceSpy.getSeries).not.toHaveBeenCalled();
  });

  /**
   * Ein synchroner of()-Stub verdeckt, ob die Kacheln schon VOR der Antwort neu
   * gebaut werden - deshalb hier ein Subject, das erst auf Kommando liefert.
   */
  it('zeigt beim Zeitraumwechsel bis zur Antwort weiter die alten Kacheln', () => {
    const pending = new Subject<MeterConsumptionSeries[]>();
    serviceSpy.getSeries.and.returnValue(pending.asObservable());

    component.setRange('WEEKS_52');

    expect(component.tiles.length).toBe(2);
    pending.next([strom]);
    pending.complete();
    expect(component.tiles.length).toBe(1);
  });

  it('behält bei einem fehlgeschlagenen Refresh die bisherigen Daten', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    component.reload();
    expect(component.tiles.length).toBe(2);
    expect(component.errorMessage).toBeNull();
  });

  it('meldet einen Fehler, wenn schon der Erstabruf scheitert', () => {
    serviceSpy.getSeries.and.returnValue(throwError(() => new Error('offline')));
    const freshFixture = TestBed.createComponent(TabletConsumptionComponent);
    freshFixture.detectChanges();
    expect(freshFixture.componentInstance.errorMessage).not.toBeNull();
    freshFixture.destroy();
  });

  it('meldet leer, wenn kein Zaehler Werte liefert', () => {
    serviceSpy.getSeries.and.returnValue(of([]));
    component.setRange('WEEKS_8');
    expect(component.isEmpty).toBeTrue();
  });

  it('gibt den Graphen die Bildschirmhoehe statt der Inhaltshoehe', () => {
    // Nur wenn die Flex-Kette vom Host bis zum Chart durchgehend ist, waechst der
    // Graph mit dem Bildschirm - sonst bleibt er auf Inhaltshoehe stehen.
    //
    // Bewusst ein EIGENER Container statt des Elternknotens: das Fixture haengt
    // direkt im <body>, und dort stehen auch Karmas eigene Elemente und die
    // Wurzelknoten schon gelaufener Suiten. Macht man den body zur Flex-Spalte,
    // teilen sich all diese Geschwister die Hoehe, und der Graph wird je nach
    // Reihenfolge der Suiten winzig.
    //
    // Gemessen wird bei 900 und 1200 px, nicht bei 600/900 wie in der Temperatur-
    // und Luftqualitaetsansicht: die Kacheln tragen ueber dem Graphen einen
    // zweizeiligen Kopf und darunter die Schaetzwert-Legende, bei 600 px bliebe
    // dem Graphen strukturell kaum etwas, ohne dass an der Kette etwas kaputt waere.
    const host = fixture.nativeElement as HTMLElement;
    const frame = document.createElement('div');
    frame.style.display = 'flex';
    frame.style.flexDirection = 'column';
    document.body.appendChild(frame);
    frame.appendChild(host);

    const chartHeights = (): number[] =>
      Array.from(host.querySelectorAll('.tablet-consumption__chart'))
        .map(chart => chart.getBoundingClientRect().height);

    frame.style.height = '900px';
    fixture.detectChanges();
    const small = chartHeights();

    frame.style.height = '1200px';
    fixture.detectChanges();
    const large = chartHeights();

    expect(small.length).toBe(2);
    small.forEach(height => expect(height).toBeGreaterThan(200));
    // Die drei Kacheln stehen nebeneinander, also kommt der ganze Zuwachs an.
    large.forEach((height, i) => expect(height).toBeGreaterThan(small[i] + 200));

    // Den Host zurueck in den body haengen, damit fixture.destroy() unveraendert laeuft.
    document.body.appendChild(host);
    frame.remove();
  });
});
```

- [ ] **Step 2: Tests laufen lassen, Fehlschlag bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-consumption.component.spec.ts'
```

Erwartet: Fehlschlag — die Komponente existiert nicht.

- [ ] **Step 3: Komponente anlegen**

Datei `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.ts`:

```typescript
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { MeterConsumptionSeriesService } from '../../services/meter-consumption-series.service';
import {
  ConsumptionRange,
  ConsumptionResolution,
  MeterConsumptionSeries
} from '../../models/meter-consumption-series.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';
import {
  RANGE_OPTIONS,
  RangeOption,
  compareToPrevious,
  defaultRangeFor,
  formatConsumption
} from '../../shared/consumption-view.util';

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface ResolutionOption {
  readonly value: ConsumptionResolution;
  readonly label: string;
}

/** Eine Zaehlerkachel des Rasters. */
interface ConsumptionTile {
  /** Zaehlertyp als Schluessel des @for-track. */
  readonly key: string;
  readonly name: string;
  /** Letzter Wert mit Einheit, z. B. "38,1 kWh". */
  readonly currentLabel: string;
  /** Veraenderung zur Vorperiode, null wenn nicht vergleichbar. */
  readonly comparison: string | null;
  /** True, wenn mindestens ein Balken ein Schaetzwert ist - steuert die Legende. */
  readonly hasEstimated: boolean;
  readonly options: Record<string, unknown>;
}

const AXIS_COLOR = '#94a3b8';
/** Deckkraft geschaetzter Balken - sichtbar blasser, aber noch klar erkennbar. */
const ESTIMATED_OPACITY = 0.45;

/**
 * Verbrauchsuebersicht fuer das Wandtablet: Strom, Gas und Wasser nebeneinander,
 * je Ablesewoche oder Kalendermonat, ohne Scrollen.
 */
@Component({
  selector: 'app-tablet-consumption',
  standalone: true,
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-consumption.component.html',
  styleUrl: './tablet-consumption.component.scss'
})
export class TabletConsumptionComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly seriesService = inject(MeterConsumptionSeriesService);
  private refreshTimer: number | null = null;

  readonly resolutions: ResolutionOption[] = [
    { value: 'WEEK', label: 'Woche' },
    { value: 'MONTH', label: 'Monat' }
  ];

  activeResolution: ConsumptionResolution = 'WEEK';
  activeRange: ConsumptionRange = defaultRangeFor('WEEK');
  tiles: ConsumptionTile[] = [];
  isLoading = true;
  isEmpty = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load(this.activeRange);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletConsumptionComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  /** Die Zeitraumknoepfe der aktiven Aufloesung. */
  get ranges(): readonly RangeOption[] {
    return RANGE_OPTIONS[this.activeResolution];
  }

  setRange(range: ConsumptionRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.load(range);
  }

  /**
   * Wechselt die Aufloesung und setzt dabei den Standardzeitraum der NEUEN
   * Aufloesung - nicht den gleichen Index. Sonst landete man von "8 Wochen" bei
   * "6 Monaten" und die Ansicht spraenge auf einen ganz anderen Massstab.
   */
  setResolution(resolution: ConsumptionResolution): void {
    if (resolution === this.activeResolution) {
      return;
    }
    this.activeResolution = resolution;
    this.activeRange = defaultRangeFor(resolution);
    this.load(this.activeRange);
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.load(this.activeRange, true);
  }

  private load(range: ConsumptionRange, silent = false): void {
    if (!silent) {
      this.isLoading = true;
      this.errorMessage = null;
    }
    const resolution = this.activeResolution;
    this.seriesService.getSeries(range).subscribe({
      next: series => {
        this.tiles = series.map(s => this.toTile(s, resolution));
        this.isEmpty = this.tiles.length === 0;
        this.errorMessage = null;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Verbrauchsdaten:', error);
        this.isLoading = false;
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte nicht
        // durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer Wandanzeige
        // mehr wert als gar keine.
        if (!silent) {
          this.errorMessage = 'Verbrauchsdaten konnten nicht geladen werden.';
        }
      }
    });
  }

  private toTile(
    series: MeterConsumptionSeries,
    resolution: ConsumptionResolution
  ): ConsumptionTile {
    const last = series.points.length > 0
      ? series.points[series.points.length - 1].consumption
      : null;
    return {
      key: series.meterType,
      name: MeterTypeUtils.getLabel(series.meterType),
      currentLabel: formatConsumption(last, series.unit),
      comparison: compareToPrevious(series.points, resolution),
      hasEstimated: series.points.some(p => p.estimated),
      options: this.chartOptionsFor(series)
    };
  }

  /**
   * Balkendiagramm einer Kachel. Geschaetzte Balken bekommen dieselbe Farbe mit
   * geringerer Deckkraft - sichtbar, dass diese Woche nicht wirklich abgelesen wurde,
   * ohne sie aus der Summe zu nehmen.
   */
  private chartOptionsFor(series: MeterConsumptionSeries): Record<string, unknown> {
    const color = MeterTypeUtils.getColor(series.meterType);
    const axisLabel = { color: AXIS_COLOR, fontSize: 12 };

    return {
      grid: { left: 52, right: 12, top: 12, bottom: 30, containLabel: false },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value: number) => formatConsumption(value, series.unit)
      },
      xAxis: {
        type: 'category',
        data: series.points.map(p => p.label),
        axisLabel: { ...axisLabel, hideOverlap: true }
      },
      yAxis: {
        type: 'value',
        axisLabel: { ...axisLabel, formatter: `{value} ${series.unit}` },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      },
      series: [
        {
          type: 'bar',
          data: series.points.map(p => ({
            value: [p.label, p.consumption],
            itemStyle: {
              color,
              opacity: p.estimated ? ESTIMATED_OPACITY : 1,
              borderColor: color,
              borderType: p.estimated ? 'dashed' : 'solid',
              borderWidth: p.estimated ? 1 : 0
            }
          }))
        }
      ]
    };
  }
}
```

- [ ] **Step 4: Markup anlegen**

Datei `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.html`:

```html
<app-tablet-shell heading="Verbrauch">
  <div shellActions class="tablet-consumption__controls">
    <div class="tablet-consumption__group" role="group" aria-label="Auflösung">
      @for (resolution of resolutions; track resolution.value) {
        <button
          type="button"
          class="tablet-consumption__btn"
          [class.tablet-consumption__btn--active]="resolution.value === activeResolution"
          [attr.aria-pressed]="resolution.value === activeResolution"
          (click)="setResolution(resolution.value)">
          {{ resolution.label }}
        </button>
      }
    </div>
    <div class="tablet-consumption__group" role="group" aria-label="Zeitraum">
      @for (range of ranges; track range.value) {
        <button
          type="button"
          class="tablet-consumption__btn"
          [class.tablet-consumption__btn--active]="range.value === activeRange"
          (click)="setRange(range.value)">
          {{ range.label }}
        </button>
      }
    </div>
  </div>

  <section class="tablet-consumption">
    @if (isLoading && tiles.length === 0) {
      <p class="tablet-consumption__status">Lade Verbrauchsdaten…</p>
    } @else if (errorMessage && tiles.length === 0) {
      <p class="tablet-consumption__status tablet-consumption__status--error">
        {{ errorMessage }}
      </p>
    } @else if (isEmpty) {
      <p class="tablet-consumption__status">Keine Zählerablesungen im gewählten Zeitraum.</p>
    } @else {
      <div class="tablet-consumption__grid">
        @for (tile of tiles; track tile.key) {
          <article class="tablet-consumption__card">
            <header class="tablet-consumption__card-head">
              <h3 class="tablet-consumption__card-title">{{ tile.name }}</h3>
              <div class="tablet-consumption__figures">
                <span class="tablet-consumption__current">{{ tile.currentLabel }}</span>
                @if (tile.comparison) {
                  <span class="tablet-consumption__comparison">{{ tile.comparison }}</span>
                }
              </div>
            </header>
            <div
              echarts
              class="tablet-consumption__chart"
              [options]="tile.options"
              [autoResize]="true"></div>
            @if (tile.hasEstimated) {
              <p class="tablet-consumption__legend">Blasse Balken sind Schätzwerte</p>
            }
          </article>
        }
      </div>
    }
  </section>
</app-tablet-shell>
```

- [ ] **Step 5: Styles anlegen**

Datei `frontend/src/app/pages/tablet-consumption/tablet-consumption.component.scss`:

```scss
// Inhalt der Tablet-Verbrauchsansicht. Kopfzeile und Ansichtsleiste liefert
// app-tablet-shell; hier bleibt nur das Kachelraster.

// Die Hoehe kommt ueber eine durchgehende Flex-Kette von .app-layout (100vh) bis
// zum Chart. Fehlt sie an EINER Stelle, faellt alles darunter auf Inhaltshoehe
// zurueck und die Graphen werden winzig - deshalb traegt schon das Host-Element
// der Seite flex: 1.
:host {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.tablet-consumption {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  gap: 0.75rem;
  color: #e4e2e4;

  &__controls {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__group {
    display: inline-flex;
    align-self: center;
    gap: 0.25rem;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 12px;
    padding: 0.3rem;
    flex: 0 0 auto;
  }

  &__btn {
    border: none;
    background: transparent;
    padding: 0.6rem 1.3rem;
    border-radius: 9px;
    cursor: pointer;
    font: inherit;
    font-size: 0.95rem;
    color: rgba(228, 226, 228, 0.75);

    &--active {
      background: rgba(170, 199, 255, 0.16);
      color: #aac7ff;
    }
  }

  &__status {
    flex: 1 1 auto;
    display: grid;
    place-items: center;
    margin: 0;
    color: #94a3b8;
    font-size: 1.25rem;

    &--error {
      color: #ffb4ab;
    }
  }

  // Drei Zaehler nebeneinander, feste Spaltenzahl: mehr Typen gibt es nicht.
  &__grid {
    flex: 1 1 auto;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    grid-auto-rows: 1fr;
    gap: 1rem;
    min-height: 0;
  }

  &__card {
    display: flex;
    flex-direction: column;
    min-height: 0;
    padding: 0.75rem 0.9rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 1rem;
    background: rgba(255, 255, 255, 0.03);
  }

  &__card-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.5rem;
    flex: 0 0 auto;
    margin-bottom: 0.35rem;
  }

  &__card-title {
    margin: 0;
    font-size: 1.15rem;
    font-weight: 600;
    // Die globale Regel h1..h6 { color: var(--color-dark) } aus styles.scss schlaegt
    // die vererbte helle Farbe - auf schwarzem Grund waere der Titel sonst kaum
    // lesbar.
    color: #f4f3f5;
    letter-spacing: -0.01em;
  }

  &__figures {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    line-height: 1.15;
  }

  &__current {
    font-size: 1.6rem;
    font-weight: 600;
    color: #f4f3f5;
  }

  // Bewusst neutral gefaerbt: ob mehr Verbrauch schlecht ist, haengt an Jahreszeit
  // und Anlass - eine rote Zahl im Winter waere eine Wertung, die diese Seite nicht
  // treffen kann.
  &__comparison {
    font-size: 0.85rem;
    color: rgba(148, 163, 184, 0.9);
  }

  &__chart {
    flex: 1 1 auto;
    width: 100%;
    min-height: 0;
  }

  &__legend {
    flex: 0 0 auto;
    margin: 0.3rem 0 0;
    font-size: 0.8rem;
    color: rgba(148, 163, 184, 0.8);
  }
}
```

- [ ] **Step 6: Tests laufen lassen, grün bestätigen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include='**/tablet-consumption.component.spec.ts'
```

Erwartet: „Executed 17 of 17 SUCCESS".

- [ ] **Step 7: Committen**

```bash
git add frontend/src/app/pages/tablet-consumption/
git commit -m "feat(consumption): Tablet-Ansicht Verbrauch"
```

---

## Task 10: Route und Ansichtsleiste

**Files:**
- Modify: `frontend/src/app/shared/tablet-views.ts`
- Modify: `frontend/src/app/app.routes.ts:83-86`

Erst hier wird die Seite erreichbar. Der Eintrag in `TABLET_VIEWS` genügt für **beide** Leisten — Dashboard und Tablet-Shell lesen dieselbe Konstante.

- [ ] **Step 1: Eintrag in `TABLET_VIEWS` ergänzen**

In `frontend/src/app/shared/tablet-views.ts` das Array ersetzen durch:

```typescript
export const TABLET_VIEWS: readonly TabletView[] = [
  { route: '/tablet/temperatures', icon: 'thermostat', label: 'Temperaturen' },
  { route: '/tablet/air-quality', icon: 'air', label: 'Luftqualität' },
  { route: '/tablet/consumption', icon: 'electric_meter', label: 'Verbrauch' },
  { route: '/tablet/toni', icon: 'pets', label: 'Toni' }
];
```

- [ ] **Step 2: Route ergänzen**

In `frontend/src/app/app.routes.ts` direkt **vor** dem Block `path: 'tablet/toni'`
(aktuell Zeile 82) einfügen — gleicher Aufbau wie die Nachbarrouten, `authGuard` wie dort:

```typescript
  {
    path: 'tablet/consumption',
    loadComponent: () => import('./pages/tablet-consumption/tablet-consumption.component').then(m => m.TabletConsumptionComponent),
    canActivate: [authGuard],
    title: 'Verbrauch Tablet - Household Manager'
  },
```

- [ ] **Step 3: Bauen**

```bash
cd frontend && npm run build
```

Erwartet: erfolgreicher Build. **Achtung:** `dashboard.component.scss` reißt bereits das `anyComponentStyle`-Budget — falls dort ein Budget-ERROR auftaucht, ist das vorbestehend und nicht Teil dieser Arbeit; ein neuer Budget-Fehler für `tablet-consumption.component.scss` wäre dagegen echt.

- [ ] **Step 4: Vollen Frontend-Testlauf machen**

```bash
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Erwartet: genau die 3 bekannten Baseline-Fails (`AppComponent should render title`, `AppComponent should have the 'household-manager' title`, `HeroComponent should create`), sonst grün. Ein vierter Fail in `SmartDeviceListComponent` (`afterAll`, „Cannot read properties of undefined") ist der bekannte Flake — dann einmal erneut laufen lassen.

- [ ] **Step 5: Committen**

```bash
git add frontend/src/app/shared/tablet-views.ts frontend/src/app/app.routes.ts
git commit -m "feat(consumption): Route und Eintrag in der Ansichtsleiste"
```

---

## Task 11: Dokumentation und Abschluss

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Tablet-Ansichten")

- [ ] **Step 1: Backend-Tests dieser Arbeit noch einmal zusammen laufen lassen**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" && cd backend && mvn -q test -Dtest='ConsumptionRangeTest+MeterConsumptionSeriesServiceTest+MeterReadingSeriesControllerTest+SecurityRulesTest'
```

Erwartet: BUILD SUCCESS.

- [ ] **Step 2: `CLAUDE.md` ergänzen**

Im Abschnitt „Tablet-Ansichten (Unterseiten des Wandtablets)", **nach** dem Absatzblock zur dritten Ansicht (`/tablet/toni`), einfügen:

```markdown
- Vierte Ansicht: `/tablet/consumption` (`pages/tablet-consumption/`). Strom, Gas und Wasser nebeneinander als Balken je Ablesewoche oder Kalendermonat. Datenquelle ist `GET /v1/meter-readings/series?range=…` (`MeterConsumptionSeriesService`), über die generische `GET /v1/**`-Regel KIOSK-lesbar — **keine** eigene Zeile in `SecurityConfig`, `SecurityRulesTest` hält beide Richtungen fest
- **Der Verbrauch wird im Serien-Service neu berechnet, nicht übernommen:** das `consumption`-Feld von `MeterReadingResponse` ist nur bei der **jeweils neuesten** Ablesung eines Typs gefüllt (`convertToResponseWithConsumption` prüft `lastTwoReadings.get(0).getId().equals(reading.getId())`), und `getAllMeterReadings` beschränkt zusätzlich aufs laufende Kalenderjahr — über die bestehende Liste wären 52 Wochen oder 24 Monate gar nicht lieferbar. Der Service lädt deshalb **alle** Ablesungen eines Typs aufsteigend und bildet die Differenzen selbst; er filtert erst **danach** aufs Fenster, weil der älteste Balken die Ablesung **davor** braucht
- **`ConsumptionRange` ist ein eigenes Enum, nicht `SeriesRange`:** Letzteres beschreibt bei Temperatur und Luftqualität Fenster von **Tagen** und kann Wochen-/Monatszahlen nicht ausdrücken. Auflösung und Periodenzahl stecken **zusammen** in einem Wert (`WEEKS_8`, `MONTHS_12`, …), damit eine unmögliche Kombination wie „8 Wochen, monatlich" gar nicht erst ausdrückbar ist
- **Eine Ablesewoche über dem Monatswechsel zählt vollständig in den Monat ihres Ablesedatums.** Bewusst gegen tagesgenaues Aufteilen: der Balken entspricht so weiterhin echten Ablesungen. Preis: an Monatsgrenzen leicht unscharf. Ein Monatsbalken gilt als `estimated`, sobald **mindestens eine** beitragende Woche geschätzt war — sonst verschwände eine geschätzte Woche in einem sonst echten Monat spurlos
- **Eine negative Differenz (Zählertausch/-reset) wird verworfen und geloggt**, statt einen Minusbalken zu zeigen. Beim Anlegen über die API verhindert `validateReadingValue` solche Werte; der **CSV-Import** ist der Weg, auf dem sie trotzdem in der Tabelle landen. Monate ohne Ablesung entstehen gar nicht erst — eine erfundene Null wäre von echtem Nullverbrauch nicht zu unterscheiden
- `/series` und `/{type}` konkurrieren um denselben Pfad; Spring bevorzugt das literale Segment. Kippt das je, liefert die Ansicht still einen 400 („No enum constant MeterType.series") — `MeterReadingSeriesControllerTest` hält es deshalb fest
- Die beiden Umschalter sind **gekoppelt**: die Auflösung tauscht die Zeitraumknöpfe aus und setzt den Default der **neuen** Auflösung (Woche → 26 Wochen, Monat → 12 Monate), nicht den gleichen Index — sonst spränge man von „8 Wochen" auf „6 Monate". Einzige Definition: `shared/consumption-view.util.ts`
- Der Vorperiodenvergleich im Kachelkopf ist bewusst **nicht eingefärbt**: ob mehr Verbrauch schlecht ist, hängt an Jahreszeit und Anlass. Er entfällt wortlos bei weniger als zwei Punkten oder wenn die Vorperiode 0 war (Division durch null)
- **52 Balken in einer Drittelspalte werden schmal** (grob 4–6 px) — der akzeptierte Preis der Nebeneinander-Anordnung: als Verlaufsbild funktioniert es, einzelne Wochen liest man dort nicht ab
- Der Höhenketten-Test dieser Ansicht misst bei **900 und 1200 px** (nicht 600/900 wie Temperatur und Luftqualität): die Kacheln tragen über dem Graphen einen zweizeiligen Kopf und darunter die Schätzwert-Legende
```

- [ ] **Step 3: Committen**

```bash
git add CLAUDE.md
git commit -m "docs(claude): Tablet-Ansicht Verbrauch festhalten"
```

- [ ] **Step 4: Übergabe**

Alle Tasks sind erledigt. Der Zweig `feature/tablet-verbrauch` steht bereit; die Zusammenführung nach `main` läuft über die `superpowers:finishing-a-development-branch`-Skill und ist **nicht** Teil dieses Plans.

**Nicht verifiziert und offen:** Die Ansicht wurde nie gegen echte Zählerdaten gesehen — insbesondere ist offen, wie voll 52 Wochen in einer Drittelspalte tatsächlich wirken und ob die Monatsbeschriftung („Jul 26") in der JDK-Version dieser Maschine mit oder ohne Punkt herauskommt.
