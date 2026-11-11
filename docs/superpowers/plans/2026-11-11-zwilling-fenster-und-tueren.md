# Digitaler Zwilling: Fenster und Türen – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Echte Fenster und Türen erscheinen als Glasfläche bzw. Türblatt in den Wänden der 3D-Hausansicht. Sie werden auf der Admin-Seite „Geräteplätze“ gepflegt, und ein Element mit verknüpftem Sensor schwenkt bei „offen“ rot auf.

**Architecture:**
- Backend: Tabelle `house_opening` mit REST-API (Muster `house/HouseDevicePosition*`).
- Frontend: Das neue reine Util `shared/house/house-openings.util.ts` ist die einzige Definition von Größen, Wänden, Einrasten, Seiten, Überlappung und 3D-Geometrie.
- Renderer, Tablet-Seite und Admin-Seite fragen alle dieses Util.

**Tech Stack:** Angular 19 standalone (Signals, OnPush), Karma/Jasmine, Three.js nur im Renderer, Spring Boot 3.4 / Java 21, Liquibase, MariaDB.

**Spec:** `docs/superpowers/specs/2026-11-11-zwilling-fenster-und-tueren-design.md`

## Global Constraints

- **Größen** (Breite / Unterkante / Oberkante, Meter über dem Etagenboden):
  - Fenster `SMALL` 0,6 / 0,9 / 2,1
  - Fenster `NORMAL` 1,0 / 0,9 / 2,1
  - Fenster `WIDE` 1,6 / 0,9 / 2,1
  - Tür `NORMAL` 0,9 / 0 / 2,1
  - Tür `TERRACE` 1,8 / 0 / 2,1, zwei Flügel mit Anschlag an beiden Enden
  - Plan-Einheiten: `UNITS_PER_METER` = 40, also Breiten 24 / 40 / 64 / 36 / 72.
- Größen gehören zur Art: Fenster nur `SMALL`/`NORMAL`/`WIDE`, Tür nur `NORMAL`/`TERRACE`.
- **Strecke:** immer waagerecht oder senkrecht. Gespeichert normalisiert, sodass `(x1,y1)` der kleinere Endpunkt ist (links bzw. oben). Länge = Breite der Größe.
- **`hinge`** (`START` = Anschlag an `(x1,y1)`, `END` = an `(x2,y2)`) und **`swing`** (`POSITIVE` = Aufschwenken nach +y bzw. +x, `NEGATIVE` = nach −y bzw. −x).
- **Offen:** Drehung um 60° um die Anschlagkante zur Seite von `swing`. Fläche und Rahmen sind rot. Geschlossen gilt immer bei fehlendem, unbekanntem oder nicht `on` gemeldetem Sensor (`isOn`-Regel).
- **Wände:** Vereinigung der Kanten aller Raumrechtecke einer Etage. Kollineare Kanten werden verschmolzen. Außenbereiche sind keine Wand. Außenwand = auf genau einer Seite der Streckenmitte liegt ein Raum, Trennwand = auf beiden.
- **Standard-`swing`:** an einer Außenwand die Seite mit Raum, an einer Trennwand die Seite des Raums, der in `HOUSE_ROOMS` zuerst steht.
- **Verknüpfbare Sensoren:** `kind: 'contact'` mit `isWindowContact` sowie `kind: 'door'` (offen über `contactEntityId`). Je Sensor höchstens ein Element. Das Backend erzwingt das mit einem Unique-Constraint und antwortet sonst mit 409.
- **Verknüpfter Kontakt/Haustür:** erzeugt keinen `open`-Effekt mehr. Bubbles und Flags bleiben unverändert.
- **Neues Gerät in `house-devices.ts`:** `contact('schlafzimmer', 'Balkontür', 'binary_sensor.dirigera_f3992ae92ca54fb6b276c2c4d5f832c9_1_contact')`.
- **API** `/api/v1/house/openings`: `GET`, `POST`, `PUT /{id}`, `DELETE /{id}` (unbekannte Id ergibt 404).
  - Lesen ist KIOSK über die generische `GET /v1/**`-Regel. `POST`, `PUT` und `DELETE` sind methodenspezifische ADMIN-Matcher.
  - Audit `house.opening.create/update/delete` nach Erfolg.
  - Koordinaten endlich und im Bereich 0–500. `device_id` wie bei den Geräteplätzen: `^[a-z0-9_-]{1,100}$` oder `null`.
