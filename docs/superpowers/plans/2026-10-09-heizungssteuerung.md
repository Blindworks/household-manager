# Heizungssteuerung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapo-KE100-Thermostate (am H100-Hub) im Backend erfassen und in einer neuen Tablet-Ansicht `/tablet/heating` bedienen.

**Spec:** `docs/superpowers/specs/2026-10-09-heizungssteuerung-design.md` (verbindlich, bei Widerspruch gewinnt die Spec).

**Architecture:** Backend-Paket `tapo/hub/` spricht den H100 lokal per KLAP/AES an (ein `get_child_device_list` je Zyklus, `control_child` zum Schreiben). Ein Poller schreibt Zustand in den Entity-State-Layer und in `thermostat_history`. Das Frontend spricht nur die REST-API `/v1/thermostats`; bis das Backend steht, liefert ein Mock denselben Vertrag.

**Tech Stack:** Spring Boot 3.4 / Java 21 / Liquibase / JUnit; Angular 19 standalone, ngx-echarts, Karma/Jasmine.

**Arbeitsweise:** Branch `feature/heizungssteuerung`. Backend (Tasks B1–B7) und Frontend (Tasks F1–F5) arbeiten in disjunkten Verzeichnissen und laufen **parallel**; der API-Vertrag unten ist die einzige Schnittstelle. Vor Backend-Builds `JAVA_HOME` auf jdk-21.0.10 setzen (Memory `backend-jdk21-build`). Jede Task endet mit eigenem Commit; Commit-Messages deutsch, Endung `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Alle neuen Repositories gehören nach `com.household.manager.repository` (JpaConfig beschränkt das Scannen).

---

## API-Vertrag (einzige Schnittstelle zwischen Backend und Frontend)

Basis `/api/v1/thermostats` (Frontend-Pfad `/v1/thermostats` über den bestehenden API-Service).

```jsonc
// GET /v1/thermostats  ->  Thermostat[]
{
  "id": 3,                        // thermostat.id (DB)
  "name": "Wohnzimmer",           // Anzeigename = Raum
  "online": true,
  "currentTemp": 20.5,            // null wenn unbekannt
  "targetTemp": 21.0,             // null wenn unbekannt
  "mode": "MANUAL",               // MANUAL | FROST_PROTECTION | OFF
  "heating": false,               // Ventil offen
  "batteryLow": false,
  "lastUpdated": "2026-10-09T18:30:00"   // lokale Haushaltszeit, null wenn nie
}
// PUT /v1/thermostats/{id}/target  body {"targetTemp": 21.5}  -> Thermostat
// PUT /v1/thermostats/{id}/mode    body {"mode": "FROST_PROTECTION"} -> Thermostat
// POST /v1/thermostats/bulk        body {"ids":[1,2], "targetTemp": 17.0}  (ids fehlt/null = alle) -> Thermostat[]
// GET /v1/thermostats/{id}/history?range=DAY|WEEK -> { "points": [ { "time": "2026-10-09T18:00:00", "currentTemp": 20.4, "targetTemp": 21.0, "heating": false } ] }
```

Regeln: `targetTemp` 5,0–30,0 in 0,5-Schritten, sonst 400. Modus `OFF` = Zieltemperatur 5 °C (Gerät kennt kein echtes „Aus"; in der Antwort `mode: OFF`). Hub weg ⇒ 502, nie 401. Unbekannte Id ⇒ 404.

---

## Backend

### Task B1: Migration und Entities

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261009-0058-create-thermostat.xml`, `…/20261009-0059-create-thermostat-history.xml` (getrennte Changesets, DDL-Autocommit-Regel; Vorbild `20260909-0053`)
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (zwei `include`)
- Create: `model/entity/Thermostat.java`, `model/entity/ThermostatHistory.java`, `repository/ThermostatRepository.java`, `repository/ThermostatHistoryRepository.java`

