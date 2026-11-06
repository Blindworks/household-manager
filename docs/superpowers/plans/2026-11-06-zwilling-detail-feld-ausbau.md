# Detail-Feld ausbauen (Stufe 3) – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das Detail-Feld der Hausansicht `/tablet/house` bedient zusätzlich:

- Lampenfarbe und Lampen-Farbtemperatur
- den Thermostat-Modus

Haustür, Kamera, Mülltonne und Toni öffnen das Feld, statt auf eine andere Seite zu wechseln.

**Architecture:**

- **Lesen:** Alle zusätzlichen Daten lädt `HouseDeviceControlService.loadExtras(device)`. Die Seite fragt bei offenem Feld beim Öffnen, im 30-s-Takt und nach jeder Aktion ab. Teilergebnisse werden mit dem letzten Stand zusammengeführt, ein gescheiterter Abruf behält den letzten Stand.
- **Feldmodell:** `buildDeviceDetail(device, entities, nowMs, extras)` baut daraus Zeilen und Bedienelemente. Die heutige Ergänzung um Raumwert und Dimmbarkeit wandert aus der Seite dorthin.
- **Bedienung:** Neue Bedienelemente sind eigene Kind-Komponenten im Ordner `device-detail/`, damit `house-device-detail.component.ts` nicht weiter wächst. Vor jedem Schreiben liest der Dienst den Zustand frisch.

**Tech Stack:** Angular 19 standalone (OnPush, Signals), RxJS, Karma/Jasmine. Das Backend ändert sich nicht.

**Spec:** `docs/superpowers/specs/2026-11-06-zwilling-detail-feld-ausbau-design.md`

## Global Constraints

- Keine Backend-Änderung, keine Änderung an `SecurityConfig`. Belegt ist das durch den Builder-Check: Alle KIOSK-Pfade sind erlaubt, nämlich `POST /v1/nuki/locks/*/actions` (KIOSK nur LOCK), `POST /v1/blink/cameras/*/arm|disarm|snapshot`, `PUT /v1/thermostats/*/mode` und `PUT /devices/*/light`.
- Entsperren und „Tür öffnen“ erscheinen im Feld nicht, auch nicht ausgegraut. Im ganzen Hausansicht-Code darf kein `'UNLOCK'` und kein `'UNLATCH'` vorkommen.
- Jede Aktion liest vorher frisch und handelt nur, wenn der Zustand der Absicht widerspricht und eindeutig ist. Danach `changed.emit()`, die Seite lädt sofort sowie nach 1500 und 5000 ms nach (vorhanden: `refreshAfterAction`).
- Fehlende Werte zeigen „–“, nie 0.
- Farben und Abstände der neuen Komponenten kommen nur über lumina-Tokens. Ausnahme sind die Farben der Farbfelder selbst.
- Die Farbfelder sind genau diese:

  | Feld | Wert |
  |---|---|
  | Warmweiß | `{colorTemp: 2700}` |
  | Rot | `{hue: 0, saturation: 100}` |
  | Orange | `{hue: 30, saturation: 100}` |
  | Gelb | `{hue: 55, saturation: 100}` |
  | Grün | `{hue: 120, saturation: 100}` |
  | Türkis | `{hue: 175, saturation: 100}` |
  | Blau | `{hue: 225, saturation: 100}` |
  | Lila | `{hue: 280, saturation: 100}` |

- Farbtemperatur-Regler: Schritt 100 K, Bereich aus `metadata.colorTempRangeMin/Max`, ersatzweise 2500–6500. Warmweiß ist hervorgehoben bei `|colorTemp − 2700| ≤ 150`, nur im Farbtemperatur-Modus.
- Texte, wörtlich:
  - „Nicht übernommen“
  - „Kamera nicht erreichbar“
  - „Schnappschuss fehlgeschlagen“
  - „Keine Abfuhr in den nächsten Tagen“
  - „kein Spaziergang in 7 Tagen“
  - „Lampe ist aus“ (vorhanden, `LAMP_OFF`)
  - „Verriegeln“
  - „Bewegungserkennung“
  - „Schnappschuss“
  - „Aus“, „Manuell“, „Frostschutz“