- **Changeset:** eine Datei, ein DDL, Nummer = die nächste nach der höchsten in `db.changelog-master.xml`. JPA-Repositories liegen in `com.household.manager.repository`.
- **Fehlerverhalten:**
  - Tablet: Ein Ladefehler behält still den letzten Stand. Ohne einen Stand gibt es keine Elemente.
  - Admin: Ein Ladefehler zeigt eine Meldung statt eines leeren Plans. Ein Speicherfehler zeigt eine Meldung, der letzte gespeicherte Stand bleibt.
- **Frontend-Tests:** `cd frontend; npm test -- --watch=false --browsers=ChromeHeadless`. Baseline: 3 vorbestehende Fehlschläge (AppComponent ×2, HeroComponent), dazu gelegentlich der SmartDeviceList-Flake.
- **Backend-Tests:** `$env:JAVA_HOME` auf jdk-21.0.10 setzen, dann `mvn test -Dtest=…`. `contextLoads` ist lokal rot, das ist erwartet.

## Review Focus

1. **Element über zwei Raumkanten auf derselben Linie**, z. B. die Außenwand an der Grenze Esszimmer/Küche: gilt als gültig und rastet ein. Test in Task 2.
2. **Tippen nahe einer Wand, deren Teilstück kürzer ist als die Elementbreite** (kurzes Mauerstück): kein Element, Hinweis statt eines halb überstehenden Elements. Test in Task 2 (`snapToWall` ergibt `null`) und Task 4 (Hinweis).
3. **Grundriss geändert, Element liegt auf keiner Wand mehr:** die Szene zeichnet es nicht, die Admin-Seite listet es unter „Liegt auf keiner Wand“. Test in Task 3 und Task 4.
4. **Zwei Admin-Tabs verknüpfen denselben Sensor:** das Backend antwortet mit 409, die Seite zeigt „Speichern fehlgeschlagen.“, der alte Stand bleibt. Test in Task 1 und Task 4.
5. **Sensor `unavailable`:** das Element bleibt geschlossen, und auch das rote Glühen des verknüpften Kontakts erscheint nicht. Test in Task 3.

---

### Task 1: Backend – Tabelle, API, Security

**Files:**
- Create: Changeset `backend/src/main/resources/db/changelog/changes/<datum>-<nr>-create-house-opening.xml` + Include in `db.changelog-master.xml`
- Create: `model/entity/HouseOpening.java`, `repository/HouseOpeningRepository.java`, `house/HouseOpeningDtos.java`, `house/HouseOpeningService.java`, `house/HouseOpeningController.java`
- Modify: `security/SecurityConfig.java` (neben den `house/device-positions`-Matchern, Zeilen ~232–233)
- Test: `house/HouseOpeningServiceTest.java`, `house/HouseOpeningControllerTest.java`, `security/SecurityRulesTest.java`

**Interfaces:**
- Produces (HTTP): `GET /api/v1/house/openings` → `[{id, floor, kind, size, x1, y1, x2, y2, hinge, swing, deviceId}]` sortiert nach `id`; `POST` (Body ohne `id`) → 201 mit Element; `PUT /{id}` (Body ohne `id`) → 200; `DELETE /{id}` → 204; 400 bei ungültigen Werten, 404 unbekannte Id, 409 `deviceId` schon vergeben.
- `floor` als Text `basement|ground|upper`, die übrigen Enums als Text in Großbuchstaben.