Schema `thermostat`: `id` BIGINT AI PK, `child_device_id` VARCHAR(100) UNIQUE NOT NULL, `hub_device_id` VARCHAR(100) NOT NULL, `hub_ip` VARCHAR(45) NULL, `name` VARCHAR(100) NOT NULL, `active` BOOLEAN NOT NULL DEFAULT TRUE.
Schema `thermostat_history`: `id` BIGINT AI PK, `thermostat_id` BIGINT NOT NULL FK → `thermostat(id)` ON DELETE CASCADE, `recorded_at` DATETIME NOT NULL, `current_temp` DOUBLE NULL, `target_temp` DOUBLE NULL, `heating` BOOLEAN NOT NULL; Index `(thermostat_id, recorded_at)`.

- [ ] **Step 1:** Changesets und Entities (Lombok `@Getter/@Setter/@NoArgsConstructor`, Stil wie `Airrohr`-Entities) anlegen, Repository-Methoden: `findByActiveTrue()`, `findByChildDeviceId(String)`, History: `findByThermostatIdAndRecordedAtBetweenOrderByRecordedAtAsc(Long, LocalDateTime, LocalDateTime)`, `@Transactional @Modifying @Query`-Bulk-Delete `deleteByRecordedAtBefore(LocalDateTime)` (abgeleitete Deletes bringen keine Transaktion mit!).
- [ ] **Step 2:** `cd backend && mvn -q -DskipTests compile` → BUILD SUCCESS.
- [ ] **Step 3:** Commit `feat(heizung): Tabellen thermostat und thermostat_history`.

### Task B2: Hub-Client (KLAP/AES, Child-Devices)

**Files:**
- Create: `tapo/hub/TapoHubClient.java` (Interface), `tapo/hub/TapoHubClientImpl.java`, `tapo/hub/ThermostatReading.java` (record), `tapo/hub/ThermostatMode.java` (enum `MANUAL, FROST_PROTECTION, OFF`), `tapo/hub/ThermostatReadingMapper.java`
- Test: `tapo/hub/ThermostatReadingMapperTest.java`, `tapo/hub/TapoHubClientImplTest.java`
- Modify: `tapo/TapoLocalDeviceConnection.java` — neue Methode `JsonNode request(String method, ObjectNode params)`, in `TapoKlapDeviceConnection` und `TapoAesDeviceConnection` über den bereits vorhandenen privaten Request-Pfad implementieren (dort nachsehen, wie `getDeviceInfo`/`setDeviceInfo` ihre Methode senden; die neue Methode ist die Verallgemeinerung).

Interface:

```java
public interface TapoHubClient {
    /** Ein Aufruf: alle Thermostat-Kinder des Hubs. */
    List<ThermostatReading> readThermostats(String hubIp);
    void setTargetTemp(String hubIp, String childDeviceId, double targetTemp);
    void setFrostProtection(String hubIp, String childDeviceId, boolean on);
}
public record ThermostatReading(String childDeviceId, String nickname, boolean online,
        Double currentTemp, Double targetTemp, ThermostatMode mode, boolean heating, boolean batteryLow) {}
```

Mapping (aus `get_child_device_list` → `result.child_device_list[]`, nur `category` enthält `thermostat` oder `model` beginnt mit `KE100`): `current_temp`→`currentTemp`, `target_temp`→`targetTemp`, `frost_protection_on`→`FROST_PROTECTION`, `target_temp ≤ 5.0`→`OFF`, sonst `MANUAL`; `trv_state == "heating"` oder `"on"`→`heating`; `at_low_battery`→`batteryLow`; `status == "online"`→`online`; `nickname` ist **Base64** → dekodieren (analog `TapoCloudService`/Memory zum L530: `"Rmx1cg=="`). Schreiben: `control_child` mit `{"device_id": <child>, "requestData": {"method":"set_device_info","params":{"target_temp":…}}}`; Frostschutz via `frost_protection_on`.

