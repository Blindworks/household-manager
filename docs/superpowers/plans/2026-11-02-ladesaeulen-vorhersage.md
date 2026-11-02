# Ladesäulen-Vorhersage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Für Ladesäulen-Favoriten jede Belegung je Ladepunkt aufzeichnen und daraus „frei bei Ankunft“, Restdauer und ein Wochenmuster vorhersagen — angezeigt auf `/tablet/charging`, abfragbar per Telegram, als Attribute an `sensor.charging_<id>_free`.

**Architecture:** Der minütliche Favoriten-Poll schreibt zusätzlich zwei Tabellen: abgeschlossene Belegungen (`charging_occupancy_history`) und beobachtete Ladepunkt-Sekunden je Station und Stunde (`charging_observation`). Ein Statistik-Service liest daraus stündlich gecachte Muster; ein Vorhersage-Service rechnet je Poll die aktuelle Prognose, hält sie im Speicher und liefert sie an Entität, REST-API, Tablet-Liste und Telegram-Tool. Alles neu Hinzugefügte wirft nie in den Poll hinein.

**Tech Stack:** Spring Boot 3.4 / Java 21, Liquibase (MariaDB), JUnit 5 + Mockito + AssertJ; Angular 19 standalone, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-11-02-ladesaeulen-vorhersage-design.md`

## Global Constraints

- Neues Java unter `backend/src/main/java/com/household/manager/charging/forecast/`; Entities in `model/entity/`, Repositories **zwingend** in `com.household.manager.repository` (JpaConfig scannt nur dort).
- Je DDL **ein** Liquibase-Changeset (MariaDB-DDL-Autocommit), Dateien `20261102-0080…`, eingetragen in `db/changelog/db.changelog-master.xml` unter einem Kommentar `<!-- Ladesäulen-Vorhersage -->`.
- Alle Zeitrechnung über das `Clock`-Bean (Europe/Berlin), nie `ZoneId.systemDefault()`.
- Aufbewahrung **365 Tage** für beide Tabellen; Bulk-Delete als `@Transactional @Modifying @Query`.
- Feiertage **Hessen** zählen als Sonntag; die Regel steht nur in `HessenHolidays`.
- Schwellen als Konstanten an einer Stelle (`ChargingForecastSettings`): Mindest-Beobachtung **14 Tage**, Musterfenster **8 Wochen**, Mindest-Vergleichsmenge **10 Belegungen**, Beginnfenster **±2 h**, Lückengrenze **10 min**, Cache **1 h**, Quantile **0,5 / 0,8**, Horizonte **15 / 30 / 60 min**.
- Restdauer immer als Median **und** 80-%-Quantil; Wahrscheinlichkeiten ohne Abschlag, ganze Prozent.
- Ladepunkte `OUT_OF_SERVICE`/`UNKNOWN` tragen nie zu „frei“ bei und zählen in keinem Nenner.
- Entität: Attribute nur bei Status `ok`, sonst fehlen die Schlüssel (nie `null`); Zeitformat `yyyy-MM-dd'T'HH:mm:ss`; kein `deviceClass`.
- Keine Security-Zeile neu: Lesen läuft über die generische `GET /v1/**` → KIOSK.
- Frontend-Farben nur über lumina-Tokens; Kartencontainer bleibt immer im DOM.
- Backend-Tests: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"` und aus `backend/` `mvn -q test -Dtest=<Klasse>`. `contextLoads`/`HealthControllerTest` sind lokal vorbestehend rot (keine DB).
- Frontend-Tests: aus `frontend/` `npm test -- --watch=false --browsers=ChromeHeadless --include=<pfad>`; Baseline im Gesamtlauf: 3 vorbestehende Fails (App/Hero).
- Der Working Tree enthält fremde, uncommittete Änderungen (`AuditEntryDescriber`, `ManualEntityService`, `SwitchCommandService` samt Tests, `CLAUDE.md`). Nur eigene Dateien `git add`en, nie `git add -A`. In einem Worktree arbeiten (siehe `superpowers:using-git-worktrees`).

## Review Focus

1. **Lücke über einen Backend-Neustart:** Belegung lief vor dem Neustart, Ende danach ohne `statusSince` → Zeile muss verworfen werden, nie „9 h belegt“. Test in Task 3.
2. **Mitternacht im Beginnfenster:** laufende Belegung begann 23:30, Vergleichsbelegungen um 00:40 liegen im ±2-h-Fenster. Test in Task 5.
3. **Station mit lauter defekten Punkten:** alle `OUT_OF_SERVICE` → Wahrscheinlichkeit 0 %, keine Restdauer, kein Absturz. Test in Task 6.
4. **Favorit entfernt und später wieder favorisiert:** Beobachtungstage summieren über die Pause, die Pause erscheint in der Heatmap als „keine Daten“ (`null`), nicht 0. Test in Task 5.
5. **Vorhersage wirft im Poll:** Exception im Vorhersage-Service → Entität wird trotzdem wie heute gemeldet (ohne Prognose-Attribute). Test in Task 7.

---

## File Structure

| Datei | Verantwortung |
|---|---|
| `db/changelog/changes/20261102-0080-create-charging-occupancy-history.xml` | Tabelle `charging_occupancy_history` |
| `…/20261102-0081-create-charging-occupancy-history-index.xml` | Index `(station_id, started_at)` |
| `…/20261102-0082-create-charging-observation.xml` | Tabelle `charging_observation` inkl. Unique `(station_id, hour_start)` |
| `model/entity/ChargingOccupancyRecord.java`, `model/entity/ChargingObservation.java` | JPA-Entities |
| `repository/ChargingOccupancyHistoryRepository.java`, `repository/ChargingObservationRepository.java` | Abfragen + Bulk-Deletes |
| `charging/forecast/ChargingForecastSettings.java` | alle Schwellen als Konstanten |
| `charging/forecast/HessenHolidays.java` | Feiertage, Tagestyp, Heatmap-Wochentag |
| `charging/forecast/ChargingHistoryRecorder.java` | schreibt beide Tabellen, wirft nie |
| `charging/ChargingOccupancyTracker.java` (ändern) | meldet beendete Belegungen an den Recorder, Lückenregel |
| `charging/forecast/ChargingHistoryRetentionJob.java` | nächtliches Löschen |
| `charging/forecast/StationPattern.java`, `ChargingPatternService.java` | stündlich gecachte Statistik je Station |
| `charging/forecast/ChargingForecast.java`, `ChargingForecastService.java` | Prognose je Poll, Speicher-Stand |
| `entitystate/mapper/ChargingEntityMapper.java` (ändern), `charging/ChargingPollingService.java` (ändern) | Attribute + Einhängen in den Poll |
| `charging/ChargingDtos.java`, `ChargingQueryService.java`, `ChargingController.java` (ändern) | API |
| `telegram/tools/GetChargingForecastTool.java` | Telegram-Werkzeug |
| `frontend/src/app/models/charging.model.ts`, `services/charging.service.ts` (ändern) | Vertrag |
| `frontend/src/app/shared/charging-forecast.util.ts` | Formatierung, Heatmap-Stufen |
| `frontend/src/app/pages/tablet-charging/*` (ändern) | Prognosezeile + Detailbereich |
| `CLAUDE.md` (ändern) | Abschnitt unter „Ladesäulen-Übersicht“ |

---

### Task 1: Schema, Entities, Repositories

**Files:**
- Create: die drei Changesets, beide Entities, beide Repositories
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml`
- Test: `backend/src/test/java/com/household/manager/charging/forecast/ChargingHistorySchemaTest.java`

**Interfaces:**
- Produces:
  - Tabelle `charging_occupancy_history`: `id BIGINT AUTO_INCREMENT PK`, `station_id VARCHAR(191) NOT NULL`, `chargepoint_id VARCHAR(191) NOT NULL`, `started_at DATETIME NOT NULL`, `ended_at DATETIME NOT NULL`.
  - Tabelle `charging_observation`: `id BIGINT AUTO_INCREMENT PK`, `station_id VARCHAR(191) NOT NULL`, `hour_start DATETIME NOT NULL`, `observed_seconds INT NOT NULL DEFAULT 0`, `free_point_seconds BIGINT NOT NULL DEFAULT 0`, `occupied_point_seconds BIGINT NOT NULL DEFAULT 0`; Unique `uk_charging_observation_station_hour (station_id, hour_start)`.
  - `ChargingOccupancyRecord` (Lombok `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`, Felder `Long id, String stationId, String chargePointId, Instant startedAt, Instant endedAt`).
  - `ChargingObservation` (Felder `Long id, String stationId, Instant hourStart, int observedSeconds, long freePointSeconds, long occupiedPointSeconds`).
  - `ChargingOccupancyHistoryRepository extends JpaRepository<ChargingOccupancyRecord, Long>`:
    - `List<ChargingOccupancyRecord> findByStationIdAndStartedAtGreaterThanEqual(String stationId, Instant from)`
    - `@Transactional @Modifying @Query int deleteEndedBefore(Instant cutoff)`
  - `ChargingObservationRepository extends JpaRepository<ChargingObservation, Long>`:
    - `Optional<ChargingObservation> findByStationIdAndHourStart(String stationId, Instant hourStart)`
    - `List<ChargingObservation> findByStationIdAndHourStartGreaterThanEqual(String stationId, Instant from)`
    - `@Query("select coalesce(sum(o.observedSeconds),0) from ChargingObservation o where o.stationId = :stationId") long sumObservedSeconds(String stationId)`
    - `@Transactional @Modifying @Query int deleteHoursBefore(Instant cutoff)`

- [ ] **Step 1: Changesets schreiben** (Muster `20261030-0075-…`, je ein `changeSet` mit Autor `benedikt`), Master-Einträge ergänzen.
- [ ] **Step 2: Test schreiben** — `ChargingHistorySchemaTest` liest die drei XML-Dateien als Text und prüft: jede Datei enthält genau ein `<changeSet`; `0082` enthält `uk_charging_observation_station_hour`; der Master inkludiert alle drei in der Reihenfolge 0080, 0081, 0082.
- [ ] **Step 3: Entities und Repositories anlegen.**
- [ ] **Step 4: Kompilieren und Test laufen lassen** — `mvn -q test -Dtest=ChargingHistorySchemaTest` → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Schema für Belegungshistorie und Beobachtungszeit`

---

### Task 2: Einstellungen und Feiertage Hessen

**Files:**
- Create: `charging/forecast/ChargingForecastSettings.java`, `charging/forecast/HessenHolidays.java`
- Test: `charging/forecast/HessenHolidaysTest.java`

**Interfaces:**
- Produces:
  - `final class ChargingForecastSettings` mit `public static final`: `Duration MIN_OBSERVED = Duration.ofDays(14)`, `Duration PATTERN_WINDOW = Duration.ofDays(56)`, `int MIN_SAMPLES = 10`, `Duration START_WINDOW = Duration.ofHours(2)`, `Duration MAX_GAP = Duration.ofMinutes(10)`, `Duration CACHE_TTL = Duration.ofHours(1)`, `double MEDIAN = 0.5`, `double LATEST = 0.8`, `List<Integer> HORIZONS_MINUTES = List.of(15, 30, 60)`, `Duration RETENTION = Duration.ofDays(365)`.
  - `enum ForecastDayType { WEEKDAY, SATURDAY, SUNDAY }` (in `HessenHolidays.java` als nested public enum oder eigene Datei — eigene Datei `ForecastDayType.java`).
  - `final class HessenHolidays`: `static boolean isHoliday(LocalDate date)`, `static ForecastDayType dayType(LocalDate date)` (Feiertag ⇒ `SUNDAY`), `static int heatmapDay(LocalDate date)` (0 = Montag … 6 = Sonntag, Feiertag ⇒ 6).

- [ ] **Step 1: Failing Test schreiben**

```java
@ParameterizedTest
@CsvSource({"2026-04-03", "2026-04-06", "2026-05-14", "2026-05-25", "2026-06-04",
            "2027-03-26", "2027-03-29", "2027-05-06", "2027-05-17", "2027-05-27",
            "2026-01-01", "2026-05-01", "2026-10-03", "2026-12-25", "2026-12-26"})
void hessischeFeiertage(LocalDate date) { assertThat(HessenHolidays.isHoliday(date)).isTrue(); }

@Test void keinFeiertag() {
    assertThat(HessenHolidays.isHoliday(LocalDate.of(2026, 11, 1))).isFalse();  // Allerheiligen: nicht in Hessen
    assertThat(HessenHolidays.isHoliday(LocalDate.of(2026, 1, 6))).isFalse();   // Hl. Drei Könige: nicht in Hessen
}
@Test void feiertagZaehltAlsSonntag() {
    assertThat(HessenHolidays.dayType(LocalDate.of(2026, 5, 14))).isEqualTo(ForecastDayType.SUNDAY); // Do, Himmelfahrt
    assertThat(HessenHolidays.heatmapDay(LocalDate.of(2026, 5, 14))).isEqualTo(6);
    assertThat(HessenHolidays.dayType(LocalDate.of(2026, 11, 7))).isEqualTo(ForecastDayType.SATURDAY);
    assertThat(HessenHolidays.heatmapDay(LocalDate.of(2026, 11, 2))).isZero();
}
```

- [ ] **Step 2: Test laufen lassen** → FAIL (Klasse fehlt).
- [ ] **Step 3: Implementieren.** Osterdatum per Gauß/Anonymer Gregorianischer Algorithmus; beweglich: Karfreitag −2, Ostermontag +1, Himmelfahrt +39, Pfingstmontag +50, Fronleichnam +60; fest: 1.1., 1.5., 3.10., 25.12., 26.12. Keine Bibliothek.
- [ ] **Step 4: Test laufen lassen** → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Feiertage Hessen und Vorhersage-Schwellen`

---

### Task 3: Aufzeichnung (Recorder, Tracker, Retention)

**Files:**
- Create: `charging/forecast/ChargingHistoryRecorder.java`, `charging/forecast/ChargingHistoryRetentionJob.java`
- Modify: `charging/ChargingOccupancyTracker.java`
- Test: `charging/forecast/ChargingHistoryRecorderTest.java`, `charging/ChargingOccupancyTrackerTest.java` (erweitern), `charging/forecast/ChargingHistoryRetentionJobTest.java`

**Interfaces:**
- Consumes: Task 1 Repositories, Task 2 `ChargingForecastSettings`.
- Produces:
  - `@Service ChargingHistoryRecorder`:
    - `void recordEnded(String stationId, String chargePointId, Instant startedAt, Instant endedAt)` — speichert nur bei `endedAt.isAfter(startedAt)`; wirft nie (loggt `warn`).
    - `void recordObservation(String stationId, Instant polledAt, int observedSeconds, int freePoints, int occupiedPoints)` — Upsert in den Stunden-Bucket `polledAt` auf die volle Stunde abgeschnitten (`truncatedTo(HOURS)` auf dem Instant; Buckets sind UTC-Stunden, die Statistik ordnet sie per `Clock`-Zone zu); addiert `observedSeconds`, `freePoints*observedSeconds`, `occupiedPoints*observedSeconds`; wirft nie.
  - `ChargingOccupancyTracker.record(ChargingStationDetails)` bleibt die Signatur; neu im Konstruktor `ChargingHistoryRecorder recorder`; neue In-Memory-Map `Map<String, Instant> lastRecordedAt` (Station → letzter `record`-Aufruf).
  - `@Component ChargingHistoryRetentionJob` mit `@Scheduled(cron = "0 50 3 * * *") void purge()` — löscht in beiden Tabellen älter als `now − RETENTION`; wirft nie.

**Regel im Tracker** beim Übergang einer bestehenden Zeile (`row != null`) nach `FREE`:
- `row.occupiedSince == null` ⇒ nichts aufzeichnen.
- `point.statusSince() != null && point.statusSince().isAfter(row.occupiedSince)` ⇒ `recordEnded(…, occupiedSince, statusSince)`.
- sonst: liegt `lastRecordedAt(station)` vor und `now − lastRecordedAt ≤ MAX_GAP` ⇒ `recordEnded(…, occupiedSince, now)`; sonst verwerfen (Neustart oder Lücke).
- Übergang nach `OUT_OF_SERVICE` ⇒ nie aufzeichnen.
- `lastRecordedAt` wird am **Ende** von `record` auf `now` gesetzt.

- [ ] **Step 1: Failing Tests schreiben** (Tracker, Mockito-Recorder, feste `Clock`):
  - `endeMitStatusSinceWirdAufgezeichnet`: Zeile seit 10:00, Poll 10:41 meldet FREE mit `statusSince` 10:40 ⇒ `verify(recorder).recordEnded("S1","P1", 10:00, 10:40)`.
  - `endeOhneStatusSinceInnerhalbDerLueckeNimmtPollzeit`: vorheriger `record` 10:40, jetzt 10:41 FREE ohne `statusSince` ⇒ `recordEnded(…, 10:00, 10:41)`.
  - `nachNeustartOhneStatusSinceWirdVerworfen` (Review Focus 1): frischer Tracker, Zeile existiert in DB seit 01:00, 10:41 FREE ohne `statusSince` ⇒ `verify(recorder, never()).recordEnded(any(), any(), any(), any())`.
  - `lueckeUeberZehnMinutenWirdVerworfen`: vorheriger `record` 10:20, jetzt 10:41 ⇒ never.
  - `ausserBetriebWirdNieAufgezeichnet`.
  - `unbekannterBeginnWirdNieAufgezeichnet`.
  Recorder: `recordObservationAddiertInDenStundenBucket` (zwei Aufrufe 10:05 und 10:06 mit 60 s, 1 frei, 1 belegt ⇒ eine Zeile, `observedSeconds 120`, `freePointSeconds 120`, `occupiedPointSeconds 120`); `recordEndedVerwirftNichtPositiveDauer`; `recorderWirftNieBeiRepositoryFehler`.
  Retention: `purgeLoeschtAelterAls365Tage` (verify beide Deletes mit `now − 365 d`).
- [ ] **Step 2: Tests laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren** (bestehende Tracker-Tests bekommen den Recorder als Mock).
- [ ] **Step 4: `mvn -q test -Dtest='ChargingOccupancyTrackerTest,ChargingHistoryRecorderTest,ChargingHistoryRetentionJobTest'`** → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Belegungshistorie und Beobachtungszeit aufzeichnen`

---

### Task 4: Beobachtung im Poll

**Files:**
- Modify: `charging/ChargingPollingService.java`
- Test: `charging/ChargingPollingServiceTest.java` (erweitern)

**Interfaces:**
- Consumes: `ChargingHistoryRecorder.recordObservation` (Task 3).
- Produces: nach erfolgreichem `source.stationDetails` und `tracker.record` je Favorit genau ein `recordObservation(stationId, clock.instant(), properties.getFavoritePollSeconds(), freeCount, occupiedCount)`. `freeCount`/`occupiedCount` zählen nur `FREE` bzw. `OCCUPIED`. Hat `ChargingProperties` noch kein `favoritePollSeconds`-Feld, wird es dort ergänzt (Property `charging.favorite-poll-seconds`, Default 60). Bei Fehlschlag des Abrufs **kein** Aufruf.

- [ ] **Step 1: Failing Tests:** `beobachtungWirdJeErfolgreichemFavoritenGemeldet` (2 Punkte FREE, 1 OCCUPIED, 1 OUT_OF_SERVICE ⇒ `recordObservation("S1", now, 60, 2, 1)`); `keineBeobachtungBeiAbruffehler`.
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `mvn -q test -Dtest=ChargingPollingServiceTest`** → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Beobachtungszeit im Favoriten-Poll erfassen`

---

### Task 5: Muster-Statistik (gecacht)

**Files:**
- Create: `charging/forecast/StationPattern.java`, `charging/forecast/ChargingPatternService.java`
- Test: `charging/forecast/ChargingPatternServiceTest.java`

**Interfaces:**
- Consumes: Task 1 Repositories, Task 2.
- Produces:
  - `record StationPattern(Duration observed, Double[][] occupancy, double[][] startsPerFreePointSecond, double overallStartRate, List<PastOccupancy> occupancies)` mit `occupancy[7][24]` (Anteil 0–1 oder `null` = nicht beobachtet), `startsPerFreePointSecond[7][24]` (0 wenn nicht beobachtet; `overallStartRate` ist der Fallback), und `record PastOccupancy(ForecastDayType dayType, LocalTime start, Duration duration)`.
  - `@Service ChargingPatternService`: `StationPattern pattern(String stationId)` — Cache je Station, neu geladen nach `CACHE_TTL`; `void evict(String stationId)`.
  - Rechnung: `observed` = `sumObservedSeconds` (gesamt, nicht nur Fenster). Heatmap und Startraten aus Beobachtungen und Historie der letzten `PATTERN_WINDOW`: Buckets per `hourStart.atZone(clock.getZone())` → `heatmapDay`, Stunde; `occupancy = Σoccupied / (Σfree + Σoccupied)` je Zelle, `null` bei Nenner 0. Startraten = Anzahl `startedAt` in der Zelle / Σ`freePointSeconds` der Zelle. `occupancies` aus der Historie mit Tagestyp und lokaler Startzeit.

- [ ] **Step 1: Failing Tests:**
  - `heatmapAusBeobachtungen`: Montag 2026-10-26 18:00 Berlin, 3600 s beobachtet, free 3600, occupied 3600 ⇒ `occupancy[0][18] == 0.5`; `occupancy[0][19]` `null`.
  - `pauseIstKeineNull` (Review Focus 4): Beobachtungen nur in KW 40 und KW 44 ⇒ Zellen ohne Beobachtung sind `null`; `observed` = Summe beider Wochen.
  - `feiertagLandetImSonntag`: Beobachtung am 2026-05-14 ⇒ Zeile 6.
  - `cacheWirdNachEinerStundeErneuert`: zwei Aufrufe im Abstand 30 min ⇒ Repository einmal; nach 61 min ⇒ zweimal.
  - `startrateJeFreierPunktSekunde`: 2 Beginne, 7200 freie Punkt-Sekunden in der Zelle ⇒ `2/7200`.
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `mvn -q test -Dtest=ChargingPatternServiceTest`** → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Belegungsmuster je Favorit`

---

### Task 6: Vorhersage

**Files:**
- Create: `charging/forecast/ChargingForecast.java`, `charging/forecast/ChargingForecastService.java`
- Test: `charging/forecast/ChargingForecastServiceTest.java`

**Interfaces:**
- Consumes: `ChargingPatternService.pattern` (Task 5), `ChargingStationDetails`, `ChargingPointOccupancy`.
- Produces:
  - `record ChargingForecast(Status status, long observedDays, Map<Integer, Integer> freeProbabilityPercent, List<PointForecast> points, Instant expectedFreeAt, Instant expectedFreeLatest, Double[][] heatmap)` mit `enum Status { OK, INSUFFICIENT_DATA }` und `record PointForecast(String chargePointId, Instant occupiedSince, Instant expectedFreeAt, Instant expectedFreeLatest)`. Bei `INSUFFICIENT_DATA` sind Wahrscheinlichkeiten/Punkte leer und Zeiten `null`; `heatmap` ist trotzdem gesetzt.
  - `@Service ChargingForecastService`:
    - `Optional<ChargingForecast> update(String stationId, ChargingStationDetails details, Map<String, ChargingPointOccupancy> occupancy)` — rechnet, legt das Ergebnis in eine `ConcurrentHashMap` und gibt es zurück; wirft nie (`Optional.empty()` + `warn`).
    - `Optional<ChargingForecast> latest(String stationId)`
    - `void forget(String stationId)`

**Algorithmus** (Zeitpunkt `now = clock.instant()`):
- `observedDays = pattern.observed().toDays()`; `< MIN_OBSERVED` ⇒ `INSUFFICIENT_DATA`.
- Je `OCCUPIED`-Punkt: Beginn = `occupiedSince`, sonst `firstSeenOccupiedAt`. `elapsed = now − Beginn`. Vergleichsmenge aus `occupancies` mit `duration ≥ elapsed`, Stufe 1: gleicher `dayType` **und** `|start − Beginn lokal| ≤ START_WINDOW` (zirkulär über Mitternacht, Distanz `min(d, 24h − d)`); Stufe 2: nur Zeitfenster; Stufe 3: alle. Erste Stufe mit `≥ MIN_SAMPLES` gewinnt; keine ⇒ keine Restdauer für diesen Punkt, und seine Frei-Wahrscheinlichkeit ist 0.
  - `expectedFreeAt = Beginn + Quantil(0,5)`, `expectedFreeLatest = Beginn + Quantil(0,8)` (Quantil: Nearest-Rank auf sortierten Dauern); liegt das Ergebnis vor `now`, gilt `now`.
  - `P(frei bis now+h) = Anteil der Vergleichsmenge mit duration ≤ elapsed + h`.
- Je `FREE`-Punkt: Rate λ = `startsPerFreePointSecond[heatmapDay(heute)][Stunde jetzt]`, ist sie 0 und die Zelle unbeobachtet ⇒ `overallStartRate`; `P(bleibt frei) = exp(−λ · h_sekunden)`.
- `OUT_OF_SERVICE`/`UNKNOWN` ⇒ Wahrscheinlichkeit 0.
- `P(mind. einer frei) = 1 − Π(1 − p_i)`, `Math.round(·100)`. Kommentar: Unabhängigkeit der Punkte ist eine bewusste Vereinfachung.
- Stations-`expectedFreeAt`/`Latest` = frühester Wert über die belegten Punkte; `null`, sobald irgendein Punkt `FREE` ist.

- [ ] **Step 1: Failing Tests** (Pattern als Stub, feste `Clock` 2026-11-02 17:15 Berlin, Montag):
  - `zuWenigDaten`: observed 13 Tage ⇒ `INSUFFICIENT_DATA`, `observedDays 13`, Heatmap gesetzt.
  - `restdauerMedianUnd80Prozent`: belegt seit 17:00, 10 Vergleichsdauern 20,30,…,110 min (Werktag, Beginn 17:00) ⇒ `expectedFreeAt` 17:00 + 60 min, `expectedFreeLatest` 17:00 + 90 min (Nearest-Rank: Rang ⌈0,5·10⌉=5 → 60, ⌈0,8·10⌉=8 → 90).
  - `bedingtAufBisherigeDauer`: elapsed 40 min ⇒ Dauern < 40 werden nicht verwendet.
  - `lockerungBeiZuWenigVergleichen`: 4 passende im Zeitfenster, 12 insgesamt ⇒ Stufe 3 genutzt (Ergebnis aus allen 12).
  - `mitternachtImBeginnfenster` (Review Focus 2): Beginn 23:30, Vergleichsbeginne 00:40 zählen in Stufe 1.
  - `freierPunktNachPoisson`: λ = 1/3600 s ⇒ `P(30) = round(100·e^−0,5) = 61`.
  - `kombinationMindestensEiner`: p = 0,5 und 0,5 ⇒ 75.
  - `alleAusserBetrieb` (Review Focus 3): ⇒ alle Horizonte 0, keine Punkte, Zeiten `null`, kein Fehler.
  - `freierPunktLoeschtStationszeit`: ein FREE + ein OCCUPIED ⇒ `expectedFreeAt == null`.
  - `updateWirftNie`: Pattern-Service wirft ⇒ `Optional.empty()`, `latest` unverändert.
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `mvn -q test -Dtest=ChargingForecastServiceTest`** → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Vorhersage frei bei Ankunft und Restdauer`

---

### Task 7: Entität und Poll

**Files:**
- Modify: `entitystate/mapper/ChargingEntityMapper.java`, `charging/ChargingPollingService.java`, `charging/ChargingFavoriteService.java` (beim Entfernen `forecastService.forget` + `patternService.evict`)
- Test: `entitystate/mapper/ChargingEntityMapperTest.java` (anlegen oder erweitern), `charging/ChargingPollingServiceTest.java`

**Interfaces:**
- Consumes: `ChargingForecastService.update/forget` (Task 6), `ChargingPatternService.evict` (Task 5).
- Produces: `ChargingEntityMapper.map(ChargingFavorite, ChargingStationDetails, Map<String, ChargingPointOccupancy>, Optional<ChargingForecast>)`; die alte Dreier-Signatur entfällt (einziger Aufrufer ist der Poll). Attribute:
  - immer: `forecastStatus` = `"ok"` | `"insufficient_data"` (fehlt, wenn `Optional.empty()`),
  - nur bei `OK`: `freeProbability15min`, `freeProbability30min`, `freeProbability60min` (Integer), und falls vorhanden `expectedFreeAt`, `expectedFreeLatest` (Format `yyyy-MM-dd'T'HH:mm:ss` in Clock-Zone, wie `nextSunrise` in `SunEntityPublisher`), `minutesUntilFree`, `minutesUntilFreeLatest` (ganze Minuten ab `now`, ≥ 0). Der Mapper bekommt dafür das `Clock`-Bean.
- Poll: zwischen `tracker.record` und `mapper.map` `Optional<ChargingForecast> forecast = forecastService.update(stationId, stationDetails, occupancy)` — zusätzlich in eigenes `try/catch` gekapselt, Fehler ⇒ `Optional.empty()`.

- [ ] **Step 1: Failing Tests:**
  - Mapper `okSetztAlleAttribute` (Zeiten, Minuten 25/45, Prozente), `zuWenigDatenNurStatus` (`containsKey("freeProbability30min")` ist false), `ohneVorhersageKeinStatus`, `keinDeviceClass` bleibt.
  - Poll `vorhersageFehlerBrichtEntitaetNicht` (Review Focus 5): `forecastService.update` wirft `RuntimeException` ⇒ `entityStateService.reportState` wird trotzdem mit State = freie Punkte aufgerufen.
  - FavoriteService `entfernenVergisstVorhersage`.
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `mvn -q test -Dtest='ChargingEntityMapperTest,ChargingPollingServiceTest,ChargingFavoriteServiceTest'`** → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Vorhersage als Entitäts-Attribute`

---

### Task 8: REST-API

**Files:**
- Modify: `charging/ChargingDtos.java`, `charging/ChargingQueryService.java`, `charging/ChargingController.java`
- Test: `charging/ChargingQueryServiceTest.java`, `charging/ChargingForecastControllerTest.java` (WebMvc-Slice, Muster der übrigen Controller-Tests), `SecurityRulesTest` (eine Zeile)

**Interfaces:**
- Consumes: `ChargingForecastService.latest` (Task 6), `ChargingFavoriteService.list`.
- Produces:
  - `ChargingDtos.ForecastSummary(String status, Integer freeProbability30min, LocalDateTime expectedFreeAt, LocalDateTime expectedFreeLatest, Long observedDays)` (`@JsonInclude(NON_NULL)`).
  - `StationResponse` bekommt am Ende das Feld `ForecastSummary forecast` (nur Favoriten, sonst `null`).
  - `ChargingDtos.PointForecastResponse(String chargePointId, LocalDateTime occupiedSince, LocalDateTime expectedFreeAt, LocalDateTime expectedFreeLatest)`.
  - `ChargingDtos.ForecastResponse(String stationId, String status, long observedDays, Map<Integer,Integer> freeProbabilityPercent, List<PointForecastResponse> points, Double[][] heatmap)`.
  - `ChargingQueryService.forecast(String stationId): ChargingDtos.ForecastResponse` — 404 (`ResponseStatusException NOT_FOUND`) wenn kein Favorit; Favorit ohne bisherigen Stand ⇒ `status "INSUFFICIENT_DATA"`, `observedDays 0`, Heatmap 7×24 `null`.
  - `GET /v1/charging/stations/{stationId}/forecast`.
  - Zeitstempel LocalDateTime in Clock-Zone (wie der Rest der Antwort).

- [ ] **Step 1: Failing Tests:** QueryService `favoritBekommtZusammenfassung`, `nichtFavoritOhneZusammenfassung`, `forecastFuerUnbekanntenFavoriten404`, `forecastOhneStandZuWenigDaten`; Controller liefert JSON mit `heatmap` als 7 Arrays à 24; `SecurityRulesTest`: KIOSK darf `GET /v1/charging/stations/x/forecast` (200).
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `mvn -q test -Dtest='ChargingQueryServiceTest,ChargingForecastControllerTest,SecurityRulesTest'`** → PASS.
- [ ] **Step 5: Commit** — `feat(charging): Vorhersage-Endpunkt und Listen-Zusammenfassung`

---

### Task 9: Telegram-Werkzeug

**Files:**
- Create: `telegram/tools/GetChargingForecastTool.java`
- Test: `telegram/tools/GetChargingForecastToolTest.java`

**Interfaces:**
- Consumes: `ChargingFavoriteService.list`, `ChargingForecastService.latest`, `ChargingSnapshot.details` (aktueller Stand), `HessenHolidays`, `ChargingPatternService.pattern`.
- Produces: `AgentTool` mit `name() = "get_charging_forecast"`, Scope Default (`TELEGRAM`), Schema `{station: string (Pflicht, Name oder Teil des Favoritennamens), at: string (optional, "HH:mm")}`. Ausgabe als JSON-String (Muster `ListPowerConsumersTool`) mit Feldern `station`, `free`, `total`, `status`, `observedDays`, `freeProbabilityPercent`, `points` (Uhrzeiten `HH:mm`), optional `atHour` + `typicalOccupancyPercent`.
  - Auflösung: case-insensitive `contains` auf `displayName`; 0 Treffer oder > 1 ⇒ Ergebnis `{"error": "...", "favorites": [alle Namen]}` (kein Throw).
  - `at` ≤ 60 min von jetzt ⇒ Wahrscheinlichkeit des nächstgrößeren Horizonts; > 60 min ⇒ `typicalOccupancyPercent` aus `pattern.occupancy()[heatmapDay][Stunde]` (oder `null` = keine Daten).

- [ ] **Step 1: Failing Tests:** `findetFavoritPerTeilname` („rewe“ ⇒ „Rewe Hauptstraße“), `mehrdeutigListetFavoriten`, `zuWenigDatenWirdGemeldet`, `spaeteZeitNutztMuster` (`at` 20:00 bei jetzt 17:15 ⇒ `typicalOccupancyPercent` aus Zelle [0][20]).
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `mvn -q test -Dtest=GetChargingForecastToolTest`** → PASS.
- [ ] **Step 5: Commit** — `feat(telegram): Werkzeug für die Ladesäulen-Vorhersage`

---

### Task 10: Frontend-Vertrag und Formatierung

**Files:**
- Modify: `frontend/src/app/models/charging.model.ts`, `frontend/src/app/services/charging.service.ts`
- Create: `frontend/src/app/shared/charging-forecast.util.ts`, `…/charging-forecast.util.spec.ts`

**Interfaces:**
- Produces (TS):
  - `interface ChargingForecastSummary { status: 'OK' | 'INSUFFICIENT_DATA'; freeProbability30min?: number; expectedFreeAt?: string; expectedFreeLatest?: string; observedDays?: number }`; `ChargingStation.forecast?: ChargingForecastSummary`.
  - `interface ChargingPointForecast { chargePointId: string; occupiedSince?: string; expectedFreeAt?: string; expectedFreeLatest?: string }`
  - `interface ChargingForecast { stationId: string; status: 'OK' | 'INSUFFICIENT_DATA'; observedDays: number; freeProbabilityPercent: Record<string, number>; points: ChargingPointForecast[]; heatmap: (number | null)[][] }`
  - `ChargingService.getForecast(stationId: string): Observable<ChargingForecast>` → `GET /api/v1/charging/stations/{id}/forecast` (Basis-URL wie die übrigen Methoden).
  - `charging-forecast.util.ts`:
    - `forecastLine(summary: ChargingForecastSummary | undefined): string | null` — `'noch zu wenig Daten (X Tage)'` | `'frei ca. 17:40 · 72 % in 30 min'` | `'72 % in 30 min'` (kein `expectedFreeAt`) | `null` (kein Summary).
    - `freeWindow(at?: string, latest?: string): string | null` — `'frei ca. 17:40, meist bis 18:00'`.
    - `heatmapLevel(value: number | null): 'none' | 'low' | 'mid' | 'high' | 'full'` — Grenzen `< 0.2`, `< 0.4`, `< 0.6`, `< 0.8`, sonst `full`; `null` ⇒ `'none'`.
    - `HEATMAP_DAY_LABELS = ['Mo','Di','Mi','Do','Fr','Sa','So/Ft']`.

- [ ] **Step 1: Failing Spec** mit genau diesen Ausgaben (Zeitstrings `2026-11-02T17:40:00` ⇒ „17:40“).
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/shared/charging-forecast.util.spec.ts`** → PASS.
- [ ] **Step 5: Commit** — `feat(frontend): Vertrag und Formatierung der Ladesäulen-Vorhersage`

---

### Task 11: Tablet — Prognosezeile und Detailbereich

**Files:**
- Modify: `frontend/src/app/pages/tablet-charging/tablet-charging.component.{ts,html,scss,spec.ts}`

**Interfaces:**
- Consumes: Task 10.
- Produces (Komponente):
  - Feld `detailStationId: string | null`, `forecast: ChargingForecast | null`, `forecastError: string | null`.
  - `openDetail(stationId: string): void` — nur für Favoriten; aufgerufen beim Antippen einer **Listenzeile** eines Favoriten (zusätzlich zum bestehenden `select`). `select(…, { fromMap: true })` ruft es **nie** auf.
  - `closeDetail(): void` — setzt `detailStationId = null` und ruft danach `map.invalidateSize()` (per `setTimeout(0)`).
  - Der 60-s-`reload()` lädt bei offenem Detail auch `getForecast` neu; ein Fehler behält den letzten `forecast`.
- Markup: unter `tablet-charging__meta` bei Favoriten `<p class="tablet-charging__forecast">{{ forecastLine(station.forecast) }}</p>`. Der Detailbereich `<section class="tablet-charging__detail">` liegt im selben Grid-Bereich wie `#mapContainer` und wird per `@if (detailStationId)` gerendert; der Kartencontainer bleibt im DOM und bekommt nur `[class.tablet-charging__map--hidden]`. Inhalt: Name, Schließen-Knopf („Karte“), je belegtem Punkt `freeWindow(...)`, Zeile „Frei in 15 / 30 / 60 min: …“, Heatmap als CSS-Grid 7 × 24 (Zellen `data-level` aus `heatmapLevel`, Spalte „jetzt“ markiert), bei `INSUFFICIENT_DATA` stattdessen „Noch zu wenig Daten (X Tage) – die Vorhersage startet nach 14 Tagen“ plus die (teilweise leere) Heatmap.
- SCSS: Farben nur über Tokens; Heatmap-Stufen über die bestehenden Ampel-Tokens bzw. `rgba(var(--tint-rgb), …)` für `none`; Detailbereich füllt die Höhe wie die Karte (`min-height: 0`, `flex: 1`).

- [ ] **Step 1: Failing Spec-Tests:**
  - `zeigtPrognosezeileBeiFavoriten` / `keinePrognosezeileBeiNichtFavoriten`.
  - `listenTippAufFavoritOeffnetDetail` (Detail sichtbar, `getForecast` mit der Id aufgerufen).
  - `markerTippOeffnetKeinDetail` (`select(id, { fromMap: true })` ⇒ `detailStationId` bleibt `null`).
  - `kartencontainerBleibtImDomBeiOffenemDetail`.
  - `schliessenZeigtKarteUndRuftInvalidateSize` (Leaflet-Map gespyt).
  - `zuWenigDatenZeigtHinweis`.
  - `heatmapHat168Zellen`.
  - `refreshFehlerBehaeltVorhersage` (zweiter `getForecast` liefert `throwError`, Anzeige unverändert).
- [ ] **Step 2: Laufen lassen** → FAIL.
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: `npm test -- --watch=false --browsers=ChromeHeadless --include=src/app/pages/tablet-charging/tablet-charging.component.spec.ts`** → PASS; danach Gesamtlauf: nur die 3 bekannten Fails.
- [ ] **Step 5: Im Browser prüfen** (Dev-Server, `/tablet/charging`, Tablet-Ansicht, hell **und** dunkel): Detail öffnen/schließen, Karte nach dem Schließen nicht grau.
- [ ] **Step 6: Commit** — `feat(tablet): Ladesäulen-Vorhersage im Detailbereich`

---

### Task 12: Dokumentation und Gesamtlauf

**Files:**
- Modify: `CLAUDE.md` (nur die eigenen Absätze `git add -p`, die Datei hat fremde uncommittete Änderungen)

- [ ] **Step 1: Abschnitt „Ladesäulen-Vorhersage“** unter „Ladesäulen-Übersicht“: Tabellen + Changesets 0080–0082 (nie gegen echte DB gelaufen, Backup vor Deploy), Lückenregel, Nenner aus `charging_observation`, Hessen-Feiertage in `HessenHolidays`, Schwellen in `ChargingForecastSettings`, Attribute und Formate, `minutesUntilFree` für Flows, Detail nur aus der Liste, Telegram-Tool, Anlaufphase 14 Tage ab Deploy, Unabhängigkeitsannahme, Retention 03:50.
- [ ] **Step 2: Backend-Gesamtlauf** `mvn -q test` → nur `contextLoads`/`HealthControllerTest` rot.
- [ ] **Step 3: Frontend-Build** `npx ng build --configuration production` → ohne neue Fehler (das bekannte Dashboard-SCSS-Budget ist kein Befund).
- [ ] **Step 4: Commit** — `docs: Ladesäulen-Vorhersage in CLAUDE.md`