- [ ] **Step 1: Changeset + Entity + Repository**
  - Tabelle `house_opening` mit den Spalten:
    - `id BIGINT` autoincrement PK
    - `floor VARCHAR(16)`, `kind VARCHAR(16)`, `size VARCHAR(16)`
    - `x1`, `y1`, `x2`, `y2` als `DECIMAL(6,1)`
    - `hinge VARCHAR(8)`, `swing VARCHAR(8)`
    - `device_id VARCHAR(100)` NULL mit Unique-Constraint `uk_house_opening_device`
    - `updated_at DATETIME`
    - alle übrigen Spalten NOT NULL, Rollback per `dropTable`
  - Kein `incrementBy` bei autoIncrement (MariaDB-Falle).
  - Enums Java-seitig: `HouseOpeningKind`, `HouseOpeningSize`, `HouseOpeningHinge`, `HouseOpeningSwing`, gespeichert per `@Enumerated(STRING)`. `floor` als String.

- [ ] **Step 2: Failing Service-Tests** (Mockito, fester `Clock`)
  - `createNormalisiertStreckeUndAuditiert`: Eingabe `x1=100,y1=90,x2=60,y2=90` (Fenster `NORMAL`, Länge 40). Gespeichert wird `x1=60,x2=100`. Audit `("house.opening.create", "<id> WINDOW NORMAL ground")`.
  - `lehntSchraegeStreckeAb`: `x1=60,y1=90,x2=100,y2=95` ergibt `IllegalArgumentException`.
  - `lehntFalscheLaengeAb`: Fenster `NORMAL` mit Länge 39 ergibt `IllegalArgumentException`. Gleiche Prüfung für alle fünf Größen per Tabelle (24/40/64/36/72), Toleranz 0,05.
  - `lehntGroesseZurFalschenArtAb`: Tür `SMALL` und Fenster `TERRACE` ergeben `IllegalArgumentException`.
  - `lehntUngueltigeWerteAb`: `floor "attic"`, Koordinate `NaN`, `-1` und `500.1`, `deviceId "Bad Fenster"` ergeben jeweils `IllegalArgumentException`. Es wird nicht gespeichert und nicht auditiert.
  - `vergebeneDeviceIdErgibtKonflikt`: Das Repository findet ein anderes Element mit derselben `deviceId`, das ergibt `DuplicateEntityException` (das vorhandene 409-Mapping im Projekt). Beim `update` desselben Elements mit seiner eigenen `deviceId` gibt es keinen Konflikt.
  - `updateUnbekannteIdErgibt404` (`ResourceNotFoundException`), `deleteUnbekannteIdErgibt404`, `deleteLoeschtUndAuditiert` (Audit `("house.opening.delete", "<id>")`), `updateAuditiert` (`"house.opening.update"`).

- [ ] **Step 3: Run** `mvn test -Dtest=HouseOpeningServiceTest`. Erwartet: FAIL.

- [ ] **Step 4: Implement** `HouseOpeningService` mit `list()`, `create(Request)`, `update(long id, Request)` und `delete(long id)`, dazu die DTO-Records `Request` und `Response`. Koordinaten werden HALF_UP auf eine Nachkommastelle gerundet.

- [ ] **Step 5: Controller + Controller-Test** (`@WebMvcTest`): GET-Liste, POST → 201, PUT → 200, DELETE → 204; `IllegalArgumentException` → 400.

- [ ] **Step 6: Security**
  - Matcher: `.requestMatchers(HttpMethod.POST, "/v1/house/openings").hasRole("ADMIN")`, dazu `PUT` und `DELETE` auf `"/v1/house/openings/*"`.
  - Tests in `SecurityRulesTest` im Stil der device-positions-Tests:
    - KIOSK GET ergibt 404, ist also nicht verboten.
    - KIOSK POST ergibt 403, MEMBER POST, PUT und DELETE ergeben 403.
    - ADMIN POST ergibt 404.

- [ ] **Step 7: Run** `mvn test -Dtest='HouseOpening*Test,SecurityRulesTest'`. Erwartet: PASS.

- [ ] **Step 8: Commit** `feat(house): Fenster und Tueren speichern (Tabelle, API, ADMIN-Schreibzugriff)`

---

### Task 2: Util, Modell, Service, Balkontür

