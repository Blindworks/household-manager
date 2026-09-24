# Verbrauch: Jahres- und Monatssummen – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Tablet-Verbrauchsansicht bekommt eine Jahres-Auflösung im Diagramm und eine Tabellenansicht mit Jahressummen, die sich in die Monate aufklappen lassen.

**Architecture:** Das Backend erweitert nur `ConsumptionResolution` (YEAR) und `ConsumptionRange` (`YEARS_ALL`, `MONTHS_ALL` ohne Fensterbeginn); die bestehende Aggregation in `MeterConsumptionSeriesService` bildet Jahresbalken mit derselben Operation wie Monatsbalken. Das Frontend liest beide Reihen über den unveränderten Endpunkt `/v1/meter-readings/series`; eine reine Funktion `buildTotalsTable` formt sie zur Tabelle.

**Tech Stack:** Spring Boot 3.4 / Java 21 / JUnit 5 + Mockito; Angular 19 standalone / Jasmine + Karma.

**Spec:** `docs/superpowers/specs/2026-09-24-verbrauch-jahres-und-monatssummen-design.md`

**Vorab (Umgebung):** Backend-Tests brauchen JDK 21: in PowerShell `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'` vor `mvn`. Frontend headless: `npx ng test --watch=false --browsers=ChromeHeadless`. 3 bekannte Fails (App/Hero) sind Baseline.

---

## Dateien

| Datei | Änderung |
|---|---|
| `backend/.../service/ConsumptionResolution.java` | `YEAR` |
| `backend/.../service/ConsumptionRange.java` | `YEARS_ALL`, `MONTHS_ALL`, `isUnbounded()`, `windowStart` |
| `backend/.../service/MeterConsumptionSeriesService.java` | Periodenschlüssel/Label/Start für YEAR |
| `backend/src/test/.../service/ConsumptionRangeTest.java` | Tests `…_ALL` |
| `backend/src/test/.../service/MeterConsumptionSeriesServiceTest.java` | Tests YEAR |
| `backend/src/test/.../controller/MeterReadingSeriesControllerTest.java` | `range=YEARS_ALL` |
| `frontend/src/app/models/meter-consumption-series.model.ts` | Typen erweitern |
| `frontend/src/app/shared/consumption-view.util.ts` | YEAR-Optionen, kein Vergleich bei YEAR, `isRunningPeriod`, `buildTotalsTable` |
| `frontend/src/app/shared/consumption-view.util.spec.ts` | Tests |
| `frontend/src/app/pages/tablet-consumption/*` | Jahr-Knopf, „bis heute", Tabellenmodus |

---

### Task 1: Backend – Zeiträume `YEARS_ALL` / `MONTHS_ALL`

**Files:** `ConsumptionResolution.java`, `ConsumptionRange.java`, `ConsumptionRangeTest.java`

- [ ] **Step 1: Failing test** – an `ConsumptionRangeTest` anhängen:

```java
    @Test
    void jahreszeitraumTraegtDieAufloesungJahr() {
        assertThat(ConsumptionRange.YEARS_ALL.getResolution()).isEqualTo(ConsumptionResolution.YEAR);
        assertThat(ConsumptionRange.MONTHS_ALL.getResolution()).isEqualTo(ConsumptionResolution.MONTH);
    }

    /** "Alle" heisst: kein Fensterbeginn, auch die aelteste Ablesung zaehlt. */
    @Test
    void unbegrenzteZeitraeumeHabenKeinenFensterbeginn() {
        LocalDate heute = LocalDate.of(2026, 8, 24);

        assertThat(ConsumptionRange.YEARS_ALL.windowStart(heute)).isEqualTo(LocalDate.MIN);
        assertThat(ConsumptionRange.MONTHS_ALL.windowStart(heute)).isEqualTo(LocalDate.MIN);
        assertThat(ConsumptionRange.YEARS_ALL.isUnbounded()).isTrue();
        assertThat(ConsumptionRange.MONTHS_12.isUnbounded()).isFalse();
    }
```

- [ ] **Step 2:** `mvn test -Dtest=ConsumptionRangeTest` → FAIL (Kompilierfehler `YEARS_ALL`).

- [ ] **Step 3: Implementierung**

`ConsumptionResolution`:

```java
/** Laenge einer Verbrauchsperiode: eine Ablesewoche, ein Kalendermonat oder ein Kalenderjahr. */
public enum ConsumptionResolution {
    WEEK,
    MONTH,
    YEAR
}
```

`ConsumptionRange` – Werte ergänzen und `windowStart` erweitern:

```java
    MONTHS_24(ConsumptionResolution.MONTH, 24),
    /** Ein Balken je Kalenderjahr seit der ersten Ablesung. */
    YEARS_ALL(ConsumptionResolution.YEAR, ConsumptionRange.UNBOUNDED),
    /** Alle Monate seit der ersten Ablesung - Datenquelle der Tabellenansicht. */
    MONTHS_ALL(ConsumptionResolution.MONTH, ConsumptionRange.UNBOUNDED);

    /** Periodenzahl der "alle"-Zeitraeume: kein Fensterbeginn. */
    private static final int UNBOUNDED = 0;
    ...
    public boolean isUnbounded() {
        return periods == UNBOUNDED;
    }

    public LocalDate windowStart(LocalDate today) {
        if (isUnbounded()) {
            return LocalDate.MIN;
        }
        return resolution == ConsumptionResolution.WEEK
                ? today.minusWeeks(periods)
                : today.minusMonths(periods - 1L).withDayOfMonth(1);
    }
```

(Eine `static final`-Konstante im Enum darf im Konstruktoraufruf nur qualifiziert als Compile-Zeit-Konstante stehen – `ConsumptionRange.UNBOUNDED` ist eine solche.)

- [ ] **Step 4:** `mvn test -Dtest=ConsumptionRangeTest` → PASS
- [ ] **Step 5:** Commit `feat(consumption): Zeitraeume YEARS_ALL und MONTHS_ALL ohne Fensterbeginn`

### Task 2: Backend – Jahresaggregation

**Files:** `MeterConsumptionSeriesService.java`, `MeterConsumptionSeriesServiceTest.java`, `MeterReadingSeriesControllerTest.java`

- [ ] **Step 1: Failing tests** – in `MeterConsumptionSeriesServiceTest`:

```java
    @Test
    void fasstWochenZuKalenderjahrenZusammen() {
        stromAblesungen(
                reading(LocalDate.of(2024, 12, 20), "900", false),
                reading(LocalDate.of(2025, 3, 7), "1000", false),
                reading(LocalDate.of(2025, 9, 5), "1500", true),
                reading(LocalDate.of(2026, 2, 6), "1800", false));

        List<ConsumptionPoint> points = strom(ConsumptionRange.YEARS_ALL).points();

        assertThat(points).extracting(ConsumptionPoint::label).containsExactly("2025", "2026");
        assertThat(points.get(0).periodStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(points.get(0).consumption()).isEqualByComparingTo("600");
        assertThat(points.get(0).estimated()).isTrue();
        assertThat(points.get(1).consumption()).isEqualByComparingTo("300");
    }

    /** Wie am Monatswechsel: die Woche zaehlt zum Jahr ihres Ablesedatums. */
    @Test
    void ordnetEineWocheUeberDemJahreswechselDemJahrDesAblesedatumsZu() {
        stromAblesungen(
                reading(LocalDate.of(2025, 12, 29), "1000", false),
                reading(LocalDate.of(2026, 1, 2), "1040", false));

        List<ConsumptionPoint> points = strom(ConsumptionRange.YEARS_ALL).points();

        assertThat(points).extracting(ConsumptionPoint::label).containsExactly("2026");
        assertThat(points.get(0).consumption()).isEqualByComparingTo("40");
    }

    /** Eine Teilsumme saehe aus wie ein billiges Jahr. */
    @Test
    void laesstJahreskostenWegWennEinerWocheDerPreisFehlt() {
        stromAblesungen(
                reading(LocalDate.of(2025, 3, 7), "1000", false),
                reading(LocalDate.of(2025, 3, 14), "1010", false),
                reading(LocalDate.of(2025, 3, 21), "1020", false));
        when(priceBook.costOf(new BigDecimal("10"), LocalDate.of(2025, 3, 14)))
                .thenReturn(Optional.of(new BigDecimal("3.00")));

        assertThat(strom(ConsumptionRange.YEARS_ALL).points().get(0).cost()).isNull();
    }

    @Test
    void summiertJahreskostenWennAlleWochenBepreistSind() {
        stromAblesungen(
                reading(LocalDate.of(2025, 3, 7), "1000", false),
                reading(LocalDate.of(2025, 3, 14), "1010", false),
                reading(LocalDate.of(2025, 3, 21), "1020", false));
        when(priceBook.costOf(new BigDecimal("10"), LocalDate.of(2025, 3, 14)))
                .thenReturn(Optional.of(new BigDecimal("3.005")));
        when(priceBook.costOf(new BigDecimal("10"), LocalDate.of(2025, 3, 21)))
                .thenReturn(Optional.of(new BigDecimal("3.005")));

        assertThat(strom(ConsumptionRange.YEARS_ALL).points().get(0).cost()).isEqualByComparingTo("6.01");
    }

    @Test
    void liefertBeiMonthsAllAuchWeitZurueckliegendeMonate() {
        stromAblesungen(
                reading(LocalDate.of(2019, 1, 4), "100", false),
                reading(LocalDate.of(2019, 1, 11), "110", false),
                reading(LocalDate.of(2026, 8, 21), "5000", false));

        assertThat(strom(ConsumptionRange.MONTHS_ALL).points())
                .extracting(ConsumptionPoint::periodStart)
                .containsExactly(LocalDate.of(2019, 1, 1), LocalDate.of(2026, 8, 1));
    }
```

