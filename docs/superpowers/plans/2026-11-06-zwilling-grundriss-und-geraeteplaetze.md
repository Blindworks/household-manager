# Digitaler Zwilling Stufe 4: Grundriss und Geräteplätze – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Der Grundriss der 3D-Hausansicht bekommt die abgestimmten Maße und den Balkon. Geräte lassen sich auf einer Admin-Seite an ihren echten Platz ziehen, und das Wandtablet zeichnet Bubble, Effekt und Kamerafahrt dort.

**Architecture:** Eine Tabelle `house_device_position` mit schlanker REST-API speichert je `HouseDevice.id` einen Punkt in Skizzen-Einheiten. Im Frontend ist ein neues Util `shared/house/house-placement.util.ts` die einzige Stelle, die entscheidet, wo ein Gerät steht. Dazu gehören:
- die Gültigkeitsprüfung
- der Rückfall auf das Raster
- die Höhe nach Art

Die Tablet-Seite und die neue Admin-Seite fragen beide dieses Util.

**Tech Stack:** Angular 19 standalone (Signals, OnPush), Karma/Jasmine, Three.js (nur im Renderer), Spring Boot 3.4 / Java 21, Liquibase, MariaDB.

**Spec:** `docs/superpowers/specs/2026-11-06-zwilling-grundriss-und-geraeteplaetze-design.md`

## Global Constraints

- **Einheiten:** `UNITS_PER_METER = 40`, und `HOUSE_BOUNDS` bleibt `{20, 90, 420, 440}`.
- **Höhen über dem Etagenboden:**
  - Lampe (`switch` mit `lamp: true`): 2,3 m.
  - `thermostat`, `contact`, `door`, `camera`, `motion` und `button`: 1,2 m.
  - Alles andere: 0,5 m.
  - Die Raumansicht-Bubble sitzt immer auf 1,2 m.
- **Gültiger Platz:** Der Punkt liegt in einem Rechteck des eigenen Bereichs, der Rand zählt mit. Er liegt nicht in einem Ausschnitt (`cutouts`). Überlappende Außenflächen zählen getrennt.
- **Backend-Prüfung:**
  - `deviceId` muss auf `^[a-z0-9_-]{1,100}$` passen.
  - x und y müssen endlich sein (`Double.isFinite`), Bereich 0–500 inklusive.
  - Gespeichert wird `DECIMAL(6,1)`, gerundet HALF_UP auf eine Nachkommastelle.
- **API:** `/api/v1/house/device-positions` mit `GET`, `PUT /{deviceId}` mit `{x, y}` und `DELETE /{deviceId}`. `DELETE` antwortet immer mit 204. `PUT` und `DELETE` sind methodenspezifische ADMIN-Matcher, das Lesen ist KIOSK über die generische `GET /v1/**`-Regel.
- **Audit:** `house.device-position.update` mit dem Detail `<deviceId> x=<x> y=<y>` und `house.device-position.delete` mit dem Detail `<deviceId>`, jeweils nach dem Erfolg.
- **Changeset:** `20261106-0087-create-house-device-position`, ein DDL. JPA-Repositories liegen in `com.household.manager.repository`.
- **Fehlerverhalten:**
  - Ein Ladefehler auf dem Tablet behält still den letzten Stand. Gab es nie einen, gilt das Raster.
  - Die Admin-Seite zeigt bei einem Ladefehler eine Meldung statt eines leeren Plans.
- **Admin-Seite:** Route `admin/house-devices`, Wurzelklasse `.admin-house-devices` in `$admin-scopes` (`frontend/src/styles.scss`), Header-Eintrag „Geräteplätze“ (ADMIN).
- **Frontend-Tests:** `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`. Die Baseline sind 3 vorbestehende Fehlschläge (AppComponent ×2, HeroComponent). Auch nicht neu ist ein gelegentlicher Flake in SmartDeviceList.
- **Backend-Tests:** `JAVA_HOME` auf jdk-21.0.10 setzen, dann `cd backend && mvn test -Dtest=<Klasse>`. `contextLoads` ist lokal rot (keine DB), das ist erwartet.