**Files:**
- Create: `frontend/src/app/shared/house/house-openings.util.ts` (+ `.spec.ts`)
- Create: `frontend/src/app/models/house-opening.model.ts`
- Create: `frontend/src/app/services/house-opening.service.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/shared/house/house-devices.ts` (Balkontür im Abschnitt Schlafzimmer), `house-devices.spec.ts` falls Gerätezahlen geprüft werden

**Interfaces:**
- Consumes: `HOUSE_ROOMS`, `FloorId`, `PlanRect`, `UNITS_PER_METER` (house-plan), `toWorldX/Z`, `floorBase`, `Vec3` (house-geometry), `isPointInArea`, `PlanPoint` (house-placement), `HOUSE_DEVICES`, `HouseDevice` (house-devices), `isWindowContact`, `isOn`, `EntityMap` (house-bubbles).
- Produces:
  ```ts
  // models/house-opening.model.ts
  export type OpeningKind = 'WINDOW' | 'DOOR';
  export type OpeningSize = 'SMALL' | 'NORMAL' | 'WIDE' | 'TERRACE';
  export type OpeningHinge = 'START' | 'END';
  export type OpeningSwing = 'POSITIVE' | 'NEGATIVE';
  export interface HouseOpeningRequest { floor: FloorId; kind: OpeningKind; size: OpeningSize;
    x1: number; y1: number; x2: number; y2: number; hinge: OpeningHinge; swing: OpeningSwing; deviceId: string | null }
  export interface HouseOpening extends HouseOpeningRequest { id: number }

  // services/house-opening.service.ts (providedIn root, Basis /api/v1/house/openings)
  list(): Observable<HouseOpening[]>; create(r: HouseOpeningRequest): Observable<HouseOpening>;
  update(id: number, r: HouseOpeningRequest): Observable<HouseOpening>; remove(id: number): Observable<void>;

  // shared/house/house-openings.util.ts
  export interface Segment { x1: number; y1: number; x2: number; y2: number }
  export interface OpeningDimensions { widthM: number; sillM: number; topM: number }
  export const OPENING_DIMENSIONS: Record<OpeningKind, Partial<Record<OpeningSize, OpeningDimensions>>>;
  export const SIZES_BY_KIND: Record<OpeningKind, OpeningSize[]>;   // WINDOW: SMALL,NORMAL,WIDE; DOOR: NORMAL,TERRACE
  export const DEFAULT_SIZE: Record<OpeningKind, OpeningSize>;       // NORMAL / NORMAL
  export const SNAP_DISTANCE_UNITS = 12;
  export function widthUnits(kind: OpeningKind, size: OpeningSize): number;
  export function normalizeSegment(s: Segment): Segment;
  export function isOnWall(floor: FloorId, s: Segment): boolean;
  /** Mitte auf nächste Wand (≤ SNAP_DISTANCE_UNITS) projiziert, ganz innerhalb des verschmolzenen Wandstücks gehalten; null wenn zu weit oder Stück zu kurz. */
  export function snapToWall(floor: FloorId, point: PlanPoint, kind: OpeningKind, size: OpeningSize): Segment | null;
  export interface WallSides { positive: string | null; negative: string | null }  // Raum-Ids
  export function wallSides(floor: FloorId, s: Segment): WallSides;
  export function isInnerWall(floor: FloorId, s: Segment): boolean;
  export function defaultSwing(floor: FloorId, s: Segment): OpeningSwing;
  export function overlapsAny(s: Segment, others: readonly Segment[]): boolean;
  export function linkableDevices(): HouseDevice[];
  export function openingIsOpen(o: HouseOpening, entities: EntityMap): boolean;
  export interface Panel { corners: [Vec3, Vec3, Vec3, Vec3] } // unten-Anschlag, unten-frei, oben-frei, oben-Anschlag
  export interface OpeningShape { id: number; floor: FloorId; kind: OpeningKind; open: boolean; frame: [Vec3, Vec3, Vec3, Vec3]; panels: Panel[] }
  export function openingShape(o: HouseOpening, open: boolean): OpeningShape;
  ```

