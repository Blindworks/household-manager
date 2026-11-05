# Zwilling: Geräte-Detail und Bedienung — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Tipp auf eine Geräte-Bubble der Raumansicht von `/tablet/house` fährt die Kamera ans Gerät und öffnet ein Detail-Feld mit aktuellem Wert, Verlauf und Bedienung (Sollwert, An/Aus, Helligkeit) statt auf eine andere Seite zu springen.

**Architecture:** Backend: KIOSK darf `PUT /devices/*/light`, `SmartDeviceResponse` trägt die `entityId` der gespiegelten Switch-Entität. Frontend: eine reine Util (`device-detail.util.ts`) baut aus `HouseDevice` + Entitäten das Feld-Modell, ein Service (`HouseDeviceControlService`) kapselt Nachschlagen (sensorId, Thermostat, Smart-Device) und Schalten mit Frischlesen, eine Kind-Komponente (`HouseDeviceDetailComponent`) zeigt Feld, Diagramm und Bedienelemente, die Seite verdrahtet Kamera und Öffnen/Schließen.

**Tech Stack:** Spring Boot 3 (Security, DTO), Angular 19 standalone, ngx-echarts, Three.js (vorhanden), Karma/Jasmine, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-05-zwilling-geraete-detail-design.md` (baut auf `2026-11-05-digitaler-zwilling-3d-design.md` auf)

## Global Constraints

- Sollwert 5,0–30,0 °C in 0,5-Schritten; `ThermostatTargetValidator` (Backend) ist die Autorität, Frontend-Grenzen sind nur Bedienhilfe.
- Leistung im Feld nur, solange der Schalter `on` ist und der Wert jünger als 5 min ist (bestehende Regel `livePower` in `house-bubbles.util.ts`).
- Veraltete Werte abdunkeln, `unavailable`/`unknown` nie als Wert (bestehende Regeln in `house-bubbles.util.ts` wiederverwenden, keine zweite Definition).
- Kein Schalten über die Bubble, beim Drehen oder durch einen Refresh; vor jedem Toggle Zustand frisch lesen, nur schalten, wenn er der Absicht widerspricht und `on`/`off` ist.
- Ausschalten fragt nach bei `confirmRequired` **oder** bei einem `appliance`, dessen Helfer „…läuft“ `on` ist; Einschalten direkt.
- Helligkeitsregler nur bei Fähigkeit `BRIGHTNESS`, gesperrt solange die Lampe aus ist; sendet erst beim Loslassen (`change`).
- Türschloss, Kamera, Mülltonne, Toni bleiben Navigation; Kontakt/Bewegung/Taster öffnen ein Lese-Feld ohne Verlauf.
- `PUT /devices/*/light` → KIOSK; alle anderen `/devices`-Schreibpfade bleiben MEMBER.
- Diagramme mit ngx-echarts; Three.js nur in `house-scene-renderer.ts`; Render-on-demand bleibt.
- Bedienelemente ≥ 44 px, Text ≥ 0,8 rem; Seite bleibt dunkel (`forceDark`), lumina-Kapselung beachten (Kind-Komponente mit eigenen Styles, Farben über eigene Neon-Tokens).
- Frontend-Testbaseline: 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent`). Backend: JDK 21 (`JAVA_HOME` auf jdk-21.0.10), `contextLoads`/DB-Tests lokal rot by design — nur die betroffenen Testklassen laufen lassen.

## Review Focus

1. **Refresh bei offenem Feld:** der 30-s-Abruf ändert den angezeigten Zustand, darf aber nie eine Schaltaktion auslösen oder eine laufende Eingabe (−/+, Regler) zurücksetzen. Test in Task 4.
2. **Schnelles −/+:** fünf Tipps in 1 s ergeben genau einen `setTarget`-Aufruf mit dem Endwert, geklemmt auf 5–30. Test in Task 4.
3. **Gerät verschwindet/wird unavailable bei offenem Feld:** Wert „–“, Bedienung gesperrt, kein Fehler. Test in Task 2.
4. **Schneller Zeitraumwechsel:** eine langsamere ältere Verlaufsantwort überschreibt nicht die neuere (Abbestellen). Test in Task 4.
5. **Geschlossenes Feld während eines Schaltaufrufs:** Antwort kommt nach dem Schließen an → kein Fehler, kein Wiederöffnen. Test in Task 5.

---

## File Structure

```
backend/src/main/java/com/household/manager/
├── security/SecurityConfig.java                 # + KIOSK-Matcher PUT /devices/*/light
├── dto/SmartDeviceResponse.java                 # + entityId
└── service/SmartDeviceService.java              # entityId setzen (Mapper als einzige Definition)
backend/src/test/java/com/household/manager/security/SecurityRulesTest.java
frontend/src/app/
├── models/smart-device.model.ts                 # + entityId?: string
├── shared/house/device-detail.util.ts (+ .spec) # HouseDevice + Entitäten → DeviceDetail
├── shared/house/house-geometry.util.ts (+ spec) # + cameraForDevice
├── pages/tablet-house/house-device-control.service.ts (+ .spec)
├── pages/tablet-house/device-detail/house-device-detail.component.{ts,html,scss,spec.ts}
└── pages/tablet-house/tablet-house.component.{ts,html,scss,spec.ts}
CLAUDE.md
```

---

### Task 1: Backend — Dimmen für KIOSK, entityId am Gerät

**Files:**
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java` (neben dem KIOSK-PUT-Matcher der Thermostate, ~Zeile 273)
- Modify: `backend/src/main/java/com/household/manager/dto/SmartDeviceResponse.java` (+ `private String entityId;`)
- Modify: `backend/src/main/java/com/household/manager/service/SmartDeviceService.java` (bei `.confirmRequired(...)` ~Zeile 1094 auch `.entityId(...)`)
- Modify: `frontend/src/app/models/smart-device.model.ts` (+ `entityId?: string;`)
- Test: `backend/src/test/java/com/household/manager/security/SecurityRulesTest.java`, bestehender Test zu `SmartDeviceService`-Mapping (per `grep -rl "confirmRequired" backend/src/test` finden)