## Review Focus

1. **Ungültig gewordener gespeicherter Platz** nach dem Grundriss-Umbau, z. B. ein Punkt in der alten Küche, der jetzt im Esszimmer liegt:
   - Das Gerät fällt zurück aufs Raster.
   - Die Admin-Liste zeigt „Platz liegt außerhalb des Raums“.
   - Test in Task 3 (Util) und Task 5 (Admin-Liste).
2. **Ein Punkt im Gäste-WC-Ausschnitt** ist für ein Flur-Gerät ungültig, für ein Gäste-WC-Gerät aber gültig. Test in Task 3.
3. **Gemischter Bereich**, ein Gerät platziert und der Rest im Raster:
   - Die übrigen bekommen ein Raster mit nur ihrer Anzahl.
   - Der platzierte Punkt belegt keine Rasterzelle mehr.
   - Test in Task 3.
4. **Positions-Abruf scheitert auf dem Tablet:**
   - Der letzte Stand bleibt.
   - Ohne einen Stand gilt das Raster, und es gibt keinen Fehler-Banner.
   - Ein unveränderter Stand löst kein neues `setEffects` aus.
   - Test in Task 4.
5. **Loslassen außerhalb des eigenen Bereichs auf der Admin-Seite:**
   - Es kommt kein PUT.
   - Der Punkt springt zurück.
   - Der Hinweis nennt den Bereich.
   - Test in Task 5.

---

### Task 1: Backend – Tabelle, API, Security

**Files:**
- Create: `backend/src/main/resources/db/changelog/changes/20261106-0087-create-house-device-position.xml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.xml` (include after `20261105-0086`)
- Create: `backend/src/main/java/com/household/manager/model/entity/HouseDevicePosition.java`
- Create: `backend/src/main/java/com/household/manager/repository/HouseDevicePositionRepository.java`
- Create: `backend/src/main/java/com/household/manager/house/HouseDevicePositionDtos.java`
- Create: `backend/src/main/java/com/household/manager/house/HouseDevicePositionService.java`
- Create: `backend/src/main/java/com/household/manager/house/HouseDevicePositionController.java`
- Modify: `backend/src/main/java/com/household/manager/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/household/manager/house/HouseDevicePositionServiceTest.java`, `.../house/HouseDevicePositionControllerTest.java`, `.../security/SecurityRulesTest.java`

**Interfaces:**
- Produces (HTTP, verwendet in Task 4/5):
  - `GET /api/v1/house/device-positions` → `[{ "deviceId": string, "x": number, "y": number }]`, sortiert nach `deviceId`
  - `PUT /api/v1/house/device-positions/{deviceId}` mit Body `{ "x": number, "y": number }` → `200 { deviceId, x, y }` (gespeicherte, gerundete Werte)
  - `DELETE /api/v1/house/device-positions/{deviceId}` → `204`
  - Fehler: 400 bei ungültiger Id oder Koordinate (Body fehlt, `null`, nicht endlich, <0, >500)

- [ ] **Step 1: Changeset + Entity + Repository**
  - Die Tabelle `house_device_position` hat die Spalten `device_id VARCHAR(100)` (PK), `plan_x DECIMAL(6,1)` und `plan_y DECIMAL(6,1)` (beide NOT NULL) sowie `updated_at DATETIME` (NOT NULL), dazu ein Rollback mit `dropTable`.
  - Entity mit Lombok im Muster von `ModeQuickAccess`. Felder: `String deviceId` (`@Id`), `BigDecimal planX`, `BigDecimal planY`, `LocalDateTime updatedAt`.
  - `HouseDevicePositionRepository extends JpaRepository<HouseDevicePosition, String>` mit `List<HouseDevicePosition> findAllByOrderByDeviceIdAsc()`.