- [ ] **Step 1: Failing Util-Tests** in `house-openings.util.spec.ts`. Die Raumrechtecke kommen aus dem aktuellen `house-plan.ts`, im EG z. B. Esszimmer `(20,90,210,240)`, Küche `(20,240,210,350)`, Wohnzimmer `(210,90,420,270)`, Flur `(210,270,420,440)` und Gäste-WC `(210,370,290,440)`.
  - `widthUnits` für alle fünf Paare: Fenster SMALL 24, NORMAL 40, WIDE 64; Tür NORMAL 36, TERRACE 72. Ein Paar, das es nicht gibt (Tür SMALL), wirft.
  - `normalizeSegment({x1:100,y1:90,x2:60,y2:90})` ergibt `{60,90,100,90}`, ebenso senkrecht (oben zuerst).
  - `isOnWall('ground', {20,200,20,260})` ist true (Außenwand über die Grenze Esszimmer/Küche bei y=240). `{20,200,20,460}` ist false (über das Haus hinaus). `{100,150,140,150}` ist false (mitten im Raum).
  - `snapToWall('ground', {x:25,y:150}, 'WINDOW', 'NORMAL')` ergibt `{20,130,20,170}`. Bei `{x:60,y:150}` (40 Einheiten weg) ergibt es null. Nahe dem Wandende `{x:20,y:95}` wird die Strecke nach innen geschoben: `{20,90,20,130}`.
  - Zu kurzes Wandstück: eine Testfunktion mit eigenem Mini-Grundriss ist nicht möglich, also eine reale Stelle: Der Gäste-WC-Abschnitt der Trennwand x=290 von y=370 bis 440 ist 70 lang. `snapToWall('ground', {x:291,y:400}, 'DOOR', 'TERRACE')` (72) ergibt null.
  - `wallSides('ground', {20,130,20,170})` ergibt `{ positive: 'esszimmer', negative: null }`. Die senkrechte Trennwand Esszimmer/Wohnzimmer bei x=210, `{210,130,210,170}`, ergibt `{ positive: 'wohnzimmer', negative: 'esszimmer' }`. Eine Strecke an der Gäste-WC-Kante innerhalb des Flurs, `{230,370,260,370}`, ergibt `{ positive: 'gaeste_wc', negative: 'flur' }`.
  - `isInnerWall` ist true bzw. false für die obigen Fälle.
  - `defaultSwing`: Außenwand links ergibt `'POSITIVE'`. Trennwand Esszimmer/Wohnzimmer ergibt die Seite des in `HOUSE_ROOMS` zuerst stehenden Raums (esszimmer ⇒ `'NEGATIVE'`).
  - `overlapsAny`:
    - gleiche Linie mit überlappendem Intervall ergibt true
    - nur berührend (Endpunkt gleich) ergibt false
    - parallele andere Linie ergibt false
  - `linkableDevices()` enthält die Terrassentür, das Badfenster, die Balkontür und die Haustür. Der Gaszähler fehlt.
  - `openingIsOpen`:
    - Kontakt `on` ergibt true, `off`/`unavailable`/fehlende Entität ergibt false
    - Haustür über `contactEntityId`
    - ohne `deviceId` oder mit unbekannter `deviceId` immer false
  - `openingShape`:
    - Fenster NORMAL auf `{20,130,20,170}` im EG, geschlossen: Rahmen-Unterkante y = `floorBase('ground') + 0.9`, Oberkante + 2,1, x = `toWorldX(20)`.
    - Ein Panel liegt geschlossen in der Wandebene.
    - Offen mit `hinge 'START'`, `swing 'POSITIVE'`: die freie Kante dreht um 60°, das freie Ende liegt bei `toWorldX(20) + sin60·1,0` und `toWorldZ(130) + cos60·1,0`. Die Anschlagecken sind unverändert.
    - `TERRACE` liefert zwei Panels mit je der halben Breite, Anschlag an beiden Enden.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement util, model, service** sowie die Balkontür in `house-devices.ts`.
  - Wandlinien: alle Rechteckkanten je Etage nach Linie (`orientation` + Koordinate) gruppieren und Intervalle verschmelzen, die sich überlappen oder berühren.
  - `wallSides` prüft je einen Punkt 1 Einheit beiderseits der Streckenmitte mit `isPointInArea` gegen alle Räume der Etage.