- [ ] **Step 1: Failing Test** `ThermostatReadingMapperTest`: ein JSON-Fixture `backend/src/test/resources/tapo/hub/child-device-list-ke100.json` (ein KE100 online heizend, einer offline, einer mit Frostschutz; Base64-Nicknames) → erwartete Records inkl. Nickname-Dekodierung, Modus-Ableitung, ein Nicht-Thermostat-Kind (z. B. T315) wird verworfen.
- [ ] **Step 2:** `mvn -q test -Dtest=ThermostatReadingMapperTest` → FAIL (Klasse fehlt).
- [ ] **Step 3:** Mapper implementieren (fail-safe: fehlende Felder ⇒ `null`, nie 0; unbekannter `trv_state` ⇒ `heating=false`).
- [ ] **Step 4:** Test grün.
- [ ] **Step 5:** `TapoHubClientImplTest` mit Fake-Verbindung (Vorbild: Fake-KLAP-Gerät im Test des Stale-Socket-Fixes, Commit `20d1fd9`): prüft, dass `readThermostats` genau **einen** Request sendet, `setTargetTemp` den verschachtelten `control_child`-Body baut, und dass bei IOException einmal mit neuer Verbindung wiederholt wird. Impl über `TapoDeviceFactory.create(protocol, ip, email, password)` (KLAP zuerst, dann AES, Protokoll je Hub-IP cachen), Fehler als `TapoException`.
- [ ] **Step 6:** Alle Tapo-Tests grün: `mvn -q test -Dtest='Tapo*Test,Thermostat*Test'`.
- [ ] **Step 7:** Commit `feat(heizung): Hub-Client fuer KE100 (Child-Devices)`.

### Task B3: ThermostatService (Validierung, Schreiben, Audit)

**Files:**
- Create: `thermostat/ThermostatService.java`, `thermostat/ThermostatDto.java`, `thermostat/ThermostatStateCache.java`, `thermostat/ThermostatTargetValidator.java`
- Test: `thermostat/ThermostatTargetValidatorTest.java`, `thermostat/ThermostatServiceTest.java`

`ThermostatTargetValidator.validate(double)`: wirft `IllegalArgumentException` (→ 400 über GlobalExceptionHandler) wenn `!Double.isFinite(v)`, `v < 5.0`, `v > 30.0` oder `v*2` nicht ganzzahlig.

`ThermostatStateCache`: rein im Speicher `Map<Long, ThermostatReading>` + `lastUpdated`, vom Poller befüllt (Muster `NetworkDeviceStatusMonitor`).

`ThermostatService`: `list()`, `setTarget(id, temp)`, `setMode(id, mode)` (`OFF` ⇒ `setTargetTemp(5.0)` + `setFrostProtection(false)`; `FROST_PROTECTION` ⇒ `setFrostProtection(true)`; `MANUAL` ⇒ `setFrostProtection(false)`; Ziel bleibt), `bulk(ids|null, temp)`. Nach jedem Schreiben sofortiges Nachlesen des Hubs (ein Aufruf) und Cache-Update; Audit **nach** Erfolg über `AuditService.record` (`thermostat.target`, `thermostat.mode`, `thermostat.bulk`). Unbekannte Id ⇒ `ResourceNotFoundException` (vorhandene Klasse im `exception`-Paket verwenden). `TapoException` ⇒ wird zu 502 (siehe B5).

- [ ] **Step 1:** Validator-Test (5.0 ok, 30.0 ok, 21.5 ok, 21.3 → Exception, 4.5, 30.5, NaN, Infinity → Exception). Rot → Validator → grün.
- [ ] **Step 2:** `ThermostatServiceTest` mit gemocktem `TapoHubClient`: setTarget ruft Client mit richtiger Hub-IP/Child-Id, Audit erst nach Erfolg (bei Client-Exception **kein** Audit), setMode-Matrix (3 Fälle), bulk mit `ids=null` trifft alle aktiven. Rot → implementieren → grün.
- [ ] **Step 3:** Commit `feat(heizung): ThermostatService mit Validierung und Audit`.