- [ ] **Step 2: Failing service tests** (Mockito, Repository und `AuditService` gemockt, fester `Clock`)
  - `setSpeichertGerundetUndAuditiert`: `set("wohnzimmer-climate", 123.46, 200.04)` speichert 123.5 und 200.0. `updatedAt = LocalDateTime.now(clock)`. Die Antwort trägt 123.5 und 200.0. Audit `("house.device-position.update", "wohnzimmer-climate x=123.5 y=200.0")`.
  - `setUeberschreibtVorhandeneZeile`: Ein vorhandener Eintrag wird aktualisiert, nicht dupliziert.
  - `setLehntUngueltigeIdAb`: Für `""`, `"Wohnzimmer"`, `"a b"`, `"x/y"` und 101 × `"a"` kommt `IllegalArgumentException`. Es wird nicht gespeichert, und es gibt kein Audit.
  - `setLehntUngueltigeKoordinatenAb`: Für `null`, `NaN`, `Infinity`, `-0.1` und `500.1` kommt `IllegalArgumentException`. Die Grenzwerte `0` und `500` sind erlaubt.
  - `deleteLoeschtUndAuditiert`: `deleteById`, Audit `("house.device-position.delete", "kueche-motion")`.
  - `deleteOhneZeileWirftNicht`: Eine fehlende Zeile wird nicht gelöscht und wirft nicht. Audit nur, wenn eine Zeile existierte.
  - `listLiefertAlleSortiert`: liefert die Ergebnisse von `findAllByOrderByDeviceIdAsc`.

- [ ] **Step 3: Run tests** – `mvn test -Dtest=HouseDevicePositionServiceTest`. Erwartet: Kompilier- oder Testfehler.

- [ ] **Step 4: Implement `HouseDevicePositionService`**
  - Methoden: `List<Response> list()`, `Response set(String deviceId, Double x, Double y)` und `void delete(String deviceId)`.
  - `@Transactional` für die Schreibmethoden, `readOnly` für `list`.
  - DTO-Records in `HouseDevicePositionDtos`: `Request(Double x, Double y)` und `Response(String deviceId, double x, double y)` mit `static from(HouseDevicePosition)`.

- [ ] **Step 5: Controller + Controller-Test**
  - `@RestController @RequestMapping("/v1/house/device-positions")`. Ein PUT ohne Body oder mit `x: null` ergibt 400.
  - Der `@WebMvcTest` prüft drei Fälle:
    - GET liefert die JSON-Liste.
    - PUT ruft `set` mit Pfad-Id und Werten.
    - DELETE liefert 204.
  - Eine `IllegalArgumentException` aus dem Service ergibt 400 (über den `GlobalExceptionHandler`).

- [ ] **Step 6: Security**
  - In `SecurityConfig` vor den generischen Regeln eintragen: `.requestMatchers(HttpMethod.PUT, "/v1/house/device-positions/*").hasRole("ADMIN")` und dasselbe für `DELETE`.
  - Tests in `SecurityRulesTest` im vorhandenen Stil (404 heißt hier: die Regel lässt durch):
    - `kioskDarfGeraeteplaetzeLesen` (GET ergibt 404, nicht 403)
    - `memberDarfGeraeteplatzNichtSetzen` (PUT ergibt 403)
    - `memberDarfGeraeteplatzNichtLoeschen` (DELETE ergibt 403)
    - `adminDarfGeraeteplatzSetzen` (PUT ergibt 404)
    - `kioskDarfGeraeteplatzNichtSetzen` (PUT ergibt 403)

- [ ] **Step 7: Run** `mvn test -Dtest='HouseDevicePosition*Test,SecurityRulesTest'`. Erwartet: PASS.

- [ ] **Step 8: Commit** `feat(house): Geraeteplaetze speichern (Tabelle, API, ADMIN-Schreibzugriff)`

---

### Task 2: Grundriss mit neuen Maßen und Balkon

**Files:**
- Modify: `frontend/src/app/shared/house/house-plan.ts`
- Modify: `frontend/src/app/shared/house/house-geometry.util.ts` (`areaFootprint`, `areOutdoorAreasVisible`)
- Modify: `frontend/src/app/pages/tablet-house/house-scene-renderer.ts` (`buildOutdoorAreas`, Sichtbarkeit)
- Modify: `frontend/src/app/pages/tablet-house/tablet-house.component.ts` (Bereichsliste der Effekte, Zeile ~154)
- Test: `house-plan.spec.ts`, `house-geometry.util.spec.ts`, gegebenenfalls `tablet-house.component.spec.ts`