**Interfaces:**
- Produces: `GET /api/devices` → jedes Gerät hat `entityId` = `SmartDeviceEntityMapper.entityId(device)` (null nur wenn der Mapper wirft, dann wie bei `confirmRequiredFor` geloggt); `PUT /api/devices/{id}/light` für KIOSK erlaubt.

- [ ] **Step 1: Failing tests:** in `SecurityRulesTest` — `kioskDarfLichtSetzen` (KIOSK `PUT /devices/1/light` ⇒ nicht 403), `kioskDarfAdresseNichtSetzen` (KIOSK `PUT /devices/1/address` ⇒ 403), `kioskDarfGeraetNichtAendern` (KIOSK `PUT /devices/1` ⇒ 403). Im Mapping-Test: Antwort trägt `entityId` gleich `smartDeviceEntityMapper.entityId(device)`.
- [ ] **Step 2: Run** (JDK 21): `mvn -q test -Dtest=SecurityRulesTest+<MappingTestKlasse>` aus `backend/` ⇒ FAIL.
- [ ] **Step 3: Implement.** Matcher `requestMatchers(HttpMethod.PUT, "/devices/*/light").hasRole("KIOSK")` mit Kommentar (Nutzerentscheidung 2026-11-05: Dimmen am Wandtablet, jeder vor dem Tablet darf dimmen wie schalten). `entityId` über denselben Mapper wie `confirmRequiredFor` — Mapper einmal je Gerät aufrufen und beide Werte daraus bilden.
- [ ] **Step 4: Run** ⇒ PASS.
- [ ] **Step 5: CLAUDE.md** im TP-Link-Abschnitt den Satz „Beide neuen PUT-Endpunkte hängen unter `/devices/**` … fällt durch auf `anyRequest` → MEMBER“ korrigieren: `/light` ist seit 2026-11-05 KIOSK, `/address` bleibt MEMBER.
- [ ] **Step 6: Commit** `feat(devices): Dimmen fuer das Wandtablet, entityId am Geraet`

---

### Task 2: Feld-Modell (reine Util)

**Files:**
- Create: `frontend/src/app/shared/house/device-detail.util.ts`, `.spec.ts`
- Modify (nur Export): `frontend/src/app/shared/house/house-bubbles.util.ts` — vorhandene interne Helfer für Messwert/Veraltung/`livePower`/Tasterwort exportieren, statt sie zu kopieren.