### Task B4: Poller, Entity-Spiegelung, Historie

**Files:**
- Create: `thermostat/ThermostatPollingService.java`, `thermostat/ThermostatEntityMapper.java`, `thermostat/ThermostatHistoryRecorder.java`, `thermostat/ThermostatHistoryRetentionJob.java`, `thermostat/ThermostatHistoryService.java`
- Modify: `entitystate/EntitySource.java` — neuer Wert `THERMOSTAT`
- Test: `ThermostatEntityMapperTest`, `ThermostatPollingServiceTest`, `ThermostatHistoryRecorderTest`

Verhalten:
- `ThermostatPollingService` `@Scheduled` (Property `thermostat.poll-interval-ms`, Default 60000; Aktiv-Flag `thermostat.enabled`), **wirft nie**; gruppiert aktive Thermostate nach `hub_ip`, **ein** `readThermostats` je Hub; Fehler eines Hubs ⇒ nur dessen Thermostate `unavailable` (Attribute erhalten: über `entityStateService.find()` lesen und mitgeben, `EntityStateWriter.upsert` überschreibt sonst); Treffer ⇒ Cache + Entitäten + Historie. Kind-Gerät nicht in der Hub-Antwort ⇒ `online=false`.
- `ThermostatEntityMapper.entityId(thermostat, suffix)` ist die **einzige** Id-Definition (`EntityIds.build`): `sensor.thermostat_<id>_temperature`, `sensor.thermostat_<id>_target`, `binary_sensor.thermostat_<id>_heating`. **Kein `deviceClass`** (Test hält die Abwesenheit fest, Begründung siehe CLAUDE.md Blink).
- `ThermostatHistoryRecorder` schreibt je Poll einen Punkt **nur wenn** sich Ist/Soll/Heizen gegenüber dem letzten gespeicherten Punkt geändert haben **oder** seit 5 min kein Punkt entstand; wirft nie (try/catch, Muster `PowerHistoryRecorder`); läuft **vor** dem Entity-Reporting.
- `ThermostatHistoryRetentionJob` nachts 03:40: Rohpunkte >30 Tage löschen (Bulk-Delete aus B1). Keine Verdichtung in v1 (bei ≤1 Punkt/min/Thermostat bleibt das klein) — bewusste Abweichung von der Spec („Verdichtung nach Muster Power") zugunsten Einfachheit; im Spec-Abschnitt „Rollout" nachtragen.
- `ThermostatHistoryService.history(id, range)`: lädt Punkte, downsampled über den bestehenden `SeriesDownsampler` (`SeriesRange` DAY/WEEK).

- [ ] **Step 1:** Mapper-Test (3 Entity-Ids, State-Strings `"20.5"`, `"on"/"off"`, offline ⇒ `unavailable` mit erhaltenen Attributen, **kein** `deviceClass`). Rot→grün.
- [ ] **Step 2:** Polling-Test (gemockter Client): ein Hub-Aufruf bei zwei Thermostaten am selben Hub; Hub wirft ⇒ beide `unavailable`, Methode wirft nicht; Recorder läuft vor Reporting (`InOrder`).
- [ ] **Step 3:** Recorder-Test (unveränderter Wert innerhalb 5 min ⇒ kein neuer Punkt; Änderung ⇒ Punkt; Repository-Fehler ⇒ keine Exception).
- [ ] **Step 4:** `mvn -q test -Dtest='Thermostat*Test'` grün. Commit `feat(heizung): Poller, Entity-Spiegelung und Verlauf`.

### Task B5: Controller, Hub-Verwaltung, Fehlerabbildung

**Files:**
- Create: `thermostat/ThermostatController.java`, `thermostat/dto/*` (Request-Records `TargetRequest`, `ModeRequest`, `BulkRequest`, `HistoryResponse`), `thermostat/ThermostatHubAdminController.java`
- Modify: `exception/GlobalExceptionHandler.java` (`TapoException` im Thermostat-Kontext ⇒ 502; vorhandenen Handler prüfen, nicht doppeln)
- Test: `thermostat/ThermostatControllerTest.java` (WebMvc-Test, Vorbild `MeterReadingSeriesControllerTest`)

Endpunkte laut Vertrag. Zusätzlich Hub-Pflege (ADMIN): `PUT /v1/thermostats/hub` Body `{"ip": "..."}` — probt per `TapoDeviceService.probeAddress`-Muster, **Identitätsprüfung** der gemeldeten `device_id`, legt/aktualisiert danach `thermostat`-Zeilen aus der Kinderliste (Upsert über `child_device_id`, Name aus Nickname nur beim Anlegen — spätere Umbenennung im DB-Feld `name` bleibt erhalten). Antwort: Liste der gefundenen Thermostate. `PUT /v1/thermostats/{id}` (ADMIN) benennt um.

- [ ] **Step 1:** Controller-Test: 200-Pfade, 400 bei 21.3 / 31 / fehlendem Body, 404 unbekannte Id, 502 bei `TapoException`. Rot→grün.
- [ ] **Step 2:** Commit `feat(heizung): REST-API /v1/thermostats`.

### Task B6: Sicherheit

**Files:**
- Modify: `config/SecurityConfig.java`; Test: `SecurityRulesTest.java`

Regeln: `PUT /v1/thermostats/hub` und `PUT /v1/thermostats/{id}` (Umbenennen) = **ADMIN, methodenspezifisch, vor** den KIOSK-Whitelist-Zeilen. In die KIOSK-Whitelist (dort, wo `POST /v1/blink/cameras/*/snapshot` steht, Muster nachsehen): `PUT /v1/thermostats/*/target`, `PUT /v1/thermostats/*/mode`, `POST /v1/thermostats/bulk`. **Matcher-Reihenfolge beachten:** `/hub` ist ein literales Segment und muss vor `/*/target`-artigen Mustern stehen, damit `PUT /v1/thermostats/hub` nicht zufällig als `{id}`-Pfad gewertet wird.

- [ ] **Step 1: Tests (rot)** je Zeile: KIOSK darf target/mode/bulk (200), KIOSK darf `PUT /hub` **nicht** (403), MEMBER darf `PUT /hub` nicht (403), ADMIN darf (200), KIOSK darf `GET /v1/thermostats` (200).
- [ ] **Step 2:** Regeln eintragen → grün: `mvn -q test -Dtest=SecurityRulesTest`.
- [ ] **Step 3:** Commit `feat(heizung): Security-Regeln fuer Thermostate`.

### Task B7: Doku und Gesamtlauf

- [ ] **Step 1:** `mvn -q test` für das Backend; vorbestehende Fehlschläge (DB-abhängig, Memory `backend-jdk21-build`) nur melden, nicht beheben. **Keine** neuen Fehlschläge.
- [ ] **Step 2:** CLAUDE.md um Abschnitt „Heizungssteuerung (Tapo KE100)" ergänzen: Hub-IP von Hand (Docker-Bridge, feste IP-Reservierung), Schreibpfad **unverifiziert**, KIOSK darf schreiben (UI-Schutz = Bestätigung bei Sammelaktion), `OFF` = 5 °C, Retention 30 Tage ohne Verdichtung, Entity-Ids und Namensfalle (`Name` ändern ändert die Id **nicht**, die Id hängt an der DB-Id), kein `deviceClass`. Spec-Abschnitt Verlauf/Verdichtung angleichen.
- [ ] **Step 3:** Commit `docs: Heizungssteuerung in CLAUDE.md`.

---

## Frontend

Pfade relativ zu `frontend/src/app/`. Vorbilder zum **Abgucken** (lesen, Struktur und Konventionen übernehmen): `pages/tablet-network/*` (Shell, Refresh, `pendingRequest`, Chart-Felder), `pages/tablet-temperatures/*` (Höhenkette, Messung im Spec), `components/tablet-shell/`. Standalone, separate `.html`, SCSS, Farben **nur** über lumina-Tokens (`var(--…)` aus `shared/styles/_lumina-theme.scss`; kein hartkodiertes Hex außer den bewussten Warm/Kühl-Akzenten, die über zwei neue Tokens `--heat-warm`/`--heat-cool` mit Werten in beiden Mixins `dark-tokens`/`light-tokens` definiert werden).

### Task F1: Modell, Service, Mock

**Files:**
- Create: `models/thermostat.model.ts`, `services/thermostat.service.ts`, `services/thermostat-mock.ts`, `services/thermostat.service.spec.ts`

```ts
export type ThermostatMode = 'MANUAL' | 'FROST_PROTECTION' | 'OFF';
export interface Thermostat {
  id: number; name: string; online: boolean;
  currentTemp: number | null; targetTemp: number | null;
  mode: ThermostatMode; heating: boolean; batteryLow: boolean;
  lastUpdated: string | null;
}
export interface ThermostatHistoryPoint { time: string; currentTemp: number | null; targetTemp: number | null; heating: boolean; }
export type ThermostatRange = 'DAY' | 'WEEK';
```

`ThermostatService`: `list()`, `setTarget(id, t)`, `setMode(id, m)`, `bulk(ids|null, t)`, `history(id, range)` — alle über den bestehenden API-Service/HttpClient (Konvention aus `network.service.ts` übernehmen). Quelle umschaltbar: `environment.useThermostatMock` (Default `true` in `environment.ts`, **`false` in `environment.prod.ts`**); der Mock (`ThermostatMock`) hält fünf Räume im Speicher (Wohnzimmer heizt, Schlafzimmer Frostschutz, Küche offline, Bad Batterie niedrig, Büro Aus), verändert bei `setTarget/setMode` den Zustand und liefert generierte Verläufe (Sinus + Stufe).

- [ ] **Step 1: Test (rot):** Service im Mock-Modus: `list()` liefert 5 Einträge; `setTarget(1, 22)` ist danach in `list()` sichtbar; `setMode(2,'OFF')` setzt Modus; `bulk(null, 17)` setzt alle **online**-Thermostate (offline bleibt unberührt); `history` liefert ≥ 24 Punkte.
- [ ] **Step 2:** Implementieren → grün (`cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='**/thermostat.service.spec.ts'`; Kommando-Variante aus Memory `frontend-test-baseline` nehmen).
- [ ] **Step 3:** Commit `feat(heizung): Thermostat-Modell, Service und Mock`.

### Task F2: Reine Logik (Util)

**Files:** Create `shared/thermostat-view.util.ts`, `shared/thermostat-view.util.spec.ts`

Funktionen (alle rein, ohne Angular): `clampTarget(t)` (5–30, auf 0,5 gerundet), `stepTarget(current, direction)` (±0,5 mit Klemmung), `thermostatTone(t): 'offline' | 'heating' | 'idle' | 'frost' | 'off'` (**erschöpfende** Abbildung ohne `default`), `arcFraction(t)` (Anteil 5–30 °C für den Bogen, `null` wenn kein Wert), `formatTemp(v)` (`"20,5"` bzw. `"–"` bei null), `gridColumns(count)` (≤4 → 2, sonst 3).

- [ ] **Step 1:** Spec mit Grenzfällen: `stepTarget(30,+1)=30`, `stepTarget(5,-1)=5`, `clampTarget(21.3)=21.5`, `formatTemp(null)='–'`, `thermostatTone` für alle fünf Zustände (offline schlägt alles), `gridColumns(4)=2`, `gridColumns(5)=3`. Rot→implementieren→grün.
- [ ] **Step 2:** Commit `feat(heizung): Thermostat-Hilfsfunktionen`.

### Task F3: Ring-Zifferblatt (Kachelkomponente)

**Files:** Create `components/thermostat-tile/thermostat-tile.component.{ts,html,scss,spec.ts}`

Eigenständige Komponente (Input `thermostat: Thermostat`; Outputs `targetChange: number`, `modeChange: ThermostatMode`; Input `busy: boolean`). Eigenes SCSS, Farben nur über Tokens (Komponente steht **innerhalb** der Shell, erbt damit CSS-Variablen). Ring als **SVG-Bogen** (kein ECharts, für Touch-Schärfe und weil keine Redraw-Falle): Bogen von 135° bis 405°, gefüllt nach `arcFraction(targetTemp)`, Farbe `--heat-warm` bei `heating`, sonst `--heat-cool`. Mitte: Ist-Temperatur groß (`formatTemp`), darunter „Soll 21,0 °C" klein. Unter dem Ring −/+-Knöpfe (min. 56×56 px Touch-Fläche). Modus-Segment (3 Knöpfe). Chips „heizt", „Batterie niedrig", „offline". Offline/ohne Wert: Kachel abgedunkelt, „–", Knöpfe deaktiviert, Grundtext („Nicht erreichbar").
**Entprellen:** Tippen verändert sofort ein lokales `pendingTarget`; 800 ms nach dem letzten Tipp wird `targetChange` **einmal** emittiert (RxJS `Subject` + `debounceTime`, in `ngOnDestroy` aufräumen). Wechselt der Server-`targetTemp`-Input, solange kein Tipp aussteht, übernimmt die Anzeige ihn; steht ein Tipp aus, gewinnt `pendingTarget`.