**Interfaces:**
- Produces:
  - `OutdoorArea { id: 'garden' | 'terrace' | 'front' | 'balcony'; label: string; floor: FloorId; rect: PlanRect }`
  - `outdoorAreasOn(floor: FloorId): OutdoorArea[]` (in `house-plan.ts`)
  - `areaFloor(areaId: string): FloorId | null` (in `house-plan.ts`; Raum oder Außenbereich, unbekannt ist `null`)
  - `areaFloorBase(areaId: string): number` (in `house-geometry.util.ts`; Etagenboden in Metern, unbekannt ergibt 0)
  - `areOutdoorAreasVisible(selected)` entfällt. An seine Stelle tritt `outdoorAreasOn(selected)`.

- [ ] **Step 1: Failing tests in `house-plan.spec.ts`**
  - Die Rechtecke sind exakt so gesetzt:
    - esszimmer `(20,90,210,240)`
    - kueche `(20,240,210,350)`
    - wohnzimmer `(210,90,420,270)`
    - flur `(210,270,420,440)`, Ausschnitt `(210,370,290,440)`
    - schlafzimmer `(20,90,420,240)`
    - buero `(20,240,250,340)`
    - bad `(20,340,250,440)`
    - treppenhaus `(250,240,420,440)`
    - vorratskeller `(20,90,210,280)`
    - waschkeller `(20,280,210,440)`
  - Unverändert bleiben laufband, gaeste_wc, paincave und kellereingang sowie garden, terrace und front.
  - Der Balkon ist `{ id: 'balcony', label: 'Balkon', floor: 'upper', rect: (130,40,310,90) }`.
  - `outdoorAreasOn('ground')` ergibt garden, terrace und front. `outdoorAreasOn('upper')` ergibt nur den Balkon. `outdoorAreasOn('basement')` ergibt `[]`.
  - `areaFloor('balcony') === 'upper'`, `areaFloor('bad') === 'upper'` und `areaFloor('xyz') === null`.
  - Ein bestehender Test, dass die Räume je Etage `HOUSE_BOUNDS` ohne Überlappung abdecken, falls vorhanden, muss mit den neuen Werten grün bleiben.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement in `house-plan.ts`**, dazu `areaFloorBase` in der Geometrie. `areaFootprint` nutzt für Außenbereiche jetzt `floorBase(area.floor)` statt 0.

- [ ] **Step 4: Renderer.** Jeder Außenumriss liegt auf `floorBase(area.floor) + PLATE_LIFT_M` und ist nur sichtbar, wenn `area.floor` die gewählte Etage ist. Ein Weg dahin: der Umriss gehört in die Layer-Gruppe seiner Etage. Der Renderer wird nicht getestet (WebGL).
  - Im Component-Code ersetzt `outdoorAreasOn(floor).map(a => a.id)` den `floor === 'ground'`-Ausdruck für die Effekt-Bereiche.
  - `buildOutdoorBubbles` bleibt EG-only, der Balkon hat keine Geräte.

- [ ] **Step 5: Run all frontend tests.** Erwartet: nur die Baseline-Fehlschläge. Bestehende Tests mit fest kodierten alten Rechteck- oder Ankerwerten aus `house-geometry.util.spec.ts` und `house-effects.util.spec.ts` auf die neuen Maße umstellen, nicht löschen.

- [ ] **Step 6: Commit** `feat(house): Grundriss nachgeschaerft, Balkon im Obergeschoss`

---

### Task 3: Platz-Logik (eine Definition)

**Files:**
- Create: `frontend/src/app/shared/house/house-placement.util.ts`
- Create: `frontend/src/app/shared/house/house-placement.util.spec.ts`
- Modify: `frontend/src/app/shared/house/house-effects.util.ts` (`deviceAnchor` entfernen, `buildHouseEffects` bekommt Positionen, `floorY` aus `areaFloorBase`)
- Modify: `frontend/src/app/shared/house/house-effects.util.spec.ts`
- Modify: `frontend/src/app/pages/tablet-house/tablet-house.component.ts`: Aufrufe kompilierbar halten, vorerst mit leerer Map. Die echte Anbindung folgt in Task 4.