- Geräte werden in Tests über Raum oder Id gefunden, nie über Teile einer Entity-ID.
- Frontend-Tests: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`. Die Baseline sind 3 vorbestehende Fails (App ×2, Hero). `--include <spec>` erlaubt Einzelläufe.

## Review Focus

1. **Späte Antwort eines vorher offenen Geräts:** Man öffnet Gerät A, dann sofort B. Die Extras von A kommen danach an und dürfen nicht im Feld von B landen. → Test in Task 5.
2. **Farbe bei ausgeschalteter Lampe:** Der frische Zustand ist nicht `on`. Dann wird nichts geschrieben, und das Feld zeigt „Lampe ist aus“. → Test in Task 4 (Dienst) und Task 6 (Feld).
3. **Kamera-Dialog offen, und der Refresh meldet die Kamera schon deaktiviert:** Bestätigen schaltet nichts. Dasselbe gilt, wenn die Kamera aus der Liste verschwunden ist. → Test in Task 4 (Dienst) und Task 9 (Komponente).
4. **Abfuhrtermin gestern, mit `daysUntil: 0` aus einem alten Poll:** Der Termin entfällt und steht nicht als „heute“ da. → Test in Task 2.
5. **Tracker-Id in anderer Schreibweise:** Die Entität trägt `pqbzjech`, die API `PQBZJECH`. Akku und Spaziergang erscheinen trotzdem. → Test in Task 4.

---

### Task 1: Farblogik der Lampe

**Files:**
- Create: `frontend/src/app/shared/house/light-color.util.ts`
- Test: `frontend/src/app/shared/house/light-color.util.spec.ts`

**Interfaces:**
- Produces:
  - `export type LightColorRequest = { hue: number; saturation: number } | { colorTemp: number }`
  - `export interface ColorSwatch { id: 'warm' | 'red' | 'orange' | 'yellow' | 'green' | 'cyan' | 'blue' | 'purple'; label: string; request: LightColorRequest; css: string }`
  - `export const COLOR_SWATCHES: readonly ColorSwatch[]`: Reihenfolge und Werte wie in den Global Constraints; `css` ist eine Anzeigefarbe, z. B. `hsl(0 100% 50%)`, Warmweiß `#ffd59a`.
  - `export const WARM_WHITE_K = 2700`
  - `export const COLOR_TEMP_STEP_K = 100`
  - `export interface ColorTempRange { min: number; max: number }`
  - `export function colorTempRange(metadata: Record<string, unknown> | null | undefined): ColorTempRange`: `colorTempRangeMin/Max` sind endliche Zahlen mit min < max, sonst `{min: 2500, max: 6500}`.
  - `export interface LightColorState { hue: number | null; saturation: number | null; colorTemp: number | null }`
  - `export function activeSwatch(state: LightColorState): ColorSwatch['id'] | null`:
    - Farbmodus (`colorTemp` null oder 0, `hue` bekannt): nächster Farbton zirkulär über 360°, nie `warm`.
    - Farbtemperatur-Modus (`colorTemp` > 0): `warm`, wenn `|colorTemp − 2700| ≤ 150`, sonst null.
    - Sonst null.
  - `export function sliderValue(state: LightColorState, range: ColorTempRange): number`: `colorTemp` > 0, auf den Bereich geklemmt, sonst die Bereichsmitte auf 100 gerundet.

- [ ] **Step 1: Failing Tests schreiben** (`light-color.util.spec.ts`)
  - `COLOR_SWATCHES` hat 8 Einträge, in dieser Reihenfolge: `warm, red, orange, yellow, green, cyan, blue, purple`. `warm.request` ist genau `{colorTemp: 2700}`, `blue.request` ist genau `{hue: 225, saturation: 100}`.
  - `colorTempRange({colorTempRangeMin: 2700, colorTempRangeMax: 6000})` → `{min: 2700, max: 6000}`.
  - `colorTempRange(null)` → `{min: 2500, max: 6500}`, ebenso bei `{colorTempRangeMin: 'x'}` und bei min ≥ max.
  - `activeSwatch({hue: 350, saturation: 90, colorTemp: 0})` → `'red'`. Das prüft den zirkulären Abstand.
  - `activeSwatch({hue: 220, saturation: 100, colorTemp: null})` → `'blue'`.
  - `activeSwatch({hue: 30, saturation: 100, colorTemp: 2700})` → `'warm'`. Farbtemperatur hat Vorrang, weil ein Wert > 0 den Farbtemperatur-Modus bedeutet.
  - `activeSwatch({hue: null, saturation: null, colorTemp: 2850})` → `'warm'`, bei 2851 → `null`.
  - `activeSwatch({hue: null, saturation: null, colorTemp: null})` → `null`.
  - `sliderValue({..., colorTemp: 7000}, {min: 2500, max: 6500})` → 6500. Ohne Wert → 4500.

