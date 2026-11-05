# Digitaler Zwilling: 3D-Hausansicht Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eine neue Seite `/tablet/house` zeigt das Haus (Keller, EG, OG, Außenbereich) als dunkle Neon-3D-Szene mit antippbaren Info-Bubbles je Raum und je Gerät, die auf bestehende Seiten springen.

**Architecture:** Grundriss und Gerätezuordnung sind statische TypeScript-Daten im Frontend. Reine Utils machen daraus Szenengeometrie und Bubble-Inhalte. Eine Three.js-Klasse (`HouseSceneRenderer`) zeichnet nur bei Bedarf und projiziert Bubble-Ankerpunkte auf Bildschirmkoordinaten. Die Bubbles selbst sind Angular-HTML, absolut über dem Canvas positioniert. Die Seite lädt alle 30 s `GET /v1/entities`.

**Tech Stack:** Angular 19 standalone, Three.js (neu), Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-11-05-digitaler-zwilling-3d-design.md`

## Global Constraints

- Nur Frontend: keine Backend-Änderung, keine Migration, keine neue Security-Zeile (Lesen KIOSK über `GET /v1/**`).
- Standalone-Komponenten, Template/Styles in eigenen `.html`/`.scss`-Dateien.
- Three.js nur im Lazy-Chunk der Route `tablet/house`; nirgends sonst importieren.
- Keine CDN-Assets; Icons als Material Symbols (lokal wie im Rest der App).
- Nur dunkles Design: die Seite ignoriert `.lumina--light` (eigene Neon-Tokens in der Komponente; Text- und Kartenflächen dürfen lumina-Tokens nutzen).
- Rendern nur bei Bedarf (Interaktion, Kamerafahrt, Datenänderung, Resize), kein Dauer-Loop; `renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5))`; keine Schatten.
- Ziel ≥ 30 fps beim Drehen/Zoomen auf dem Lenovo Tab M11.
- Maßstab: 40 Skizzen-Einheiten = 1 m, Geschosshöhe 2,6 m.
- Refresh 30 s; nur der Erstabruf meldet einen Fehler, ein fehlgeschlagener Refresh behält die letzten Werte.
- `unavailable`/`unknown` werden nie als Wert angezeigt („–“ und abgedunkelt).
- Überblick: genau eine Bubble je Raum. „Gerät läuft“ im Überblick nur aus den Wasch-/Spülmaschinen-Helfern, nie aus Watt.
- Geräte ohne Raum (Router, Wasserpumpe, Ladestation, Klimagerät, Lichterkette, Staubsauger-Ladegerät, Fire Tablet, Balkontür) erscheinen nicht.
- Testkommando: `npm test -- --watch=false --browsers=ChromeHeadless` aus `frontend/`. Baseline: 3 vorbestehende Fails (`AppComponent` ×2, `HeroComponent`); nur zusätzliche Fails sind Regressionen.

## Review Focus

1. **`lastUpdated` ohne Zeitzone** („2026-11-05T09:30:58“, lokale Haushaltszeit): als lokale Zeit parsen, nicht als UTC. Sonst ist jeder Wert 1–2 h „veraltet“. Test in Task 3.
2. **Ein Raum ohne jeden lesbaren Klimawert** (Pain-Cave-Fühler ist seit Wochen `unavailable`): Die Bubble zeigt „–“ und gilt als veraltet, statt zu verschwinden oder `NaN°` zu zeigen. Test in Task 3.
3. **Ein Refresh liefert eine zugeordnete Entität nicht mehr** (gelöscht, umbenannte Zigbee-Id): Die Bubble zeigt „–“, nichts wirft. Test in Task 3.
4. **Tablet-Falle:** Im Tablet-Modus führt kein Bubble-Ziel auf eine Seite ohne Tablet-Shell (sonst gibt es keinen Rückweg). Test in Task 3.
5. **Antippen beim Drehen:** Ein Ziehen mit Maus/Finger darf am Ende keinen Raum-Zoom und keine Navigation auslösen. Nur ein Tipp unter 8 px Bewegung zählt. Test in Task 4.

---

## File Structure

```
frontend/src/app/
├── shared/house/
│   ├── house-plan.ts               # Räume, Etagen, Außenflächen (Daten + Typen)
│   ├── house-plan.spec.ts
│   ├── house-devices.ts            # Gerät → Raum + Entity-IDs (Daten + Typen)
│   ├── house-devices.spec.ts
│   ├── house-bubbles.util.ts       # EntityState[] → Bubble-Inhalte, Veraltung, Ziele
│   ├── house-bubbles.util.spec.ts
│   ├── house-geometry.util.ts      # Plan → Boxen/Kamera-Ziele/Bubble-Anker (reine Mathematik)
│   └── house-geometry.util.spec.ts
├── pages/tablet-house/
│   ├── house-scene-renderer.ts     # Three.js-Kapsel (einzige Datei mit `three`-Import)
│   ├── tablet-house.component.{ts,html,scss,spec.ts}
├── shared/tablet-views.ts          # + Eintrag „Haus“
├── shared/temperature-comfort.util.ts  # STALE_THRESHOLD_MS exportieren
├── components/header/header.component.ts  # + Navi-Eintrag
└── app.routes.ts                   # + Route tablet/house
```

---

### Task 1: Grundrissdaten

**Files:**
- Create: `frontend/src/app/shared/house/house-plan.ts`
- Test: `frontend/src/app/shared/house/house-plan.spec.ts`

**Interfaces:**
- Produces:
  ```ts
  export type FloorId = 'basement' | 'ground' | 'upper';
  export const FLOORS: readonly { id: FloorId; label: string; level: number }[]; // Keller -1, EG 0, OG 1
  export interface PlanRect { x1: number; y1: number; x2: number; y2: number } // Skizzen-Einheiten
  export interface HouseRoom { id: string; label: string; floor: FloorId; rects: PlanRect[]; cutouts?: PlanRect[] }
  export interface OutdoorArea { id: 'garden' | 'terrace' | 'front'; label: string; rect: PlanRect }
  export const HOUSE_ROOMS: readonly HouseRoom[];
  export const OUTDOOR_AREAS: readonly OutdoorArea[];
  export const HOUSE_BOUNDS: PlanRect; // { x1: 20, y1: 90, x2: 420, y2: 440 }
  export const UNITS_PER_METER = 40;
  export const STOREY_HEIGHT_M = 2.6;
  export function roomById(id: string): HouseRoom | undefined;
  ```

- [ ] **Step 1: Write the failing tests** in `house-plan.spec.ts`:
  - `alle Raum-Ids sind eindeutig`
  - `jeder Raum liegt innerhalb von HOUSE_BOUNDS`
  - `je Etage überlappen sich Räume nur über erklärte cutouts`: Paarweise Prüfung der Flächenschnitte. Das Gäste-WC liegt im Flur und ist dort als `cutouts` eingetragen.
  - `kennt genau die abgestimmten Räume`: Ids sortiert gleich `['bad','buero','esszimmer','flur','gaeste_wc','kellereingang','kueche','laufband','paincave','schlafzimmer','treppenhaus','vorratskeller','waschkeller','wohnzimmer']`
  - `Schlafzimmer überspannt die ganze Rückseite`: `roomById('schlafzimmer')!.rects` gleich `[{x1:20,y1:90,x2:420,y2:220}]`

- [ ] **Step 2: Run, verify FAIL** (Modul fehlt).

- [ ] **Step 3: Implement `house-plan.ts`** mit genau den Rechtecken der Spec-Tabellen (Abschnitt „Grundriss“):
  - **EG:** esszimmer 20/90–210/220, kueche 20/220–210/350, laufband 20/350–210/440, wohnzimmer 210/90–420/250, flur 210/250–420/440 mit cutout 210/370–290/440, gaeste_wc 210/370–290/440
  - **OG:** schlafzimmer 20/90–420/220, buero 20/220–210/340, bad 20/340–210/440, treppenhaus 210/220–420/440
  - **Keller:** vorratskeller 20/90–210/220, waschkeller 20/220–210/440, paincave 210/90–420/250, kellereingang 210/250–420/440
  - **Außen:** garden 10/10–430/85, terrace 230/56–410/88, front 20/445–420/480

- [ ] **Step 4: Run, verify PASS.**

- [ ] **Step 5: Commit** `feat(house): Grundrissdaten der drei Etagen`

---

### Task 2: Szenengeometrie, Renderer und Seiten-Durchstich (Checkpoint auf dem Tablet)

**Files:**
- Create: `frontend/src/app/shared/house/house-geometry.util.ts`, `.spec.ts`
- Create: `frontend/src/app/pages/tablet-house/house-scene-renderer.ts`
- Create: `frontend/src/app/pages/tablet-house/tablet-house.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/app.routes.ts` (Route nach `tablet/heating`, gleiches Muster, Titel `Haus Tablet - Household Manager`)
- Modify: `frontend/src/app/shared/tablet-views.ts` (`{ route: '/tablet/house', icon: 'home', label: 'Haus' }` als erster Eintrag)
- Modify: `frontend/src/app/components/header/header.component.ts` (unter „Smart Home“ zuerst `{ path: '/tablet/house', label: 'Haus 3D' }`)
- Modify: `frontend/package.json` (`npm install three @types/three`, exakte Versionen pinnen)

**Interfaces:**
- Consumes: Task 1 (`HOUSE_ROOMS`, `OUTDOOR_AREAS`, `FLOORS`, `UNITS_PER_METER`, `STOREY_HEIGHT_M`)
- Produces:
  ```ts
  // house-geometry.util.ts — Weltkoordinaten in Metern, y = oben, Hausmitte bei x=z=0, Straßenseite = +z
  export interface Vec3 { x: number; y: number; z: number }
  export interface RoomBox { roomId: string; floor: FloorId; center: Vec3; size: Vec3 }
  export function roomBoxes(): RoomBox[];                       // je Rechteck eine Box (Höhe = STOREY_HEIGHT_M, Boden bei level*STOREY_HEIGHT_M)
  export function roomAnchor(roomId: string): Vec3;             // Mitte des größten Rechtecks, 1,2 m über dem Boden
  export function floorOpacity(floor: FloorId, selected: FloorId): 'solid' | 'glass' | 'hidden'; // darüber glass, gewählte+darunter solid
  export function cameraForOverview(selected: FloorId): { position: Vec3; target: Vec3 };
  export function cameraForRoom(roomId: string): { position: Vec3; target: Vec3 };

  // house-scene-renderer.ts
  export interface ScreenPoint { x: number; y: number; visible: boolean }
  export class HouseSceneRenderer {
    constructor(host: HTMLElement);
    setFloor(floor: FloorId): void;
    flyTo(view: { position: Vec3; target: Vec3 }, durationMs?: number): Promise<void>; // Default 700 ms, ease-in-out
    pickRoom(clientX: number, clientY: number): string | null;   // Raycast auf Räume der gewählten Etage
    project(points: Vec3[]): ScreenPoint[];                       // relativ zum Host, visible=false hinter Kamera/außerhalb
    onRendered(cb: () => void): void;                             // nach jedem gezeichneten Frame
    requestRender(): void;
    dispose(): void;
  }
  export const HOUSE_RENDERER_FACTORY: InjectionToken<(host: HTMLElement) => HouseSceneRenderer>;
  ```

- [ ] **Step 1: Write failing geometry tests:**
  - `roomBoxes legt EG-Boden auf y=0, Keller auf -2.6, OG auf 2.6`: Boden einer Box = `center.y - size.y/2`.
  - `Wohnzimmer ist 5,25 x 4 m`: Box `wohnzimmer` hat `size.x` 5.25, `size.z` 4 (`toBeCloseTo`).
  - `Straßenseite liegt bei +z`: `roomAnchor('flur').z > roomAnchor('wohnzimmer').z`.
  - `floorOpacity`: Bei gewähltem EG ist upper `'glass'`, ground `'solid'`, basement `'solid'`. Bei gewähltem Keller sind ground und upper `'glass'`.
  - `cameraForRoom zielt auf den Raum`: Das `target` gleicht `roomAnchor(id)` in x/z, der Abstand von `position` zum `target` liegt zwischen 4 und 12 m.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement `house-geometry.util.ts`.** Umrechnung: `xMeter = (x - 220) / 40`, `zMeter = (y - 265) / 40`; 220/265 ist die Mitte von `HOUSE_BOUNDS`.

- [ ] **Step 4: Run, verify PASS.**

- [ ] **Step 5: Implement `HouseSceneRenderer`.** Das ist die einzige Datei mit `import * as THREE from 'three'` plus `OrbitControls` aus `three/examples/jsm/controls/OrbitControls.js`.
  - **Szene:** Hintergrund `#05070f`. Je Raum eine Bodenplatte und vier Wandkanten als `LineSegments`, Farbe `#5b8cff`, Bodenfüllung mit Deckkraft 0,08. Außenflächen als gestrichelte grüne Umrisse (`#3fbf7f`). Glas-Etagen nur als Kanten mit Deckkraft 0,15, `hidden` gar nicht.
  - **Steuerung:** OrbitControls mit `enableDamping: false`, Polarwinkel begrenzt (nicht unter den Boden), Zoom 3–30 m.
  - **Rendern nur bei Bedarf:** Das `change`-Event der Controls, `flyTo`, `setFloor`, `ResizeObserver` und `requestRender()` lösen ein `requestAnimationFrame`-Rendern aus. `flyTo` animiert über rAF bis zum Ende und hört dann auf.
  - **Antippen:** Der Raycaster trifft nur Bodenplatten der gewählten Etage. `dispose()` gibt Geometrien, Materialien, Renderer und Observer frei.
  - Kein Test für diese Klasse, weil WebGL in Karma nicht verlässlich ist. Sie wird durch die Komponente über `HOUSE_RENDERER_FACTORY` erzeugt, der Default ist `host => new HouseSceneRenderer(host)`.

- [ ] **Step 6: Write failing component tests** (`tablet-house.component.spec.ts`). Die Factory wird durch einen Fake ersetzt, der `pickRoom`/`project` stubbt und Aufrufe aufzeichnet:
  - `steckt in der Tablet-Shell mit Überschrift „Haus“`
  - `zeigt je Raum der gewählten Etage eine Raum-Bubble mit dem Raumnamen`: EG ergibt 6 Bubbles, darunter „Wohnzimmer“.
  - `Etagenwahl wechselt die Bubbles und ruft setFloor`: Klick auf „OG“ ergibt `setFloor('upper')` und eine Bubble „Schlafzimmer“.
  - `Tipp auf eine Raum-Bubble fliegt in den Raum`: `flyTo` wird mit `cameraForRoom('wohnzimmer')` aufgerufen, danach ist der Zurück-Knopf sichtbar.
  - `Zurück fliegt in den Überblick`: `flyTo(cameraForOverview('ground'))`.
  - `Höhenkette`: Der Host hängt in einem eigenen `div` mit fester Höhe 600/900 px (Muster `tablet-heating.component.spec.ts`, **nicht** `host.parentElement`). Die Canvas-Fläche `.tablet-house__stage` wächst mit, Höhe bei 900 mindestens 200 px größer als bei 600.

- [ ] **Step 7: Run, verify FAIL.**

- [ ] **Step 8: Implement die Komponente** (Durchstich, Bubbles zeigen vorerst nur den Raumnamen):
  - **Template:** `<app-tablet-shell heading="Haus">`, im Slot `shellActions` die Etagenknöpfe Keller/EG/OG. Darunter `.tablet-house__stage`: Renderer-Host plus absolut positionierte Bubble-Ebene.
  - **Zustand:** `selectedFloor = signal<FloorId>('ground')`, `focusedRoom = signal<string | null>(null)`.
  - **Bubble-Positionen:** Nach jedem `onRendered` werden die Anker über `project` in Bildschirmkoordinaten umgerechnet und als `style.transform` gesetzt, ohne Change Detection je Frame: Signal plus `ChangeDetectionStrategy.OnPush`, die Elemente werden direkt per `ElementRef` verschoben.
  - **SCSS:** Flex-Kette wie `tablet-heating.component.scss` (`:host` und Wurzel mit `flex: 1; min-height: 0`). Neon-Tokens lokal: `--house-neon: #5b8cff`, `--house-accent: #8fd3ff`, `--house-bg: #05070f`.

- [ ] **Step 9: Run component tests, verify PASS; `npm run build` ohne neuen Fehler.** Die vorhandene Budget-Warnung des Dashboard-SCSS zählt nicht. Three.js muss im Lazy-Chunk liegen, nicht in `main`.

- [ ] **Step 10: Commit** `feat(house): 3D-Durchstich mit Etagenwahl und Raum-Zoom`

- [ ] **Step 11: CHECKPOINT. Anhalten und den Nutzer bitten, `/tablet/house` auf dem Wandtablet zu prüfen** (Drehen, Zoomen, Raum antippen, Etage wechseln). Erst nach seinem OK weiter. Ruckelt es, zuerst reduzieren: Pixelratio 1, keine Bodenfüllung, weniger Kanten. Danach erneut prüfen lassen.

---

### Task 3: Gerätezuordnung und Bubble-Inhalte

**Files:**
- Create: `frontend/src/app/shared/house/house-devices.ts`, `.spec.ts`
- Create: `frontend/src/app/shared/house/house-bubbles.util.ts`, `.spec.ts`
- Modify: `frontend/src/app/shared/temperature-comfort.util.ts` (`STALE_THRESHOLD_MS` exportieren, sonst unverändert)

**Interfaces:**
- Consumes: Task 1 (`HOUSE_ROOMS`, `roomById`), `EntityState` aus `models/entity-state.model.ts`, `STALE_THRESHOLD_MS`
- Produces:
  ```ts
  // house-devices.ts
  export type HouseDevice =
    | { kind: 'climate'; id: string; area: string; label: string; temperature?: string; humidity?: string; iaq?: string; roomSensor: boolean }
    | { kind: 'thermostat'; id: string; area: string; label: string; temperature: string; target: string; heating: string }
    | { kind: 'contact'; id: string; area: string; label: string; entityId: string }
    | { kind: 'motion'; id: string; area: string; label: string; entityId: string }
    | { kind: 'button'; id: string; area: string; label: string; entityIds: string[] }
    | { kind: 'switch'; id: string; area: string; label: string; entityId: string; powerEntityId?: string }
    | { kind: 'appliance'; id: string; area: string; label: string; switchEntityId: string; powerEntityId: string; runningEntityId: string; finishedEntityId: string }
    | { kind: 'lock'; id: string; area: string; label: string; entityId: string }
    | { kind: 'camera'; id: string; area: string; label: string; armedEntityId: string }
    | { kind: 'waste'; id: string; area: 'front'; label: string; entityId: string }
    | { kind: 'pet'; id: string; area: string; label: string; homeEntityId: string };
  export const HOUSE_DEVICES: readonly HouseDevice[];
  export function devicesInArea(area: string): HouseDevice[];   // area = Raum-Id oder Außen-Id

  // house-bubbles.util.ts
  export type BubbleFlag = 'window_open' | 'heating' | 'appliance_running' | 'appliance_finished' | 'motion';
  export interface BubbleValue { text: string; stale: boolean }  // text '–' bei fehlend/unavailable/unknown
  export interface RoomBubble { areaId: string; label: string; temperature: BubbleValue; humidity: BubbleValue | null; flags: BubbleFlag[] }
  export interface DeviceBubble { deviceId: string; areaId: string; icon: string; label: string; lines: BubbleValue[]; target: string; highlight: boolean }
  export function parseLocalTimestamp(value: string): number;   // ms; ohne Zone = lokale Zeit
  export function buildRoomBubble(areaId: string, entities: ReadonlyMap<string, EntityState>, nowMs: number): RoomBubble;
  export function buildDeviceBubbles(areaId: string, entities: ReadonlyMap<string, EntityState>, nowMs: number, tabletMode: boolean): DeviceBubble[];
  export function buildOutdoorBubbles(entities: ReadonlyMap<string, EntityState>, nowMs: number, tabletMode: boolean): DeviceBubble[]; // alle Geräte der Bereiche 'front' und 'garden'
  export function bubbleTarget(kind: HouseDevice['kind'], tabletMode: boolean): string;
  ```

- [ ] **Step 1: Write failing tests for `house-devices.ts`:**
  - `jede area ist ein Raum aus HOUSE_ROOMS oder eine Außenfläche`
  - `keine Entity-ID kommt in zwei Geräten vor`
  - `jeder Raum außer gaeste_wc und vorratskeller hat mindestens ein Gerät`
  - `je Raum höchstens ein climate-Gerät mit roomSensor: true`
  - `Balkontür und Router sind nicht zugeordnet`: `binary_sensor.dirigera_f3992ae92ca54fb6b276c2c4d5f832c9_1_contact` und `switch.tapo_80223493d58341bf26cdbbf42d330231243f3a4d` kommen in keiner Entity-ID vor.

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement `HOUSE_DEVICES`** mit diesen Entity-IDs (Stand 2026-11-05, vom Nutzer bestätigt). `rs` steht für `roomSensor: true`:
  - **wohnzimmer:**
    - climate rs `sensor.alexa_gaj2300425330047_temperature|_humidity|_iaq`
    - thermostat 8, thermostat 3 (Label „Heizung Fenster“)
    - camera `binary_sensor.blink_511671_armed`
    - switch Fernseher `switch.meross_2112152941687590863548e1e9817812` (+ power `sensor.meross_…9817812_power`)
    - switch Stehlampe `switch.tapo_802366e0c88d0dba80336f9f7363f8c4216c350a`
    - switch Stern `switch.tapo_80225ff5f331563993ff0df4adbedae5243f7caa`
    - switch Couch-Lichterkette `switch.meross_24041984068833510d06c4e7ae039150`
    - contact Terrassentür `binary_sensor.zigbee_terassentuer_contact`
  - **esszimmer:**
    - climate rs `sensor.dirigera_dcc8d385781d4071adf85f403b0cb997_temperature|_humidity`
    - thermostat 2
    - switch Lampe `switch.kasa_8012e05a14a26399f80f21e4fbff42ac1cdf5b50`
  - **kueche:**
    - climate rs `sensor.dirigera_4a8044b0974e4885b2f60967af2201e1_*`
    - thermostat 4
    - motion `binary_sensor.zigbee_motion_kueche_occupancy`
    - switch Kaffeemaschine `…2112150005305590863548e1e98183b3`, switch Kühlschrank `…2112155672709990863548e1e98181eb`, switch Küchenzeile `…2205069806156351080148e1e991d1f0`, jeweils Meross mit Power
    - appliance Spülmaschine (switch/power `…2205060757549251080148e1e991c4f9`, `input_boolean.manual_spuelmaschine_laeuft|_fertig`)
  - **flur:**
    - climate rs `sensor.dirigera_f2af71e53e924b0895cec17793205733_*`
    - thermostat 6
    - motion `binary_sensor.zigbee_motion_flur_occupancy`
    - switch Licht `switch.tapo_80232e39ed1bbdd537ab39cac50eede2216b55c7`
  - **laufband:**
    - climate rs `sensor.zigbee_temperatur_aqara_laufband_*`
    - switch Laufband `switch.tapo_802238b61a02b19027d329013d5647a9243f6e59` (+ power)
  - **schlafzimmer:**
    - climate rs `sensor.alexa_gaj230043055005a_*`
    - thermostat 5
    - switch Licht `switch.tapo_80238e15a36182550737e4ce654d2f2420b98b5c`
    - switch Fernseher `switch.meross_2112153143211390863548e1e98182b1` (+ power)
  - **buero:**
    - climate rs `sensor.zigbee_temperatur_aqara_buero_*`
    - thermostat 7
    - motion `binary_sensor.zigbee_motion_buero_occupancy`
    - button `event.dirigera_e66219cc17d542fab6ce241b53855360_1_action|_2_action`, `event.zigbee_button_buero_action`
    - switch PC `…2112159340612090863548e1e98183ba`, switch Desktop Light `…24041948162696510d06c4e7ae038ae1`, switch Drucker `…24041903314491510d06c4e7ae038da2`, jeweils Meross mit Power
    - switch Lichtstreifen `switch.tapo_8023261c2124d3bf658efedd6f6c46c020952267`
  - **bad:**
    - climate rs `sensor.zigbee_temperatur_aqara_badezimmer_*`
    - thermostat 1
    - contact Fenster `binary_sensor.zigbee_fenster_badezimmer_contact`
    - contact Fenster (IKEA) `binary_sensor.dirigera_07ddd369cdff4593a0f5c995dfcea5f2_1_contact`
  - **treppenhaus:**
    - climate rs `sensor.dirigera_bffb9dee1dbc4670964f8a1ef8d11b5c_*`
    - motion `binary_sensor.zigbee_motion_treppenhaus_occupancy`
    - button `event.dirigera_4ab740b2efb543018bf563c702722938_1_action|_2_action`, `event.zigbee_schalter_treppenhaus_action`
    - switch Licht `switch.kasa_80121a1cd98950323721a763bc9f1b9f1ce0f7c0`
  - **kellereingang:**
    - climate rs `sensor.zigbee_temperatur_aqara_keller_*`
    - motion `binary_sensor.zigbee_motion_keller_occupancy`
  - **paincave:**
    - climate rs `sensor.zigbee_temperatur_buero_temperature|_humidity`
  - **waschkeller:**
    - appliance Waschmaschine (`…2112156531504590863548e1e9817420`, `input_boolean.manual_waschmaschine_laeuft|_fertig`)
    - contact Gaszähler `binary_sensor.zigbee_sensor_gas_contact`
  - **garden:**
    - climate rs `sensor.zigbee_temperatur_aqara_garten_*`
    - climate Feinstaub (Airrohr `sensor.airrohr_airrohr_pm25`, als `iaq`-Zeile „PM2.5“)
  - **front:**
    - waste `sensor.waste_calendar_next_collection`
    - pet Toni `binary_sensor.tractive_pqbzjech_home`
    - contact Haustür `binary_sensor.zigbee_eingangstuer_contact`, lock `lock.nuki_18241688572` und camera Haustür `binary_sensor.blink_214942_armed`. Die Spec will die Haustür als eigenes Ding außerhalb der Räume.

  Die Thermostat-IDs folgen dem Schema `sensor.thermostat_<n>_temperature`, `sensor.thermostat_<n>_target` und `binary_sensor.thermostat_<n>_heating`. Die Meross-Power-ID ist jeweils `sensor.meross_<sourceRef>_power`, wobei `sourceRef` gleich dem Switch-Suffix ist.

- [ ] **Step 4: Run, verify PASS.**

- [ ] **Step 5: Write failing tests for `house-bubbles.util.ts`.** `now` ist fest auf `new Date(2026, 10, 5, 10, 0).getTime()`, also lokal:
  - `parseLocalTimestamp liest Zeit ohne Zone als Lokalzeit`: `parseLocalTimestamp('2026-11-05T09:30:58')` gleich `new Date(2026,10,5,9,30,58).getTime()`
  - `Raum-Bubble nimmt den Raumfühler, nicht das Thermostat`: wohnzimmer mit Alexa 19.5 und Thermostat 8 20.1 ergibt `temperature.text === '19,5°'`, Feuchte `'45 %'`
  - `ohne lesbaren Raumfühler fällt die Temperatur auf das erste Thermostat zurück`
  - `veraltet ab 60 min`: `lastUpdated` 61 min alt ergibt `stale: true`, 59 min alt ergibt `false`
  - `unavailable zeigt „–“ und gilt als veraltet` (paincave, beide Werte `unavailable`): `{ text: '–', stale: true }`
  - `fehlende Entität wirft nicht`: leere Map ergibt `temperature.text === '–'`
  - `Flags nur bei Abweichung`: Fenster bad `on` ergibt `window_open`; Thermostat heating `on` ergibt `heating`; motion `on` ergibt `motion`; Helfer `…waschmaschine_laeuft` `on` ergibt `appliance_running` in waschkeller; `…_fertig` `on` ergibt `appliance_finished`. Ein Switch mit 221 W ergibt **kein** Flag. Alles aus ergibt `flags` gleich `[]`.
  - `Kontakt unavailable setzt kein window_open`
  - `buildDeviceBubbles liefert je Gerät eine Bubble`: für buero 8 Bubbles. Die Thermostat-Bubble hat die Zeilen `'19,5°'` und `'Soll 20°'`. Die Switch-Bubble PC zeigt `'an'`/`'aus'` und `'221 W'` nur bei `on`. Bei Waschmaschine läuft ist `highlight: true`.
  - `Ziele Website`: `bubbleTarget('climate', false) === '/temperatures'`, `thermostat` ergibt `'/tablet/heating'`, `switch` ergibt `'/devices'`, `waste` ergibt `'/waste-collection'`, `camera` ergibt `'/cameras'`, `pet` ergibt `'/pets'`, `contact`/`motion`/`button`/`lock` ergeben `'/entities'`
  - `Ziele Tablet führen nie auf Seiten ohne Shell`: Für jede `kind` liegt `bubbleTarget(kind, true)` in `['/', ...TABLET_VIEWS.map(v => v.route)]`. climate ergibt `'/tablet/temperatures'`, camera ergibt `'/tablet/cameras'`, pet ergibt `'/tablet/toni'`, waste/switch/contact/motion/button/lock ergeben `'/'` (Dashboard)
  - `Mülltonne`: `state '2026-11-06'` bei `now` 05.11. ergibt die Zeile `'Morgen Abfuhr'` und `highlight: true`. `'2026-11-10'` ergibt `'Di. 10.11.'` und `highlight: false`. Die Abfallart steht in `attributes.wasteTypes` (falls vorhanden, sonst weglassen; vor dem Implementieren im Backend `WasteCollection*`-Mapper nachsehen und den Test an den tatsächlichen Attributnamen anpassen).

- [ ] **Step 6: Run, verify FAIL.**

- [ ] **Step 7: Implement `house-bubbles.util.ts`.**
  - **Zahlenformat:** deutsch, Temperatur mit einer Nachkommastelle, Feuchte und Watt ganzzahlig.
  - **Veraltung:** `nowMs - parseLocalTimestamp(lastUpdated) > STALE_THRESHOLD_MS` gilt nur für climate- und thermostat-Werte. Kontakte, Bewegung und Taster melden nur bei Änderung und sind nie zeitlich veraltet, nur bei `unavailable`/`unknown`.
  - **Icons** (Material Symbols): climate `thermostat`, thermostat `mode_heat`, contact `sensor_window`, motion `directions_walk`, button `radio_button_checked`, switch `power`, appliance `local_laundry_service`/`dishwasher`, lock `lock`, camera `videocam`, waste `delete`, pet `pets`.

- [ ] **Step 8: Run, verify PASS.**

- [ ] **Step 9: Commit** `feat(house): Gerätezuordnung und Bubble-Inhalte`

---

### Task 4: Live-Bubbles in der Seite

**Files:**
- Modify: `frontend/src/app/pages/tablet-house/tablet-house.component.{ts,html,scss,spec.ts}`
- Modify: `frontend/src/app/shared/house/house-geometry.util.ts` (+ `deviceAnchors`), `.spec.ts`

**Interfaces:**
- Consumes: Task 2 (Renderer, Geometrie), Task 3 (`buildRoomBubble`, `buildDeviceBubbles`, `buildOutdoorBubbles`, `devicesInArea`), `EntityStateService.getEntities()`, `ViewModeService.isTabletView`
- Produces: `export function deviceAnchors(areaId: string, count: number): Vec3[]`. Die Anker liegen gleichmäßig im Raster innerhalb des größten Rechtecks des Bereichs, mit 0,4 m Abstand zur Wand, 1,0 m über dem Boden.

- [ ] **Step 1: Write failing tests:**
  - **Geometrie:**
    - `deviceAnchors liegen innerhalb des Raums und sind paarweise ≥ 0,5 m entfernt` (buero mit 9)
  - **Komponente** (Fake-Renderer, `EntityStateService` gestubbt):
    - `lädt beim Start einmal und dann alle 30 s` (`fakeAsync`, `tick(30000)`, danach 2 Aufrufe)
    - `Erstabruf-Fehler zeigt eine Fehlermeldung, Refresh-Fehler behält die Werte`
    - `Raum-Bubble zeigt Temperatur und Flag-Symbole`: Wohnzimmer zeigt `19,5°`. Bei Terrassentür `on` ist `.house-bubble__flag--window_open` sichtbar.
    - `veraltete Werte sind abgedunkelt`: Die Klasse `house-bubble--stale` ist gesetzt.
    - `Raumansicht zeigt die Geräte-Bubbles des Raums, Überblick nicht`
    - `Tipp auf eine Geräte-Bubble navigiert zum Ziel`: Mit `Router`-Spy ergibt `navigateByUrl('/tablet/heating')` für das Thermostat. Im Tablet-Modus führt der Switch auf `'/'`.
    - `Außen-Bubbles (Mülltonne, Toni, Haustür, Garten) sind im Überblick des EG sichtbar und navigieren direkt`
    - `Ziehen ist kein Tipp`: Pointerdown bei (100,100), pointerup bei (130,100) auf der Bühne ergibt keinen `pickRoom`-Aufruf und kein `flyTo`. Bei 5 px Bewegung kommt `pickRoom`.
    - `Tipp ins Leere in der Raumansicht kehrt in den Überblick zurück` (`pickRoom` gibt `null`)

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement.**
  - **Laden:** `getEntities()` landet in `Map<entityId, EntityState>`, `interval(30000)` mit `startWith(0)`, `takeUntilDestroyed`.
  - **Bubbles berechnen:** Nach jedem Abruf werden die Bubbles neu berechnet, dann `requestRender()`.
  - **Tipp erkennen:** Nur Pointerdown und Pointerup unter 8 px Bewegung zählen als Tipp. Im Überblick öffnet ein Raum-Tipp oder ein Tipp auf die Raum-Bubble die Raumansicht. In der Raumansicht wechselt ein Tipp auf einen anderen Raum dorthin, ein Tipp ins Leere geht zurück.
  - **Navigation:** Geräte- und Außen-Bubbles navigieren über `Router.navigateByUrl(bubble.target)`.
  - **Bubble-Markup:** Icon, Label, Zeilen, Flag-Symbole. `--stale` mit Deckkraft 0,45. `--highlight` mit Akzentrand `--house-accent`. Text mindestens 0,8 rem und gut antippbar (mindestens 44 px hoch).

- [ ] **Step 4: Run, verify PASS; full test run = baseline.**

- [ ] **Step 5: Commit** `feat(house): Live-Bubbles, Raumansicht und Navigation`

- [ ] **Step 6: Nutzer prüft auf dem Wandtablet** (Werte stimmen, keine Überladung, flüssig). Anpassungen an Abständen und Größen kommen in denselben Branch.

---

### Task 5: Doku

**Files:**
- Modify: `CLAUDE.md`: Abschnitt „Digitaler Zwilling (3D-Hausansicht)“ unter „Tablet-Ansichten“, dazu ein Eintrag bei „Current Features“

- [ ] **Step 1: Abschnitt schreiben.** Der Abschnitt nennt:
  - Spec- und Plan-Pfad
  - `house-plan.ts` und `house-devices.ts` als einzige Definition von Grundriss und Zuordnung. Neue Geräte müssen dort von Hand nachgetragen werden, eine Entität taucht nicht von selbst auf. Ein Umbenennen in zigbee2mqtt ändert die Entity-ID, die Bubble zeigt dann „–“.
  - Die Tablet-Ziele: nur Seiten mit Shell oder `/`
  - „Gerät läuft“ nur über Helfer
  - Three.js nur in `house-scene-renderer.ts`
  - Rendern nur bei Bedarf
  - Die bekannten Grenzen der Spec (kein Deep Link, Balkon offen, Maße geschätzt)

- [ ] **Step 2: Commit** `docs: Digitaler Zwilling in CLAUDE.md`