**Interfaces:**
- Consumes: `areaFloorBase` (Task 2), `deviceAnchors(areaId, count)`, `toWorldX`, `toWorldZ` und `insideCutout` (bestehend), `devicesInArea` und `HOUSE_DEVICES` (bestehend)
- Produces (Task 4/5):
  ```ts
  export interface PlanPoint { x: number; y: number }
  export type DevicePositions = ReadonlyMap<string, PlanPoint>;
  export const LAMP_HEIGHT_M = 2.3;
  export const WALL_HEIGHT_M = 1.2;
  export const LOW_HEIGHT_M = 0.5;
  export const BUBBLE_HEIGHT_M = 1.2;
  export function deviceHeightM(device: HouseDevice): number;
  /** In einem Rechteck des Bereichs (Rand inklusive), nicht in einem Ausschnitt; unbekannter Bereich: false. */
  export function isPointInArea(areaId: string, point: PlanPoint): boolean;
  /** Gespeicherter Punkt, falls gültig für den Bereich des Geräts, sonst null. */
  export function validPlacement(device: HouseDevice, positions: DevicePositions): PlanPoint | null;
  /** Anker für Effekt und Kamera: x/z aus gültigem Platz oder Raster der ungeplatzten Geräte, y = Etagenboden + deviceHeightM. */
  export function deviceAnchor(deviceId: string, positions: DevicePositions): Vec3 | null;
  /** Wie deviceAnchor, aber y = Etagenboden + BUBBLE_HEIGHT_M. */
  export function deviceBubbleAnchor(deviceId: string, positions: DevicePositions): Vec3 | null;
  export function toPositionMap(list: readonly { deviceId: string; x: number; y: number }[]): DevicePositions;
  export function samePositions(a: DevicePositions, b: DevicePositions): boolean;
  ```
  - `buildHouseEffects(areaIds, entities, brightness, positions: DevicePositions)`. `HouseEffect.floorY` ist `areaFloorBase(device.area)`.

- [ ] **Step 1: Failing tests in `house-placement.util.spec.ts`**
  - `deviceHeightM`: Lampe (`flur` Licht) 2.3, Thermostat 1.2, Kontakt 1.2, Haustür 1.2, Kamera 1.2, Bewegungsmelder 1.2, Taster 1.2, Raumklima 0.5, Fernseher (Schalter ohne Lampe) 0.5, Spülmaschine 0.5, Mülltonne 0.5, Toni 0.5.
  - `isPointInArea`:
    - `('bad', {x:20,y:340})` und `{x:250,y:440}` sind true (Rand).
    - `('bad', {x:251,y:400})` ist false.
    - `('flur', {x:250,y:400})` ist false (Ausschnitt), `('gaeste_wc', {x:250,y:400})` ist true.
    - `('garden', {x:300,y:87})` ist false (nur Terrasse), `('terrace', {x:300,y:87})` ist true.
    - `('unbekannt', …)` ist false.
  - `validPlacement`:
    - Ein gültiger Punkt kommt zurück.
    - Ein Punkt im falschen Raum ergibt null, z. B. der Kühlschrank (`kueche`) bei `{x:100,y:150}` (Esszimmer).
    - Ein Gerät ohne Eintrag ergibt null.
  - `deviceAnchor`:
    - Platziert: x = `toWorldX(px)`, z = `toWorldZ(py)`, y = `floorBase('upper') + 2.3` für das Schlafzimmer-Licht.
    - Gemischt: Im Bad ist ein Gerät platziert, die beiden übrigen haben die Anker von `deviceAnchors('bad', 2)` in der Reihenfolge von `devicesInArea` (nur x/z vergleichen).
    - Ohne Positionen: x/z gleich `deviceAnchors(area, n)[index]`.
    - Unbekannte Id ergibt null.
  - `deviceBubbleAnchor`: gleiches x/z wie `deviceAnchor`, y = Etagenboden + 1.2.
  - `samePositions`:
    - Gleiche Einträge in anderer Reihenfolge sind true.
    - Abweichender Wert ist false.
    - Andere Größe ist false.
  - `toPositionMap` bildet die Liste auf eine Map ab.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement.** Für das Raster werden zuerst die Geräte des Bereichs ohne gültigen Platz in der Reihenfolge von `devicesInArea` bestimmt. Dann kommen `deviceAnchors(area, unplaced.length)` zum Zug, nur x/z werden übernommen.