- [ ] **Step 1: Tests (rot, `fakeAsync`):** drei schnelle `+`-Klicks ⇒ nach 800 ms genau **ein** `targetChange` mit +1,5; offline ⇒ Knöpfe `disabled`, Text „–"; Modus-Klick emittiert `modeChange`; Chips je nach Flags; `heating` ⇒ Bogen trägt Klasse `tile__arc--warm`; Grenze 30 °C: `+` ändert nichts.
- [ ] **Step 2:** Implementieren → grün.
- [ ] **Step 3:** Commit `feat(heizung): Raumkachel mit Ring-Zifferblatt`.

### Task F4: Seite `/tablet/heating`

**Files:**
- Create: `pages/tablet-heating/tablet-heating.component.{ts,html,scss,spec.ts}`
- Modify: `app.routes.ts` (Route `tablet/heating` mit denselben Guards wie die Schwesteransichten), `shared/tablet-views.ts` (Eintrag `{ route: '/tablet/heating', icon: 'mode_heat', label: 'Heizung' }` — **nicht** `thermostat`, das Icon gehört schon den Temperaturen), `shared/styles/_lumina-theme.scss` (Tokens `--heat-warm`, `--heat-cool` in beiden Mixins)

Verhalten laut Spec: `<app-tablet-shell heading="Heizung">`; `[shellActions]` mit Zeitraum (24 h/7 Tage) und Menü „Alle auf …" (Stufen 17 °C und 21 °C) mit Bestätigungsdialog (Markup **in dieser Seite**, eigene Styles, weiße Dialogfläche wie die übrigen Dialoge; Dialog hält nur die gewählte Temperatur, die Ziel-IDs werden beim Bestätigen aus der **aktuellen** Liste neu aufgelöst und nur **online**-Thermostate geschaltet). Raster mit `gridColumns`. Je Kachel unter dem Ring ein Sparkline-Chart (ngx-echarts, Optionen als **Felder** in einer `Map<number, EChartsOption>`, Ist-Linie plus Soll als Stufenlinie, `connectNulls: false`). 60-s-Refresh, nur der Erstabruf meldet Fehler, `pendingRequest` bestellt Verlaufsabrufe beim Zeitraumwechsel ab. Schreibfehler: Meldung inline an der Kachel und Rücksetzen auf den Serverwert (`list()` neu laden). Höhenkette: `:host { display:flex; flex-direction:column; flex:1; min-height:0 }` durch Shell, Raster (`grid-auto-rows: 1fr`) bis in die Kachel.