- [ ] **Step 2: Laufen lassen, Fehlschlag sehen**
  Run: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless --include src/app/shared/house/light-color.util.spec.ts`
  Expected: FAIL, Modul nicht gefunden.

- [ ] **Step 3: Util schreiben** mit genau den Signaturen aus „Produces“.

- [ ] **Step 4: Laufen lassen, alles grün.**

- [ ] **Step 5: Commit**: `feat(house): Farblogik fuer Lampen im Detail-Feld`

### Task 2: Zusatzdaten und ihre Zeilen

**Files:**
- Create: `frontend/src/app/shared/house/detail-extras.util.ts`
- Test: `frontend/src/app/shared/house/detail-extras.util.spec.ts`

**Interfaces:**
- Consumes: `BubbleValue`, `MISSING`, `fresh` aus `house-bubbles.util`; `walkTimeRange`, `walkDuration`, `walkDistance` aus `shared/walk-format.util`; `petSupplyTone` aus `shared/pet-supply-level.util`; Modelle `BlinkCamera`, `TractivePet`, `TractiveWalk`, `PetSupply`, `WasteCollectionEvent`, `ThermostatMode`; `ColorTempRange` aus Task 1.
- Produces:
  - Typen:

    ```ts
    export interface LightExtras {
      dimmable: boolean; brightness: number | null;
      supportsColor: boolean; supportsColorTemp: boolean;
      hue: number | null; saturation: number | null; colorTemp: number | null;
      colorTempRange: ColorTempRange;
    }
    export interface LockExtras { smartlockId: number; batteryCharge: number | null; batteryCritical: boolean }
    export interface PetExtras { pet: TractivePet | null; lastWalk: TractiveWalk | null }
    /** Ein fehlender Schluessel heisst: noch nie erfolgreich geladen. */
    export interface DetailExtras {
      light?: LightExtras | null;              // null = kein Smart-Device zur Entity
      roomValue?: BubbleValue | null;
      thermostatMode?: ThermostatMode | null;
      lock?: LockExtras | null;                // null = Schloss nicht in der Liste
      camera?: BlinkCamera | null;             // null = Kamera nicht in der Liste
      pet?: PetExtras;
      supplies?: PetSupply[];
      waste?: WasteCollectionEvent[];
    }
    ```

    `LightInfo` aus dem Dienst wird durch `LightExtras` ersetzt (Task 4).
  - `export function wasteDayText(isoDate: string, today: Date): string`: „heute“, „morgen“, sonst z. B. „Do 12.11.“ (Wochentag kurz, deutsch, ohne Punkt am Wochentag, Datum `TT.MM.`).
  - `export function wasteLines(events: WasteCollectionEvent[], today: Date): BubbleValue[]`:
    - Termine vor `today` (Datumsvergleich auf Tagesbasis, `daysUntil` wird ignoriert) entfallen.
    - Je `label` bleibt der früheste Termin, sortiert nach Datum.
    - Text `"<label> · <wasteDayText>"`.
  - `export const NO_WASTE_TEXT = 'Keine Abfuhr in den nächsten Tagen'`
  - `export function petLines(pet: PetExtras | undefined, supplies: PetSupply[] | undefined): BubbleValue[]`, in dieser Reihenfolge:
    - Akku: „Akku 80 %“, mit „ · lädt“ wenn `charging`. Ohne `pet` oder ohne `batteryPercent` steht `MISSING` mit Text „Akku –“.
    - Spaziergang: „Spaziergang <walkTimeRange> · <walkDuration> · <walkDistance>“. Ist `lastWalk` null (geladen, keiner), steht „kein Spaziergang in 7 Tagen“. Fehlt `pet`, steht „Spaziergang –“.
    - je Vorrat: „<name> <amountRemaining> <unit> · noch <daysRemaining> Tage“, `stale: false`. Fehlt `supplies`, entfallen diese Zeilen.
  - `export function motionLine(lastMotionAt: string | null | undefined, now: Date): BubbleValue | null`: ohne Wert `null`. Am selben Tag „letzte Bewegung 14:05“, sonst „letzte Bewegung 03.11. 14:05“.
  - `export function lockBatteryLine(lock: LockExtras | null | undefined): BubbleValue | null`: ohne `lock` oder ohne `batteryCharge` `null`. Sonst „Akku 64 %“, dazu „ · schwach“, wenn `batteryCritical`.
  - `export function smartlockIdOf(entityId: string): number | null`: `'lock.nuki_18241688572'` → 18241688572, nicht numerisch → null.
  - `export function cameraIdOf(entityId: string): string | null`: `'binary_sensor.blink_511671_armed'` → `'511671'`.
  - `export function trackerIdOf(entityId: string): string | null`: `'binary_sensor.tractive_pqbzjech_home'` → `'pqbzjech'`.

- [ ] **Step 1: Failing Tests** (`today = new Date(2026, 10, 9)`, ein Montag):
  - `wasteDayText('2026-11-09', today)` → `'heute'`, `'2026-11-10'` → `'morgen'`, `'2026-11-12'` → `'Do 12.11.'`.
  - `wasteLines` mit `[{date:'2026-11-08', label:'Bio', daysUntil:0}, {date:'2026-11-16', label:'Bio', daysUntil:7}, {date:'2026-11-12', label:'Restmüll', daysUntil:3}, {date:'2026-11-26', label:'Restmüll', daysUntil:17}]` → Texte `['Restmüll · Do 12.11.', 'Bio · Mo 16.11.']`. Der gestrige Bio-Termin entfällt trotz `daysUntil: 0`.
  - `wasteLines([], today)` → `[]`.
  - `petLines({pet: {..., batteryPercent: 80, charging: true}, lastWalk: null}, undefined)` → Texte `['Akku 80 % · lädt', 'kein Spaziergang in 7 Tagen']`.
  - `petLines(undefined, undefined)` → `['Akku –', 'Spaziergang –']`, beide `stale: true`.
  - `petLines` mit `batteryPercent: 0` → `'Akku 0 %'`. Null ist ein echter Wert und kein „–“.
  - Vorratszeile: `{name:'Futter', amountRemaining: 12.5, unit:'Dosen', daysRemaining: 12, ...}` → `'Futter 12,5 Dosen · noch 12 Tage'`. Das Dezimalkomma ist deutsch.
  - `motionLine('2026-11-09T14:05:00', new Date(2026,10,9,18))` → `'letzte Bewegung 14:05'`; am 10.11. → `'letzte Bewegung 09.11. 14:05'`; `null` → `null`.
  - `lockBatteryLine({smartlockId:1, batteryCharge: 64, batteryCritical: true})` → `'Akku 64 % · schwach'`; `batteryCharge: null` → `null`.
  - `smartlockIdOf('lock.nuki_abc')` → `null`. `cameraIdOf` und `trackerIdOf` wie oben.

- [ ] **Step 2: Laufen lassen, FAIL.**
- [ ] **Step 3: Implementieren.** Die Vorratszeilen tragen keinen Ton. Die Reichweite steht im Text, und `BubbleValue` kennt kein Ton-Feld.
- [ ] **Step 4: Laufen lassen, grün.**
- [ ] **Step 5: Commit**: `feat(house): Zeilen fuer Tuer, Kamera, Muell und Toni`

### Task 3: Feldmodell um Zusatzdaten und neue Bedienelemente erweitern

**Files:**
- Modify: `frontend/src/app/shared/house/device-detail.util.ts`
- Test: `frontend/src/app/shared/house/device-detail.util.spec.ts`

**Interfaces:**
- Consumes: alles aus Task 1 und 2.
- Produces:
  - `DetailControl` bekommt diese Erweiterungen und Varianten:
    - `target`-Variante: zusätzlich `mode: ThermostatMode | null`.
    - `switch`-Variante: zusätzlich `color: LightExtras | null`. Es ist nur gesetzt, wenn `supportsColor || supportsColorTemp`. `dimmable` kommt jetzt aus `extras.light?.dimmable === true`.
    - Neu `{ type: 'lock'; smartlockId: number; locked: boolean | null }`:
      - `locked` ist `true` bei Entity-State `locked`, `false` bei jedem anderen lesbaren State, und `null` bei nicht lesbarem State.
      - Gibt es keine `smartlockId`, steht `control: null`.
    - Neu `{ type: 'camera'; cameraId: string; camera: BlinkCamera | null | undefined }`: `undefined` heißt noch nicht geladen, `null` heißt nicht erreichbar.
  - `buildDeviceDetail(device, entities, nowMs, extras: DetailExtras = {}): DeviceDetail`. Neue Fälle:
    - `thermostat`: hängt `extras.roomValue` als Zeile `"Raum <text>"` an. Das wandert aus `tablet-house.component.ts`.
    - `door`:
      - Kopf: Schlosstext. Nutzt eine neu exportierte `lockText` aus `house-bubbles.util`, vorher privat.
      - Zeilen: Tür „offen“/„zu“ aus dem Kontakt plus `sinceLine`, dann `lockBatteryLine`.
      - Bedienelement: `lock`.
      - Kein Verlauf.
    - `camera`:
      - Kopf: „aktiv“ oder „deaktiviert“ aus `extras.camera.armed`, sonst `MISSING`.
      - Zeile: `motionLine`.
      - Bedienelement: `camera`.
    - `waste`: Kopf ist die erste Zeile von `wasteLines`. Ohne Termine ist der Kopf `fresh(NO_WASTE_TEXT)`, bei `extras.waste` undefined `MISSING`. Die Zeilen sind die übrigen `wasteLines`. Kein Bedienelement, `today = new Date(nowMs)`.
    - `pet`: Kopf „zu Hause“ (`on`) oder „unterwegs“ (`off`) aus `homeEntityId`, sonst `MISSING`. Zeilen: `petLines`.
  - `opensDetail(kind)` ist für alle Arten `true`. Bleibt als einzige Regel bestehen. `bubbleTarget` bleibt als Rückfall in `openDevice` bei fehlendem Anker stehen.

- [ ] **Step 1: Failing Tests** zusätzlich zu den bestehenden. Geräte über `HOUSE_DEVICES.find(d => d.id === 'front-door')` usw.
  - `opensDetail` ist für jede `kind` in `HOUSE_DEVICES` wahr.
  - Tür mit Lock-State `unlocked`, Kontakt `off`, `extras.lock = {smartlockId: 18241688572, batteryCharge: 64, batteryCritical: false}`:
    - Kopf ist „entriegelt“.
    - Zeilen enthalten „zu“ und „Akku 64 %“.
    - Bedienelement ist `{type:'lock', smartlockId: 18241688572, locked: false}`.
  - Tür mit Lock-State `unavailable` → `locked: null`.
  - Kamera mit `extras.camera = null` → `control.camera === null`; ohne `extras` → `undefined`.
  - Mülltonne mit `extras.waste = []` → Kopf `'Keine Abfuhr in den nächsten Tagen'`.
  - Toni, Entity `on` → Kopf `'zu Hause'`.
  - Thermostat mit `extras.roomValue = {text:'19,8°', stale:false}` → letzte Zeile `'Raum 19,8°'`, `control.mode` aus `extras.thermostatMode`.
  - Schalter mit `extras.light` und `supportsColor: true` → `control.color` gesetzt, `dimmable` aus `extras`. Bei `supportsColor: false, supportsColorTemp: false` ist `color` null.
- [ ] **Step 2: FAIL sehen.**
- [ ] **Step 3: Implementieren.** `lockText` exportieren. Die Zuordnung von `extras` stellt die Seite in Task 5 um.
- [ ] **Step 4: Grün** (Datei `device-detail.util.spec.ts` und `house-bubbles.util.spec.ts`).
- [ ] **Step 5: Commit**: `feat(house): Detail-Feld fuer Tuer, Kamera, Muell und Toni`

### Task 4: Dienst – Zusatzdaten laden und neue Aktionen

**Files:**
- Modify: `frontend/src/app/pages/tablet-house/house-device-control.service.ts`
- Test: `frontend/src/app/pages/tablet-house/house-device-control.service.spec.ts`

**Interfaces:**
- Consumes: `NukiService.getLocks/sendAction`, `BlinkService.getCameras/setCameraArmed/takeSnapshot/thumbnailUrl`, `TractiveService.getPets/getWalks`, `PetSupplyService.getSupplies`, `WasteCollectionService.getUpcoming`, `ThermostatService.list/setMode`, Task 1 bis 3.
- Produces:
  - `lightDevice(entityId): Observable<LightExtras | null>`. Die Signatur ändert sich, `LightInfo` entfällt:
    - `supportsColor` kommt aus `capabilities.includes('COLOR')`, `supportsColorTemp` aus `'COLOR_TEMP'`.
    - `hue`, `saturation`, `colorTemp` sind `?? null`.
    - `colorTempRange` ist `colorTempRange(device.metadata)`.
  - `setLightColor(entityId: string, request: LightColorRequest): Observable<BrightnessOutcome>`: gleicher Ablauf wie `setBrightness` (frisch lesen, nur bei `on` schreiben, danach Cache verwerfen). Beide nutzen eine private `writeLight(entityId, state: LightStateRequest)`.
  - `export type ActionOutcome = 'done' | 'already' | 'unavailable'`
  - `setThermostatMode(thermostatId: number, mode: ThermostatMode): Observable<ActionOutcome>`: liest `thermostats.list()` frisch. Fehlt das Thermostat → `unavailable`. Ist `mode` schon gesetzt → `already`. Sonst `setMode` → `done`.
  - `lockDoor(smartlockId: number): Observable<ActionOutcome>`: liest `getLocks()` frisch. Fehlt das Schloss → `unavailable`. `state === 'locked'` → `already`. Sonst `sendAction(id, 'LOCK')` → `done`.
  - `setCameraArmed(cameraId: string, armed: boolean): Observable<ActionOutcome>`: liest `getCameras()` frisch. Fehlt die Kamera → `unavailable`. `armed` schon gleich → `already`. Sonst schalten → `done`.
  - `takeSnapshot(cameraId: string): Observable<void>`
  - `thumbnailUrl(cameraId: string, cacheKey: number): string`
  - `loadExtras(device: HouseDevice): Observable<Partial<DetailExtras>>`:
    - Je Art die Quellen parallel (`forkJoin`). Jede Quelle hat ihr eigenes `catchError`, eine gescheiterte Quelle fehlt im Ergebnis. Das Observable selbst scheitert nie.
    - `switch` → `light`.
    - `thermostat` → `roomValue` und `thermostatMode`. **Beide aus einem `thermostats.list()`.** `thermostatRoom` wird intern so umgebaut, dass es die Liste übernimmt, statt sie selbst zu holen.
    - `door` → `lock`.
    - `camera` → `camera`.
    - `waste` → `waste` (`getUpcoming()` ohne `days`).
    - `pet` → `pet` und `supplies`:
      - `pet.pet` ist das Tier mit `trackerId.toLowerCase() === trackerIdOf(homeEntityId)`, sonst null.
      - `pet.lastWalk` ist der Spaziergang mit dem spätesten `end` aus `getWalks(trackerId, 7)`, ohne Tier oder ohne Spaziergang null.
    - Andere Arten → `of({})`.

- [ ] **Step 1: Failing Tests** (Services per `jasmine.createSpyObj`):
  - `setLightColor` mit Schalterliste `state: 'off'` → `'lamp-off'`, `setLightState` nie aufgerufen.
  - `setLightColor` bei `on` → `setLightState(deviceId, {hue: 225, saturation: 100})`.
  - `lockDoor`:
    - `getLocks` liefert `state: 'locked'` → `'already'`, `sendAction` nie aufgerufen.
    - `state: 'unlocked'` → `sendAction(id, 'LOCK')`.
    - Schloss fehlt → `'unavailable'`.
  - `setCameraArmed('511671', false)`:
    - Liste meldet `armed: false` → `'already'`, `setCameraArmed` am BlinkService nie aufgerufen.
    - Kamera fehlt → `'unavailable'`.
  - `setThermostatMode(8, 'OFF')` bei gemeldetem `OFF` → `'already'`.
  - `loadExtras(Toni)` mit `getPets` → `[{trackerId: 'PQBZJECH', batteryPercent: 80, ...}]` und zwei Spaziergängen → `pet.pet.batteryPercent === 80`, `lastWalk` ist der spätere.
  - `loadExtras(Toni)`, wenn `getPets` scheitert → das Ergebnis enthält `supplies`, aber keinen Schlüssel `pet`.
  - `loadExtras(Thermostat)` ruft `thermostats.list` genau einmal.
  - `lightDevice` mapt `COLOR`, `COLOR_TEMP` und die Metadata-Spanne.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementieren.** Bestehende Tests zu `lightDevice` und `thermostatRoom` an die neuen Formen anpassen, nicht löschen.
- [ ] **Step 4: Grün.**
- [ ] **Step 5: Commit**: `feat(house): Dienst laedt Zusatzdaten und schaltet Tuer, Kamera, Modus, Farbe`

### Task 5: Seite – Zusatzdaten im Takt, späte Antworten verwerfen

**Files:**
- Modify: `frontend/src/app/pages/tablet-house/tablet-house.component.ts`, `tablet-house.component.html`
- Test: `frontend/src/app/pages/tablet-house/tablet-house.component.spec.ts`

**Interfaces:**
- Consumes: `loadExtras`, `buildDeviceDetail(..., extras)`.
- Produces:
  - Signal `openExtras = signal<DetailExtras>({})`. Ersetzt `openLight` und `openRoomValue`.
  - `detail` ist `buildDeviceDetail(device, entities(), now(), openExtras())`, ohne eigene Nachbearbeitung.
  - `detailBrightness` ist `openExtras().light?.brightness ?? null`.

Ablauf:

- `openDevice` setzt `openExtras({})` und stößt sofort einen Abruf an.
- Ein Abruf läuft außerdem bei jedem Tick des bestehenden `ticks`-Stroms, und zwar nur, wenn ein Feld offen ist. `refreshAfterAction` stößt über `refreshRequests` ebenfalls an, dadurch entfällt `reloadLight`.
- Zusammenführen: `openExtras.update(prev => ({...prev, ...partial}))`, aber **nur, wenn die Antwort zur aktuell offenen Id gehört**. Die Id wird beim Start des Abrufs gemerkt und vor dem Schreiben verglichen; `switchMap` bricht zusätzlich den alten Abruf ab.
- `dropDetail` setzt `openExtras({})`.
- `loadDetailExtras`, `reloadLight` und `detailExtras` (die Subscription) entfallen.

- [ ] **Step 1: Failing Tests:**
  - Gerät A (Thermostat) öffnen. Die Extras von A kommen verzögert (`Subject`), dazwischen wird Gerät B (Schalter) geöffnet. Danach emittiert A `{roomValue: ...}`, und das Feld von B zeigt keine „Raum“-Zeile.
  - Bei offenem Feld führt ein Tick (Entity-Refresh) zu einem weiteren `loadExtras`-Aufruf. Ohne offenes Feld gibt es keinen Aufruf.
  - Ein zweiter Abruf mit `{}` (alle Quellen gescheitert) behält die zuvor geladenen `roomValue` bei.
  - Ein Tipp auf die Haustür-Bubble im EG-Überblick öffnet das Feld und navigiert nicht. Der Router-Spy wird nicht aufgerufen.
  - Bestehende Tests zu Helligkeit und Raumwert laufen über den neuen Weg weiter.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: Grün** (Seite, Detail-Komponente, Utils).
- [ ] **Step 5: Commit**: `feat(house): Seite laedt Zusatzdaten des Feldes im Takt`

### Task 6: Farbfelder und Farbtemperatur-Regler

**Files:**
- Create: `frontend/src/app/pages/tablet-house/device-detail/light-color-control.component.{ts,html,scss,spec.ts}`
- Modify: `house-device-detail.component.html` (Einbindung unter dem Helligkeitsregler, wenn `sw.color`)

**Interfaces:**
- Consumes: `LightExtras`, `COLOR_SWATCHES`, `activeSwatch`, `sliderValue`, `COLOR_TEMP_STEP_K`, `HouseDeviceControlService.setLightColor`, `LAMP_OFF`.
- Produces: `<app-light-color-control [entityId] [color] [lampOn] (changed)>`, selector `app-light-color-control`.

Verhalten:

- Die Farbfelder erscheinen nur bei `supportsColor`, der Regler (`type="range"`) nur bei `supportsColorTemp`.
- `min`, `max` und `step` des Reglers kommen aus `colorTempRange` und `COLOR_TEMP_STEP_K`. Die Regler-Spur ist ein Verlauf von warm nach kalt.
- Das aktive Feld trägt die Klasse `light-color__swatch--active` und `aria-pressed="true"`.
- **Ruling zu Warmweiß:** Die Farbfelder hängen an COLOR. Warmweiß ist aber ein Farbtemperatur-Wert und erscheint deshalb nur, wenn das Gerät zusätzlich COLOR_TEMP kann. COLOR allein ergibt 7 Felder, COLOR und COLOR_TEMP ergeben 8. COLOR_TEMP allein ergibt keine Felder, nur den Regler.
- Alles ist gesperrt bei `lampOn !== true` und während eines Schreibens.
- Ein Feld sendet beim Antippen, der Regler bei `change`.
- Ergebnisse:
  - `lamp-off` → Fehlertext `LAMP_OFF`, dann `changed.emit()`.
  - Fehler (HTTP) → „Nicht übernommen“. Die Auswahl springt nicht zurück: Beim Regler bleibt der DOM-Wert stehen.
  - `set` → `changed.emit()`.

- [ ] **Step 1: Failing Tests:**
  - Mit `supportsColor: true, supportsColorTemp: true` erscheinen 8 Felder und ein Regler. Mit nur `supportsColorTemp` gibt es keine Farbfelder, aber den Regler. Mit nur `supportsColor` gibt es 7 Felder ohne Warmweiß.
  - Tipp auf Blau ruft `setLightColor(entityId, {hue: 225, saturation: 100})` und emittiert `changed`.
  - Bei `lampOn: false` sind alle Felder `disabled`, ein Klick ruft nichts auf.
  - Dienst liefert `'lamp-off'` → der Text „Lampe ist aus“ steht im DOM.
  - Dienst wirft → „Nicht übernommen“, und der Reglerwert bleibt auf dem gewählten Wert.
  - `color: {hue: 220, saturation: 100, colorTemp: 0, ...}` → Blau trägt `aria-pressed="true"`.
- [ ] **Step 2: FAIL.**
- [ ] **Step 3: Implementieren.** Einbindung im Detail-Template mit `[lampOn]="sw.on"` und `(changed)="changed.emit()"`. Dafür bekommt die Detail-Komponente eine öffentliche Methode `emitChanged()`, oder das Template ruft direkt `changed.emit()`.
- [ ] **Step 4: Grün.**
- [ ] **Step 5: Commit**: `feat(house): Farbe und Farbtemperatur im Detail-Feld`

### Task 7: Thermostat-Modus

**Files:**
- Create: `frontend/src/app/pages/tablet-house/device-detail/thermostat-mode-control.component.{ts,html,scss,spec.ts}`
- Modify: `house-device-detail.component.html` (unter −/+, wenn `targetControl`)

**Interfaces:**
- Consumes: `setThermostatMode`, `ThermostatMode`, `TARGET_GRACE_MS` (60 000, aus der Detail-Komponente exportiert).
- Produces: `<app-thermostat-mode-control [thermostatId] [mode] (changed)>`.

Verhalten:

- Drei Knöpfe in der Reihenfolge Aus (`OFF`), Manuell (`MANUAL`), Frostschutz (`FROST_PROTECTION`).
- Hervorgehoben ist `localMode ?? mode`. `localMode` wird nach `done` gesetzt und gilt, bis `mode` ihn meldet oder `TARGET_GRACE_MS` vergangen ist.
- Ein Wechsel von `thermostatId` setzt `localMode` zurück.
- Antippen des schon aktiven Knopfs tut nichts.
- Während des Schreibens sind die Knöpfe gesperrt.
- `already` und `done` → `changed.emit()`; `unavailable` → „Gerät nicht erreichbar“ (`UNREACHABLE`); Fehler → ebenso.
- − / + bleiben im Modus Aus bedienbar, die Detail-Komponente ändert sich dafür nicht.

- [ ] **Step 1: Failing Tests:**
  - Es gibt drei Knöpfe mit den Texten „Aus“, „Manuell“, „Frostschutz“. Bei `mode: 'MANUAL'` trägt Manuell `aria-pressed="true"`.
  - Tipp auf Frostschutz ruft `setThermostatMode(8, 'FROST_PROTECTION')`. Danach ist Frostschutz hervorgehoben, auch wenn `mode` noch `MANUAL` ist.
  - Nach `jasmine.clock().tick(60_000)` zählt wieder `mode`.
  - Tipp auf den aktiven Knopf ruft nichts auf.
- [ ] **Step 2: FAIL.** **Step 3: Implementieren.** **Step 4: Grün.**
- [ ] **Step 5: Commit**: `feat(house): Thermostat-Modus im Detail-Feld`

### Task 8: Haustür verriegeln

**Files:**
- Create: `frontend/src/app/pages/tablet-house/device-detail/door-lock-control.component.{ts,html,scss,spec.ts}`
- Modify: `house-device-detail.component.ts` (Getter `lockControl`), `.html`

**Interfaces:**
- Consumes: `lockDoor`, Control-Variante `lock` aus Task 3.
- Produces: `<app-door-lock-control [control] (changed)>`.

Verhalten:

- Knopf „Verriegeln“ nur bei `control.locked === false`, sonst gar kein Knopf.
- Kein Dialog.
- Während des Schreibens ist der Knopf gesperrt.
- `done` und `already` → `changed.emit()`; `unavailable` oder Fehler → `UNREACHABLE`.

- [ ] **Step 1: Failing Tests:**
  - `locked: false` → genau ein Knopf mit dem Text „Verriegeln“. Ein Tipp ruft `lockDoor(18241688572)` und emittiert `changed`.
  - `locked: true` und `locked: null` → kein Knopf.
  - **Sicherheitstest im Detail-Feld** (`house-device-detail.component.spec.ts`): Mit Tür-Detail und `locked: false` enthält der DOM-Text weder „Entriegeln“ noch „Öffnen“ noch „öffnen“.
  - Repo-weiter Grep-Test als Verifikationsschritt: `grep -rn "UNLOCK\|UNLATCH" frontend/src/app/pages/tablet-house frontend/src/app/shared/house` liefert nichts.
- [ ] **Step 2: FAIL.** **Step 3: Implementieren.** **Step 4: Grün, und der Grep ist leer.**
- [ ] **Step 5: Commit**: `feat(house): Haustuer im Detail-Feld (nur verriegeln)`

### Task 9: Kamera im Feld

**Files:**
- Create: `frontend/src/app/pages/tablet-house/device-detail/camera-control.component.{ts,html,scss,spec.ts}`
- Modify: `house-device-detail.component.ts` (Getter `cameraControl`), `.html`

**Interfaces:**
- Consumes: `setCameraArmed`, `takeSnapshot`, `thumbnailUrl`, Control-Variante `camera`.
- Produces: `<app-camera-control [control] (changed)>`.

Verhalten:

- **Noch nicht geladen** (`camera === undefined`): Hinweis „Lade Kamera …“.
- **Nicht erreichbar** (`camera === null`): nur der Text „Kamera nicht erreichbar“, kein Bild, kein Schalter, kein Knopf.
- **Standbild:** `<img [src]="thumbnailUrl(cameraId, cacheKey)">`.
  - `cacheKey` startet beim Erzeugen mit `Date.now()` und ändert sich **nur** nach einem erfolgreichen Schnappschuss, nicht bei einem neuen `control` aus dem Takt.
  - Bei `(error)` erscheint ein Platzhalter. Ein neuer `cacheKey` blendet ihn wieder aus.
- **Schalter „Bewegungserkennung“** (`role="switch"`, `aria-checked` = `camera.armed`):
  - Einschalten geht direkt.
  - Ausschalten öffnet einen Bestätigungsdialog mit dem Text „Bewegungserkennung der Kamera deaktivieren?“ und den Knöpfen „Abbrechen“ / „Deaktivieren“.
  - Nach dem Bestätigen ruft er `setCameraArmed(id, false)` auf. Der Dienst prüft frisch, verschwundene oder schon deaktivierte Kameras schaltet er nicht.
  - Während der Aktion erscheint ein Spinner, und der Schalter ist gesperrt.
  - `done`/`already` → `changed.emit()`; `unavailable`/Fehler → `UNREACHABLE`.
- **Knopf „Schnappschuss“:**
  - Spinner und gesperrt während der Aktion.
  - Erfolg → `cacheKey = Date.now()`.
  - Fehler → „Schnappschuss fehlgeschlagen“, das Bild bleibt.

- [ ] **Step 1: Failing Tests:**
  - `camera: null` → Text „Kamera nicht erreichbar“, kein `img`, kein `[role=switch]`.
  - Schalter bei `armed: true` antippen → Dialog erscheint, `setCameraArmed` ist noch nicht aufgerufen. Bei „Abbrechen“ wird nichts aufgerufen, bei „Deaktivieren“ `setCameraArmed('511671', false)`.
  - Schalter bei `armed: false` antippen → sofort `setCameraArmed('511671', true)`, kein Dialog.
  - Neues `control` mit gleicher Kamera (Takt) → die `img`-`src` bleibt gleich.
  - Schnappschuss erfolgreich → `src` ändert sich. Schnappschuss scheitert → `src` gleich, Text „Schnappschuss fehlgeschlagen“.
- [ ] **Step 2: FAIL.** **Step 3: Implementieren.** **Step 4: Grün.**
- [ ] **Step 5: Commit**: `feat(house): Kamera im Detail-Feld`

### Task 10: Gesamtlauf, Browserprüfung, Doku

**Files:**
- Modify: `CLAUDE.md` (Abschnitt „Digitaler Zwilling (3D-Hausansicht)“)

Änderungen in `CLAUDE.md`:

- Der Absatz „Geräte-Detail statt Seitenwechsel“ sagt jetzt: Alle Gerätearten öffnen das Feld.
- Neuer Absatz „Detail-Feld Stufe 3“:
  - `loadExtras` mit dem Takt-Verhalten und dem Verwerfen später Antworten
  - Farbfelder (Werte und Warmweiß-Regel)
  - Modus-Knöpfe
  - Tür nur verriegeln (kein UNLOCK/UNLATCH im Code)
  - Kamera (Bild nur beim Öffnen und nach einem Schnappschuss, Deaktivieren mit Dialog)
  - Mülltonne (Datum aus `date`, nicht `daysUntil`)
  - Toni (Tracker-Id ohne Groß-/Kleinschreibung)
- In den „Bekannten Grenzen v1“ entfällt der Punkt „Haustür, Kamera, Mülltonne und Toni führen weiter nur auf die Seite …“, ebenso „Farbe/Farbtemperatur und Thermostat-Modus sind … nicht bedienbar“. Neu hinzu kommt: Der Farb-Schreibpfad ist am echten Gerät nie gelaufen.

- [ ] **Step 1:** Volle Frontend-Tests: `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`. Erwartet: nur die 3 Baseline-Fails.
- [ ] **Step 2:** `cd frontend && npx ng build --configuration production`. Erwartet: Build erfolgreich. Die bekannte `anyComponentStyle`-Budgetwarnung des Dashboards ist keine Regression.
- [ ] **Step 3: Browserprüfung** über den Testserver auf :4300. Der Proxy zeigt auf PROD, mit Origin-Rewrite, siehe Memory „Digitaler Zwilling 3D“. Den Login macht der Nutzer selbst.
  - Nur lesend prüfen: Felder von Haustür, Mülltonne, Toni und Kamera öffnen und den Text per `read_page` vergleichen.
  - Ohne Freigabe des Nutzers nichts schalten.
  - Nach einem Screenshot fragen.
- [ ] **Step 4:** CLAUDE.md wie oben ergänzen.
- [ ] **Step 5: Commit**: `docs(house): Detail-Feld Stufe 3 in CLAUDE.md`