- [ ] **Step 4: Effekte umstellen.**
  - `buildHouseEffects` nutzt `deviceAnchor(id, positions)`.
  - `floorY = areaFloorBase(device.area)`.
  - Tests in `house-effects.util.spec.ts` anpassen und ergänzen: `effectFloorYIstEtagenboden`, z. B. OG-Effekt `floorY === floorBase('upper')`, unabhängig von der Ankerhöhe.
  - `deviceAnchor` existiert danach nur noch in `house-placement.util.ts`.

- [ ] **Step 5: Run all frontend tests.** Erwartet: nur die Baseline-Fehlschläge.

- [ ] **Step 6: Commit** `feat(house): Geraeteplatz-Logik mit Hoehe nach Art`

---

### Task 4: Tablet-Seite und Renderer nutzen die Plätze

**Files:**
- Create: `frontend/src/app/models/house-device-position.model.ts` (`export interface HouseDevicePosition { deviceId: string; x: number; y: number }`)
- Create: `frontend/src/app/services/house-device-position.service.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/pages/tablet-house/tablet-house.component.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/pages/tablet-house/house-scene-renderer.ts` (`addEffect`)

**Interfaces:**
- Consumes: `DevicePositions`, `deviceAnchor`, `deviceBubbleAnchor`, `toPositionMap`, `samePositions` (Task 3); API aus Task 1
- Produces: `HouseDevicePositionService` (`providedIn: 'root'`, Basis-URL `/api/v1/house/device-positions`) mit:
  - `list(): Observable<HouseDevicePosition[]>`
  - `set(deviceId: string, point: PlanPoint): Observable<HouseDevicePosition>` (PUT mit `{x, y}`)
  - `remove(deviceId: string): Observable<void>`

- [ ] **Step 1: Service-Test (HttpTestingController)**
  - Prüft die URLs, die Methoden und die Bodies der drei Aufrufe.
  - Die Geräte-Id wird mit `encodeURIComponent` in den Pfad gesetzt.

- [ ] **Step 2: Failing component tests in `tablet-house.component.spec.ts`**, mit dem Service-Fake im vorhandenen Stil:
  - `nutztGespeichertenPlatzFuerBubbleUndEffekt`: Die Liste setzt das Flur-Licht auf `{x:400,y:300}`. Die Bubble der Raumansicht `flur` hat dann `anchor.x === toWorldX(400)` und y = 1,2 m. Das Effekt-Objekt (Licht an) hat `anchor.x === toWorldX(400)`.
  - `positionsFehlerOhneStandNutztRaster`: Der erste Abruf wirft. Die Anker entsprechen dann dem Raster, und es gibt keinen Fehlertext auf der Seite.
  - `positionsFehlerBehaeltLetztenStand`: Erst Erfolg, dann ein Fehler im nächsten Takt. Der Platz bleibt.
  - `unveraenderterStandLoestKeinSetEffectsAus`: Zwei Takte liefern dieselbe Liste bei gleichen Entitäten. `setEffects` des Renderer-Fakes wird nach dem ersten Mal nicht erneut gerufen. Dafür `samePositions` vor `positions.set` nutzen.
  - `geraeteDetailFaehrtZumGespeichertenPlatz`: `cameraForDevice` bekommt den `deviceAnchor`, also auf Ankerhöhe, nicht auf Bubble-Höhe.

- [ ] **Step 3: Run, verify FAIL.**