**Interfaces:**
- Consumes: `HouseDevice` (`house-devices.ts`), `BubbleValue`, `EntityState`, `parseLocalTimestamp`, `STALE_THRESHOLD_MS`.
- Produces:
  ```ts
  export type HistorySource =
    | { type: 'temperature'; entityId: string }          // sensorId löst der Service auf
    | { type: 'thermostat'; thermostatId: number }
    | { type: 'power'; entityId: string };
  export type DetailControl =
    | { type: 'target'; thermostatId: number; target: number | null; min: 5; max: 30; step: 0.5 }
    | { type: 'switch'; entityId: string; on: boolean | null; confirmOff: boolean; dimmable: boolean };
  export interface DeviceDetail {
    deviceId: string; title: string; icon: string;
    headline: BubbleValue;            // großer Wert
    lines: BubbleValue[];             // Nebenwerte (Feuchte, Soll, heizt, Leistung, seit …)
    history: HistorySource | null;    // null bei Kontakt/Bewegung/Taster
    control: DetailControl | null;
  }
  export function buildDeviceDetail(device: HouseDevice, entities: ReadonlyMap<string, EntityState>, nowMs: number): DeviceDetail;
  export function opensDetail(kind: HouseDevice['kind']): boolean; // true: climate, thermostat, switch, appliance, contact, motion, button
  export function thermostatIdOf(entityId: string): number | null; // 'sensor.thermostat_8_target' → 8
  ```
  `dimmable` ist in diesem Task immer `false` — die Fähigkeit kommt aus dem Smart-Device (Task 3) und wird in Task 5 eingesetzt.

- [ ] **Step 1: Failing tests:**
  - `Fühler: headline Temperatur, Zeile Feuchte, history temperature mit entityId der Temperatur, control null`
  - `Thermostat 8: headline Ist '20,1°', Zeile 'Soll 20°', Zeile 'heizt'/'heizt nicht', control target mit thermostatId 8 und target 20, history thermostat 8`
  - `Switch an mit frischer Leistung: headline 'an', Zeile '221 W', control switch on:true, history power mit powerEntityId; ohne powerEntityId history null`
  - `Switch confirmRequired: confirmOff true`; `Appliance läuft: confirmOff true, auch ohne confirmRequired`; `Appliance fertig/aus: confirmOff = confirmRequired`
  - `Kontakt/Bewegung/Taster: headline Zustand ('offen'/'zu', 'Bewegung'/'ruhig', letzter Druck 'einfach'), Zeile 'seit HH:mm' aus lastChanged, history null, control null`
  - `unavailable Switch: headline '–' stale, control on:null` und `fehlende Entität wirft nicht, headline '–'`
  - `opensDetail: false für door, camera, waste, pet`
  - `thermostatIdOf('sensor.thermostat_8_temperature') === 8, ('switch.x') === null`
- [ ] **Step 2: Run** `npm test -- --watch=false --browsers=ChromeHeadless --include "src/app/shared/house/**/*.spec.ts"` aus `frontend/` ⇒ FAIL.
- [ ] **Step 3: Implement** in `device-detail.util.ts`; Werte und Veraltung ausschließlich über die exportierten Helfer aus `house-bubbles.util.ts`.
- [ ] **Step 4: Run** ⇒ PASS; Bubble-Specs unverändert grün.
- [ ] **Step 5: Commit** `feat(house): Feld-Modell fuer das Geraete-Detail`

---

### Task 3: Steuer- und Nachschlage-Service

**Files:**
- Create: `frontend/src/app/pages/tablet-house/house-device-control.service.ts`, `.spec.ts`

**Interfaces:**
- Consumes: `TemperatureService.getCurrent()/getSeries('DAY')/getSensorSeries`, `ThermostatService.list()/history()/setTarget()`, `PowerConsumerService.getHistory()`, `SwitchService.getSwitches(undefined, 'all')/toggle()`, `SmartDeviceService.getAllDevices()/setLightState()`, `HistorySource` (Task 2), `SmartDevice.entityId` (Task 1).
- Produces (`@Injectable` in der Komponente provided, nicht root):
  ```ts
  export interface HistoryPoint { at: string; value: number | null }
  export interface HistorySeries { name: string; points: HistoryPoint[]; dashed?: boolean }
  export type SwitchOutcome = 'switched' | 'already' | 'unavailable';
  loadHistory(source: HistorySource, range: 'DAY' | 'WEEK'): Observable<HistorySeries[] | null>; // null = kein Verlauf verfügbar
  setSwitch(entityId: string, on: boolean): Observable<SwitchOutcome>;  // liest frisch, toggelt nur bei Widerspruch und on/off
  setTarget(thermostatId: number, target: number): Observable<void>;    // klemmt 5..30, rundet auf 0,5
  setBrightness(entityId: string, brightness: number): Observable<void>;// sucht Gerät über entityId
  lightDevice(entityId: string): Observable<{ dimmable: boolean; brightness: number | null } | null>;
  thermostatRoom(thermostatId: number): Observable<BubbleValue | null>; // Raumwert über roomReading, null ohne Raumfühler
  ```
  sensorId: zuerst `/current` (entityId-Treffer), sonst einmal `getSeries('DAY')`, Ergebnis je Seite gecacht; kein Treffer ⇒ `loadHistory` liefert `null`. Thermostat-Verlauf: Ist durchgezogen, Soll `dashed: true`. Smart-Device-Liste je Seite einmal laden und cachen (Neuladen nach `setBrightness`).