In `MeterReadingSeriesControllerTest`:

```java
    @Test
    void akzeptiertDenJahreszeitraum() throws Exception {
        stubSeries();

        mockMvc.perform(get("/v1/meter-readings/series?range=YEARS_ALL"))
                .andExpect(status().isOk());

        verify(meterConsumptionSeriesService).getSeries(ConsumptionRange.YEARS_ALL);
    }
```

- [ ] **Step 2:** `mvn test -Dtest=MeterConsumptionSeriesServiceTest,MeterReadingSeriesControllerTest` → Jahres-Tests FAIL (Label/`periodStart` stimmen nicht, weil YEAR wie MONTH behandelt wird).

- [ ] **Step 3: Implementierung** in `MeterConsumptionSeriesService`:

```java
    private static String periodKey(LocalDate date, ConsumptionResolution resolution) {
        return switch (resolution) {
            case WEEK -> date.get(WeekFields.ISO.weekBasedYear()) + "-" + date.get(WeekFields.ISO.weekOfWeekBasedYear());
            case MONTH -> date.getYear() + "-" + date.getMonthValue();
            case YEAR -> String.valueOf(date.getYear());
        };
    }

    private static ConsumptionPoint mergeGroup(List<ConsumptionPoint> group, ConsumptionResolution resolution) {
        ...
        return switch (resolution) {
            case WEEK -> new ConsumptionPoint(first.periodStart(), first.label(), consumption, estimated, cost);
            case MONTH -> {
                LocalDate periodStart = first.periodStart().withDayOfMonth(1);
                yield new ConsumptionPoint(periodStart, MONTH_LABEL.format(periodStart), consumption, estimated, cost);
            }
            case YEAR -> {
                LocalDate periodStart = first.periodStart().withDayOfYear(1);
                yield new ConsumptionPoint(periodStart, String.valueOf(periodStart.getYear()),
                        consumption, estimated, cost);
            }
        };
    }
```

Klassen-Javadoc um „Jahresbalken wie Monatsbalken" ergänzen.

- [ ] **Step 4:** Tests → PASS
- [ ] **Step 5:** Commit `feat(consumption): Jahresbalken im Serien-Service`

### Task 3: Frontend – Modell und Util (Jahr, laufende Periode)

**Files:** `meter-consumption-series.model.ts`, `consumption-view.util.ts`, `consumption-view.util.spec.ts`

- [ ] **Step 1: Failing tests** (in `consumption-view.util.spec.ts`):

```ts
    it('bietet bei Jahr genau "Alle Jahre" als Default', () => {
      expect(RANGE_OPTIONS.YEAR.map(o => o.value)).toEqual(['YEARS_ALL']);
      expect(defaultRangeFor('YEAR')).toBe('YEARS_ALL');
    });

  // in describe('compareToPrevious')
    it('vergleicht bei Jahren nie - ein angebrochenes Jahr gegen ein volles waere falsch', () => {
      expect(compareToPrevious([point(100, '2025-01-01'), point(40, '2026-01-01')], 'YEAR')).toBeNull();
    });

  describe('isRunningPeriod', () => {
    const today = new Date(2026, 8, 24);
    it('erkennt das laufende Jahr', () => {
      expect(isRunningPeriod('2026-01-01', 'YEAR', today)).toBeTrue();
      expect(isRunningPeriod('2025-01-01', 'YEAR', today)).toBeFalse();
    });
    it('erkennt den laufenden Monat', () => {
      expect(isRunningPeriod('2026-09-01', 'MONTH', today)).toBeTrue();
      expect(isRunningPeriod('2025-09-01', 'MONTH', today)).toBeFalse();
    });
  });
```

- [ ] **Step 2:** Test → FAIL.
- [ ] **Step 3: Implementierung**
  - Modell: `ConsumptionResolution = 'WEEK' | 'MONTH' | 'YEAR'`; `ConsumptionRange` um `'YEARS_ALL' | 'MONTHS_ALL'`.
  - Util: `RANGE_OPTIONS.YEAR = [{ value: 'YEARS_ALL', label: 'Alle Jahre' }]`, `DEFAULT_RANGE.YEAR = 'YEARS_ALL'`, `PREVIOUS_LABEL.YEAR = 'Vorjahr'` (nur für die Typvollständigkeit), `compareToPrevious` gibt bei `resolution === 'YEAR'` sofort `null` zurück.
  - `isRunningPeriod(periodStart, resolution: 'MONTH' | 'YEAR', today = new Date())` parst `YYYY-MM-DD` per Split (keine `Date`-Zonenfalle) und vergleicht Jahr bzw. Jahr+Monat.