- [ ] **Step 4: Service-Test** (HttpTestingController): URLs, Methoden und Bodies.

- [ ] **Step 5: Run all frontend tests.** Erwartet: nur die Baseline-Fehlschläge.

- [ ] **Step 6: Commit** `feat(house): Fenster-und-Tueren-Logik, Balkontuer als Kontakt`

---

### Task 3: Szene – Elemente zeichnen, Glühen unterdrücken

**Files:**
- Modify: `frontend/src/app/shared/house/house-effects.util.ts` (+ spec)
- Modify: `frontend/src/app/pages/tablet-house/house-scene-renderer.ts`
- Modify: `frontend/src/app/pages/tablet-house/tablet-house.component.ts` (+ spec)

**Interfaces:**
- Consumes: `HouseOpeningService.list`, `openingShape`, `openingIsOpen`, `isOnWall`, `OpeningShape` (Task 2)
- Produces:
  - `buildHouseEffects(areaIds, entities, brightness, positions, linkedDeviceIds: ReadonlySet<string>)`: Ein `contact` oder `door`, dessen `id` im Set liegt, liefert keinen `open`-Effekt.
  - `HouseSceneRenderer.setOpenings(shapes: readonly OpeningShape[]): void`. Der Fake in `HOUSE_RENDERER_FACTORY`-Tests bekommt dieselbe Methode.

- [ ] **Step 1: Failing Tests**
  - Effekte: `verknuepfterKontaktGluehtNicht` (Badfenster `on` im Set ergibt keinen `open`-Effekt), `unverknuepfterKontaktGluehtWeiter`, `verknuepfteHaustuerGluehtNicht`.
  - Komponente, mit Fake-Service im vorhandenen Stil:
    - `zeichnetElementeDerEtage`: Ein Element im EG führt dazu, dass `setOpenings` mit genau einer Shape (`open:false`) aufgerufen wird.
    - `offenesElementWirdOffenGezeichnet`: Ein verknüpfter Kontakt `on` ergibt `open:true`.
    - `elementOhneWandWirdNichtGezeichnet`: `isOnWall` ist false, also keine Shape.
    - `ladefehlerBehaeltLetztenStand`.
    - `unveraenderteElementeRufenSetOpeningsNichtErneut` (Gleichheitsprüfung wie bei `sameEffects`).
  - Alle Elemente aller Etagen gehen an den Renderer. Die Sichtbarkeit regelt der Renderer über die Layer-Gruppen.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement**
  - Komponente:
    - `openings`-Signal, geladen im `ticks`-Strom wie die Positionen (`catchError(() => of(null))`, null behält den Stand)
    - `linkedDeviceIds` als `computed` aus den Elementen mit `deviceId`
    - `shapes` als `computed` (nur `isOnWall`, `open` via `openingIsOpen`) mit inhaltsbasiertem `equal`
    - ein `effect` ruft `renderer.setOpenings`
  - Renderer:
    - eine eigene Gruppe je Etage in deren Layer-Gruppe, `setOpenings` leert und befüllt sie und fordert ein Rendering an
    - Fenster als Glasfläche (bläulich, halbtransparent) mit Rahmenlinien, Türen als Türblatt (heller, halbtransparent) mit Rahmenlinien
    - offen: Panels und Rahmen rot (Farben wie im Entwurf: Glas `rgba(143,211,255,0.22)`, Rahmen `#b9e6ff`, offen `#ff8f8f`)
    - geteilte Materialien je Zustand, Geometrien beim Leeren freigeben, ein kleiner Versatz vor die Wand gegen Z-Fighting
    - WebGL wird nicht getestet

- [ ] **Step 4: Run all frontend tests.** Erwartet: nur die Baseline-Fehlschläge.

- [ ] **Step 5: Commit** `feat(house): Fenster und Tueren in der 3D-Szene`

---

### Task 4: Admin-Seite – Fenster und Türen pflegen, Doku