- [ ] **Step 4: Implement**
  - `positions = signal<DevicePositions>(new Map())`. Die Liste lädt im selben `ticks`-Strom wie die Helligkeit: `catchError(() => of(null))`, bei `null` bleibt der Stand.
  - `placeByArea` nutzt `deviceBubbleAnchor(id, positions)`.
  - `effects` übergibt `positions()`.
  - Die Kamerafahrt beim Öffnen des Details nutzt `deviceAnchor(device.id, positions())` statt des Bubble-Ankers.

- [ ] **Step 5: Renderer `addEffect`.** Der hängende Teil sitzt auf `effect.anchor.y` statt auf `floorY + 0.35/0.3/0.4/0.6`: Glühbirne und Sprite bei `light` und `powered`, Sprite bei `heating` und `open`. Der Bodenschein (`addFloorGlow`), die Bewegungsringe und der Maschinenbogen bleiben auf `floorY + EFFECT_LIFT_M`. Nicht getestet (WebGL).

- [ ] **Step 6: Run all frontend tests.** Erwartet: nur die Baseline-Fehlschläge.

- [ ] **Step 7: Commit** `feat(house): Tablet zeigt Geraete an ihrem gespeicherten Platz`

---

### Task 5: Admin-Seite „Geräteplätze“ und Doku

**Files:**
- Create: `frontend/src/app/pages/admin-house-devices/admin-house-devices.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/app.routes.ts` (Route `admin/house-devices`, `adminGuard`, Titel `Geräteplätze - Household Manager`, vor `admin`)
- Modify: `frontend/src/app/components/header/header.component.ts` (Eintrag `{ path: '/admin/house-devices', label: 'Geräteplätze', minRole: 'ADMIN' }` neben „Modus-Schnellzugriff“)
- Modify: `frontend/src/styles.scss` (`.admin-house-devices` an `$admin-scopes` anhängen)
- Modify: `CLAUDE.md` (Abschnitt „Digitaler Zwilling“)

**Interfaces:**
- Consumes: `HouseDevicePositionService` (Task 4), `isPointInArea`, `validPlacement`, `toPositionMap` und `PlanPoint` (Task 3), `HOUSE_ROOMS`, `outdoorAreasOn`, `FLOORS`, `roomById` und `OUTDOOR_AREAS` (Task 2), `HOUSE_DEVICES`

**Verhalten (verbindlich):**
- **Reiter:** Keller, Erdgeschoss, Obergeschoss; Start auf Erdgeschoss. Gezeigt werden die Räume der Etage plus `outdoorAreasOn(floor)` als SVG mit `viewBox="0 0 440 490"` und Namen.
- **Geräte der Etage:** alle `HOUSE_DEVICES`, deren `area` ein Raum dieser Etage oder ein Außenbereich aus `outdoorAreasOn(floor)` ist.
  - Mit `validPlacement` erscheinen sie als Kreis in der Farbe ihrer Art: Lampe `#ffd36b`, Thermostat `#ff9a4d`, Fühler (`climate`) `#6be0a0`, Kontakt und Haustür `#ff6b6b`, sonstiger Schalter und Maschine `#8fb4ff`, alles andere `#c9a6ff`.
  - Die Beschriftung ist `device.label`. Kommt das Label auf der Etage mehrfach vor, lautet sie `"<label> · <Bereichsname>"`.
- **Liste „Noch ohne Platz“:** Geräte der Etage ohne gültigen Platz, gruppiert nach Bereichsname. Hat ein Gerät einen gespeicherten, aber ungültigen Platz, steht darunter „Platz liegt außerhalb des Raums“.
- **Ziehen:**
  - Per Pointer Events mit `setPointerCapture`. Client-Koordinaten werden über `getScreenCTM().inverse()` in Plan-Einheiten umgerechnet.
  - Ein Listengerät startet das Ziehen per `pointerdown` auf dem Listeneintrag. Sein Punkt erscheint unter dem Finger.
  - Gespeichert wird nur beim Loslassen und auf eine Nachkommastelle gerundet. Liegt der Punkt in `isPointInArea(device.area, p)`, folgt `set` (PUT), danach steht der Platz aus der Antwort in der Map.
  - Sonst kommt kein PUT. Der Punkt springt auf den alten Stand zurück, und ein Hinweis erscheint: `„<label>“ gehört in: <Bereichsname>`.