- [ ] **Step 4:** PASS. **Step 5:** Commit.

### Task 4: Frontend – `buildTotalsTable`

- [ ] **Step 1: Failing tests:** zwei Jahresreihen (Strom 2025/2026, Wasser nur 2026) + Monatsreihen:
  - Spalten = Typen der Jahresreihen in Antwortreihenfolge, mit deutschem Namen
  - Jahreszeilen absteigend, Monate darunter aufsteigend, Monatsname lang („März")
  - Zelle ohne Wert für Wasser 2025 ist `null`
  - Zelle trägt `consumption`-Text, `cost`-Text (`'–'` bei `null`) und `estimated`
  - `running` für 2026 und September 2026 bei `today = 2026-09-24`
  - leere Eingabe → `rows: []`
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: Implementierung** – Typen `TotalsTable { columns: TotalsColumn[]; rows: TotalsYearRow[] }`, `TotalsColumn { meterType; name }`, `TotalsYearRow { year; label; running; cells: (TotalsCell|null)[]; months: TotalsMonthRow[] }`, `TotalsMonthRow { key; label; running; cells }`, `TotalsCell { consumption: string; cost: string; estimated: boolean }`. Gruppierung über `periodStart.slice(0, 4)` bzw. `slice(0, 7)`; Zellen je Spalte über `Map<MeterType, Map<string, ConsumptionPoint>>`.
- [ ] **Step 4:** PASS. **Step 5:** Commit.

### Task 5: Frontend – Jahr im Diagramm

- [ ] **Step 1: Failing tests** (Komponente): `setResolution('YEAR')` lädt `YEARS_ALL`, `ranges` hat einen Eintrag; Kachel mit Punkt im laufenden Jahr hat `currentSuffix === 'bis heute'` und `comparison === null`; bei Woche ist `currentSuffix` `null`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** `resolutions` um `{ value: 'YEAR', label: 'Jahr' }`; `ConsumptionTile.currentSuffix: string | null`, gesetzt, wenn `resolution === 'YEAR'` und `isRunningPeriod(last.periodStart, 'YEAR')`; Template zeigt ihn unter dem Kopfwert.
- [ ] **Step 4:** PASS. **Step 5:** Commit.

### Task 6: Frontend – Tabellenmodus

- [ ] **Step 1: Failing tests:**
  - `setViewMode('table')` ruft `getSeries('YEARS_ALL')` und `getSeries('MONTHS_ALL')`, `totals` gefüllt
  - Auflösungs-/Zeitraumknöpfe fehlen im DOM, Tabelle da, Kachelraster weg
  - jüngstes Jahr aufgeklappt (Monatszeilen sichtbar), älteres zu; Klick auf den Jahresknopf klappt um
  - Aufklappzustand überlebt `reload()`
  - `reload()` im Tabellenmodus lädt die Tabellenreihen, nicht den Diagrammzeitraum
  - Moduswechsel bestellt laufenden Abruf ab (Subject-Test)
  - Fehler beim Erstabruf → Meldung; Fehler beim Refresh → Tabelle bleibt
  - Höhe: bei 900 px Rahmen und vielen Zeilen bleibt der Host ≤ 900 px, `.tablet-consumption__table-wrap` scrollt (`scrollHeight > clientHeight`)
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** `viewMode: 'chart' | 'table'`, `viewModes`, `totals: TotalsTable | null`, `expandedYears: Set<number>`, `setViewMode`, `toggleYear`, `isYearExpanded`, `loadTotals(silent)` via `forkJoin` über dasselbe `pendingRequest`; `reload()` verzweigt nach Modus. Template: `@if (viewMode === 'chart')` um die beiden Knopfgruppen, Tabellenblock mit `ng-template` für die Zelle. SCSS: `__table-wrap { flex: 1 1 auto; min-height: 0; overflow-y: auto }`, `thead th { position: sticky; top: 0; background: #0b0b0c }`, Jahreszeile als Knopf ≥ 44 px.
- [ ] **Step 4:** komplette Frontend-Suite → nur Baseline-Fails. **Step 5:** Commit.

### Task 7: Doku und Abschluss

- [ ] CLAUDE.md, Abschnitt Tablet-Ansichten/Verbrauch: Jahres-Auflösung, Tabellenmodus, `…_ALL` ohne Fensterbeginn, kein Vergleich bei Jahr.
- [ ] Backend `mvn test` für die berührten Klassen, Frontend komplette Suite, `ng build`.
- [ ] Commit, dann superpowers:finishing-a-development-branch.