**Files:**
- Create: `frontend/src/app/pages/admin-house-devices/house-openings-editor/house-openings-editor.component.{ts,html,scss,spec.ts}`. Kindkomponente, die das SVG der Seite nutzt: Sie bekommt `floor` und die Plan-Pointer-Ereignisse als Inputs bzw. Methoden, oder rendert eine eigene `<g>`-Ebene über einen Template-Slot. Der Builder wählt den Schnitt, solange Ziehen und Tippen im selben SVG funktionieren und die Geräte-Logik der Seite unverändert bleibt.
- Modify: `admin-house-devices.component.{ts,html,scss}` (Einbindung, Knöpfe unter der Etagenwahl)
- Modify: `CLAUDE.md` (Abschnitt „Digitaler Zwilling“)

**Interfaces:**
- Consumes: `HouseOpeningService`, `snapToWall`, `isOnWall`, `wallSides`, `isInnerWall`, `defaultSwing`, `overlapsAny`, `normalizeSegment`, `widthUnits`, `SIZES_BY_KIND`, `DEFAULT_SIZE`, `linkableDevices` (Task 2), `roomById`.

**Verhalten (verbindlich):**
- **Anlegen:**
  - Knöpfe „Fenster hinzufügen“ und „Tür hinzufügen“ schalten in einen Setz-Modus mit dem Hinweis „Auf eine Wand tippen“. Der nächste Tipp auf den Plan führt `snapToWall` mit der Standardgröße aus.
  - Ist das Ergebnis `null`, erscheint „Hier ist keine Wand, die lang genug ist.“
  - Überlappt die Strecke ein anderes Element der Etage, erscheint „Dort ist schon ein Fenster oder eine Tür.“
  - Sonst folgt `create` mit `hinge 'START'`, `defaultSwing` und `deviceId null`.
- **Darstellung:**
  - Ein Element ist eine dicke Linie auf der Wand, Fenster `#5b8cff`, Tür `#c9d6f0`, Breite 6.
  - Eine Tür bekommt einen Viertelbogen vom Anschlag zur `swing`-Seite.
  - Ein verknüpftes Element trägt einen kleinen Punkt in der Mitte.
- **Ziehen:**
  - Pointer Events, Schwelle 8 px wie bei den Geräten.
  - Während des Ziehens rastet die Strecke per `snapToWall` (gleiche Größe) an die nächste Wand. Ein Sprung auf eine andere Wand ist erlaubt.
  - Beim Loslassen folgt `update` mit neuer Strecke. Wechselt dabei die Wand, wird `swing` neu per `defaultSwing` gesetzt.
  - Kein Speichern, wenn die Strecke `null` ist oder überlappt: Das Element springt zurück, und es erscheint derselbe Hinweis.
- **Antippen** öffnet das Feld mit:
  - **Größe:** Auswahl aus `SIZES_BY_KIND`, Anzeige „schmal/normal/breit“ bzw. „Tür/Terrassentür“. Ein Größenwechsel behält die Mitte. Passt das Ergebnis nicht (nicht `isOnWall` oder überlappend), kommt der Hinweis „Diese Größe passt hier nicht hin.“ und es wird nicht gespeichert.
  - **Anschlag links/rechts** (nicht bei `TERRACE`): „links“ ist der Endpunkt, der von der `swing`-Seite aus gesehen links liegt. Die Umrechnung auf `START`/`END` steht im Util, Funktion `hingeForSide(segment, swing, 'LEFT'|'RIGHT'): OpeningHinge` und `sideForHinge(...)`, beide mit Test in Task 4.
  - **Bei Trennwänden** zusätzlich „Öffnet in:“ mit beiden Raumnamen aus `wallSides`. Die Auswahl setzt `swing`.
  - **Sensor:** Auswahl „kein Sensor“ plus `linkableDevices()` ohne die schon von anderen Elementen belegten. Eine unbekannte gespeicherte `deviceId` erscheint als „Sensor nicht gefunden“ mit Option „kein Sensor“.
  - **„Löschen“** mit Rückfrage „Dieses Element löschen?“.
  - Jede Änderung im Feld speichert sofort per `update`.