- **Tippen statt Ziehen:** Ein Tipp auf einen Listeneintrag (Weg < 8 px) markiert ihn. Der nächste Tipp in den Plan setzt ihn an diese Stelle, mit denselben Regeln.
- **Zurücksetzen:** Ein Tipp auf einen platzierten Punkt (Weg < 8 px) markiert ihn und zeigt den Knopf „Platz zurücksetzen“. Der ruft `remove`, und das Gerät wandert in die Liste.
- **Fehler:**
  - Speichern oder Zurücksetzen scheitert: Meldung „Speichern fehlgeschlagen.“, der Punkt bleibt auf dem letzten gespeicherten Stand.
  - Laden scheitert: statt Plan und Liste nur „Geräteplätze konnten nicht geladen werden.“ mit einem Knopf „Neu laden“.
- Einträge zu unbekannten Geräte-Ids werden ignoriert.

- [ ] **Step 1: Failing component tests** mit Service-Fake und `PointerEvent`s, `getScreenCTM` per Spy auf eine Einheitsmatrix:
  - `zeigtStandardmaessigErdgeschossMitAussenbereichen`: Die Raum- und Bereichsnamen Esszimmer, Garten und Vor dem Haus sind sichtbar, Balkon nicht.
  - `obergeschossZeigtBalkon`
  - `platziertesGeraetErscheintAlsPunktUnplatziertesInDerListe`
  - `ungueltigerPlatzStehtMitVermerkInDerListe`: Der Kühlschrank liegt bei `{x:100,y:150}`. Er steht dann in der Liste mit „Platz liegt außerhalb des Raums“, nicht als Punkt.
  - `loslassenImEigenenBereichSpeichert`: Ein Listengerät wird in seinen Raum gezogen. Das ergibt `set(id, {x, y})` mit gerundeten Werten, danach ist es ein Punkt.
  - `loslassenAusserhalbSpeichertNichtUndZeigtHinweis`: kein `set`, Hinweis mit Bereichsname.
  - `tippenUndTippenSetztGeraet`
  - `zuruecksetzenRuftRemoveUndListetGeraet`
  - `speicherfehlerZeigtMeldungUndBehaeltStand`
  - `ladefehlerZeigtMeldungStattLeeremPlan`
  - `doppelteLabelsTragenBereichsnamen`: Im EG gibt es mehrere „Raumklima“, die Beschriftung enthält den Bereichsnamen.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement** die Komponente (standalone, OnPush, Signals, Template und SCSS getrennt, Trefferradius ≥ 12 Plan-Einheiten), dazu Route, Header und `$admin-scopes`.

- [ ] **Step 4: Run all frontend tests.** Erwartet: nur die Baseline-Fehlschläge. Dazu `ng build --configuration production`. Das bekannte Budget-ERROR der Dashboard-SCSS ist keine Regression, neue Fehler sind es schon.

- [ ] **Step 5: CLAUDE.md**, Abschnitt „Digitaler Zwilling“, mit einem neuen Unterpunkt „Grundriss und Geräteplätze (seit 2026-11-06)“. Inhalt:
  - die neuen Maße und der Balkon (`OutdoorArea.floor`)
  - die Tabelle und die API samt Security und Audit, Changeset 0087 nie gegen die echte DB gelaufen
  - `house-placement.util.ts` als einzige Definition, mit Höhe nach Art und Bubble auf 1,2 m
  - Rückfall aufs Raster bei ungültigem Platz, z. B. nach einem Grundriss-Umbau
  - Admin-Seite `admin/house-devices`
  - Tablet nur lesend

  In der Aufzählung der Grenzen entfällt „Balkon nicht platziert“ und „Maße geschätzt“, sie wird stattdessen auf den neuen Stand gebracht.

- [ ] **Step 6: Commit** `feat(house): Admin-Seite Geraeteplaetze` und `docs(house): Grundriss und Geraeteplaetze in CLAUDE.md`