- [ ] **Step 1: Tests (rot):** (a) rendert 5 Kacheln aus dem Mock; (b) `targetChange` einer Kachel ruft `setTarget` einmal; (c) Fehler beim Erstabruf zeigt Meldung, Fehler beim zweiten Abruf behält Kacheln; (d) „Alle auf 17" öffnet Dialog, **kein** Request vor „Bestätigen"; Bestätigen ruft `bulk` nur mit den online-Ids, auch wenn ein Thermostat zwischenzeitlich offline ging; (e) Zeitraumwechsel bestellt den laufenden Verlaufsabruf ab (`Subject`-Stub, nicht `of`); (f) Höhenketten-Test: Host in eigenen Container hängen (nicht `parentElement`, siehe CLAUDE.md), Messung bei 900 und 1200 px, Chart-Höhe wächst mit.
- [ ] **Step 2:** Implementieren → grün.
- [ ] **Step 3:** `ng build --configuration production` darf **keinen neuen** Fehler melden (SCSS-Budget-Fehler des Dashboards ist vorbestehend, Memory `dashboard-scss-budget`).
- [ ] **Step 4:** Commit `feat(heizung): Tablet-Ansicht Heizung`.

### Task F5: Sichtprüfung im Browser

- [ ] **Step 1:** `preview_start`/Dev-Server starten (Mock aktiv), `/tablet/heating` laden, Tablet-Modus; Screenshots bei 1280×800 **hell und dunkel** (Darstellung unter Admin umschalten bzw. `.lumina--light`), Zustände prüfen: heizt (warmer Bogen), Frostschutz, offline, Batterie niedrig.
- [ ] **Step 2:** Konsole auf Fehler prüfen, Ansichtsleiste mit acht Einträgen prüfen (scrollt, nichts abgeschnitten).
- [ ] **Step 3:** Auffälligkeiten beheben, Commit `style(heizung): Feinschliff nach Sichtprüfung`.