- **Liste „Liegt auf keiner Wand“:** Elemente der Etage mit `!isOnWall`, je mit „Löschen“.
- **Fehler:**
  - Speichern ergibt „Speichern fehlgeschlagen.“, der letzte Stand bleibt. Das gilt auch bei 409.
  - Laden: Scheitert `list`, zeigt die Seite die Meldung „Fenster und Türen konnten nicht geladen werden.“ samt „Neu laden“. Die Geräteplätze bleiben davon unberührt.
- Meldungen erscheinen in der vorhandenen festen Meldungszeile der Seite, die den Plan nicht verschiebt.

- [ ] **Step 1: Failing Tests**, gleicher Stil wie `admin-house-devices.component.spec.ts`: PointerEvents, `getScreenCTM`-Spy als Einheitsmatrix, Fake-Service.
  - `fensterHinzufuegenUndAnWandTippenLegtAn`: `create` mit Strecke von `snapToWall`, `hinge 'START'`, `defaultSwing`, `deviceId null`.
  - `tippenOhneWandLegtNichtsAn`: Hinweis „Hier ist keine Wand, die lang genug ist.“, kein `create`.
  - `ueberlappungWirdAbgelehnt`.
  - `ziehenEntlangDerWandSpeichert`.
  - `ziehenInsLeereSpringtZurueck`: kein `update`.
  - `groesseWechselnBehaeltMitte`.
  - `groesseDiePasstNichtWirdAbgelehnt`.
  - `trennwandZeigtOeffnetIn`: Auswahl setzt `swing`.
  - `sensorListeOhneBelegte`.
  - `unbekannterSensorZeigtHinweis`.
  - `loeschenMitRueckfrage`.
  - `elementAufKeinerWandStehtInListe`.
  - `speicherfehlerUnd409ZeigenMeldungUndBehaltenStand`.
  - `ladefehlerZeigtMeldung`.
  - Util-Tests zu `hingeForSide` und `sideForHinge` in `house-openings.util.spec.ts`:
    - Plan-Koordinaten: x nach rechts, y nach unten.
    - Waagerechte Außenwand oben (y=90) mit `swing 'POSITIVE'`: Man steht im Haus und blickt nach −y, „links“ ist das kleinere x, also `'START'`.
    - Senkrechte linke Außenwand (x=20) mit `swing 'POSITIVE'`: Man blickt nach −x, „links“ ist das größere y, also `'END'`.
    - Der Test hält außerdem beide Umkehrungen fest.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement** die Komponente und die Einbindung, außerdem `hingeForSide` und `sideForHinge` im Util.

- [ ] **Step 4: Run all frontend tests.** Erwartet: nur die Baseline-Fehlschläge. Dazu `npx ng build --configuration production`: das bekannte Dashboard-SCSS-Budget-ERROR ist keine Regression, jeder andere neue Fehler schon.

- [ ] **Step 5: CLAUDE.md**, Abschnitt „Digitaler Zwilling“, neuer Unterpunkt „Fenster und Türen (seit 2026-11-11)“. Inhalt:
  - Größentabelle
  - Wandregel samt Trennwand und Außenwand
  - `hinge`/`swing` mit Normalisierung
  - API, Security, Audit, Changeset (nie gegen die echte DB gelaufen)
  - Unique `device_id`
  - `house-openings.util.ts` als einzige Definition
  - Regel zum roten Glühen
  - die Balkontür als neuer Kontakt
  - Verhalten bei verwaisten Sensoren und bei Elementen, die auf keiner Wand mehr liegen

  In „Bekannte Grenzen v1“ entfällt „Wände/Türen/Fenster gibt es nicht als eigene Geometrie“ und wird ersetzt durch „Wände sind weiter keine eigene Geometrie, Fenster und Türen liegen davor“.

- [ ] **Step 6: Commit** `feat(house): Fenster und Tueren auf der Admin-Seite pflegen` und `docs(house): Fenster und Tueren in CLAUDE.md`