- [ ] **Step 1: Failing tests** (Services als Jasmine-Spies):
  - `setSwitch(on:true) bei frischem Zustand off ⇒ toggle; Ergebnis 'switched'`
  - `setSwitch(on:true) bei frischem Zustand on ⇒ kein toggle, 'already'` (Hintergrund-Refresh-Fall)
  - `setSwitch bei unavailable ⇒ kein toggle, 'unavailable'`
  - `setTarget(31) ⇒ setTarget(id, 30); setTarget(20.3) ⇒ 20.5; setTarget(4) ⇒ 5`
  - `loadHistory temperature: entityId aus /current ⇒ getSensorSeries(sensorId, range)`; `nicht in /current, aber in getSeries ⇒ dort gefunden`; `nirgends ⇒ null`
  - `loadHistory thermostat ⇒ zwei Serien, Soll dashed`
  - `setBrightness ⇒ setLightState(device.id, { brightness })` über `entityId`-Treffer; ohne Treffer ⇒ Fehler-Observable
  - `lightDevice: BRIGHTNESS in capabilities ⇒ dimmable true mit brightness`
- [ ] **Step 2: Run** `--include "src/app/pages/tablet-house/**/*.spec.ts"` ⇒ FAIL.
- [ ] **Step 3: Implement.** Feldnamen der vorhandenen Antworttypen (`TemperatureSensorSeries`, `ThermostatHistoryResponse`, `PowerHistory`, `LightStateRequest`) in den Models nachlesen.
- [ ] **Step 4: Run** ⇒ PASS.
- [ ] **Step 5: Commit** `feat(house): Steuer-Service fuer das Geraete-Detail`

---

### Task 4: Detail-Feld-Komponente

**Files:**
- Create: `frontend/src/app/pages/tablet-house/device-detail/house-device-detail.component.{ts,html,scss,spec.ts}`

**Interfaces:**
- Consumes: `DeviceDetail`, `DetailControl` (Task 2), `HouseDeviceControlService` (Task 3).
- Produces: `<app-house-device-detail [detail]="…" (closed)="…">` — standalone, OnPush, eigene dunkle Styles. Inputs: `detail: DeviceDetail` (wird bei jedem Refresh neu gesetzt). Outputs: `closed`.

- [ ] **Step 1: Failing tests** (Service als Fake):
  - `zeigt Titel, headline groß, lines; veraltete Werte abgedunkelt`
  - `Verlauf: lädt beim Öffnen DAY; Knopf '7 Tage' lädt WEEK; null ⇒ Text 'Kein Verlauf verfügbar'`
  - `Zeitraumwechsel bestellt den laufenden Abruf ab` (Subject-Fake: DAY-Antwort nach WEEK-Antwort ändert nichts)
  - `Sollwert: + fünfmal in 1 s ⇒ genau ein setTarget mit target+2,5 nach 600 ms (fakeAsync)`; `Anzeige folgt sofort dem lokalen Wert`
  - `neues detail-Input während einer offenen −/+-Eingabe setzt den lokalen Wert nicht zurück`
  - `Schalter aus bei confirmOff ⇒ Bestätigungsdialog, erst 'Ausschalten' ruft setSwitch(false)`; `Einschalten ruft direkt setSwitch(true)`; `confirmOff false ⇒ Aus direkt`
  - `Helligkeit: Regler nur bei dimmable, disabled bei on:false; change ⇒ setBrightness(entityId, wert)`; kein Aufruf bei `input`
  - `Fehler beim Schalten ⇒ Meldung 'Gerät nicht erreichbar' im Feld`
  - `on:null ⇒ Schalter disabled`
  - `✕ emittiert closed`