---

## Integration (nach beiden Strängen)

### Task I1: Verdrahten und Abschluss

- [ ] **Step 1:** In `environment.ts` `useThermostatMock: false` setzen? **Nein** — Dev bleibt auf Mock, bis die Hub-IP real eingetragen ist; PROD (`environment.prod.ts`) hat `false`.
- [ ] **Step 2:** Admin-Zugang zur Hub-Pflege: kleine Admin-Seite `pages/admin-heating/` (Route `admin/heating`, Wurzelklasse in `$admin-scopes` in `styles.scss` eintragen!) mit Feld „Hub-IP", Knopf „Hub suchen" (`PUT /v1/thermostats/hub`), Liste der gefundenen Thermostate mit Umbenennen. Tests: Eingabe leer ⇒ Knopf gesperrt; Fehler 400/502 werden als Klartext gezeigt.
- [ ] **Step 3:** Gesamttests: `mvn -q test` und Frontend-Gesamtlauf; nur vorbestehende Fehlschläge (Memory `frontend-test-baseline`) sind erlaubt.
- [ ] **Step 4:** Abschlussreview (Skill `superpowers:requesting-code-review`), danach `superpowers:finishing-a-development-branch`.

## Bewusste Abweichungen von der Spec

- Historien-Verdichtung entfällt in v1 (nur 30-Tage-Retention); Begründung in B4, Spec wird in B7 angeglichen.
- Hub-Verwaltungs-Endpunkt und Admin-Seite sind ergänzt (Spec nennt „IP von Hand eintragen", aber keinen Weg dafür).