- [ ] **Step 2: Run** ⇒ FAIL.
- [ ] **Step 3: Implement.** Diagramm mit `NgxEchartsDirective`, Optionen als Feld (nicht Methodenaufruf im Template), `connectNulls: false`. Sammelpause 600 ms für −/+. Bestätigungsdialog im Feld selbst.
- [ ] **Step 4: Run** ⇒ PASS.
- [ ] **Step 5: Commit** `feat(house): Detail-Feld mit Verlauf und Bedienung`

---

### Task 5: Einbau in die Hausansicht

**Files:**
- Modify: `frontend/src/app/shared/house/house-geometry.util.ts` (+ `cameraForDevice`), `.spec.ts`
- Modify: `frontend/src/app/pages/tablet-house/tablet-house.component.{ts,html,scss,spec.ts}`
- Modify: `CLAUDE.md` (Abschnitt „Digitaler Zwilling (3D-Hausansicht)“)

**Interfaces:**
- Consumes: alles aus Task 2–4; bestehende `openDevice(bubble)`, `focusedRoom`, `fly`, `deviceAnchors`.
- Produces: `export function cameraForDevice(anchor: Vec3): CameraView` — Ziel = Anker, Position 2,5–4 m schräg davor (Straßenseite +z, oberhalb), innerhalb der Zoom-Grenzen 3–30 m des Renderers.

- [ ] **Step 1: Failing tests:**
  - Geometrie: `cameraForDevice zielt auf den Anker, Abstand 3–5 m, Kamera über dem Anker`
  - Komponente: `Tipp auf Geräte-Bubble (opensDetail) ⇒ flyTo(cameraForDevice(anker)), Feld sichtbar, kein navigateByUrl`
  - `Tipp auf door/waste/pet-Bubble ⇒ weiterhin navigateByUrl(target)`
  - `✕ oder Tipp ins Leere bei offenem Feld ⇒ Feld zu, flyTo(cameraForRoom(raum)), Raumansicht bleibt`
  - `Refresh bei offenem Feld ⇒ detail-Input neu gesetzt, keine Schaltaktion, Feld bleibt offen`
  - `Feld geschlossen, während ein Schaltaufruf läuft ⇒ Antwort danach öffnet nichts, wirft nicht`
  - `Etagenwechsel oder Zurück schließt das Feld`
- [ ] **Step 2: Run** ⇒ FAIL.
- [ ] **Step 3: Implement.** `openDevice`: `opensDetail(kind)` ⇒ `openDetail(deviceId)`, sonst Navigation wie bisher. `openDetail` hält nur die `deviceId` (nicht das Modell) und baut `DeviceDetail` per `computed` aus Entitäten + `now` — Regel aus `petSupplyDialogSupply`. `dimmable`/Helligkeit über `lightDevice(entityId)` und beim Thermostat den Raumwert über `thermostatRoom(id)` (als zusätzliche Zeile „Raum 19,8°“, fehlt ohne Raumfühler) einmal beim Öffnen ergänzen. Der Garten (climate, Außen-Bubble im EG-Überblick) öffnet das Feld ebenfalls; Kamera dann `cameraForDevice` auf seinen Außen-Anker. Test dafür ergänzen: `Tipp auf Garten-Bubble im Überblick ⇒ Feld offen`. Feld als Overlay rechts in `.tablet-house__stage` (Hochformat: unten), Bubbles bleiben positioniert.
- [ ] **Step 4: Run** focused ⇒ PASS; volle Suite = Baseline; `npm run build` grün.
- [ ] **Step 5: CLAUDE.md** — im Zwilling-Abschnitt: Geräte-Bubbles öffnen das Detail-Feld (keine Navigation mehr außer door/waste/pet), Bedienung und Schutzregeln (Frischlesen, Ausschalt-Schutz inkl. laufender Maschine, Regler gesperrt bei aus), sensorId-Zuordnung über `/current` + Serienliste, KIOSK darf dimmen.
- [ ] **Step 6: Commit** `feat(house): Geraete-Detail in der Hausansicht`
- [ ] **Step 7: Nutzer prüft auf dem Wandtablet** (Testserver :4300 mit Proxy auf PROD — Achtung: Schalten und Dimmen wirken dort auf echte Geräte).
