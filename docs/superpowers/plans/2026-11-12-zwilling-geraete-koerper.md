# Großgeräte als Körper im Raum Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Neun Großgeräte stehen in `/tablet/house` als halbtransparente 3D-Körper, die ihren Zustand zeigen.

**Architecture:** Eine reine Util `shared/house/house-appliance-bodies.util.ts` leitet aus Gerät, Entitäten und Plätzen Form, Lage, Drehung und Zustand ab (Muster `openingShape`). Die Tablet-Komponente reicht das Ergebnis per `setBodies` an den Renderer; Three.js bleibt im Renderer. `buildHouseEffects` lässt für Geräte mit Körper `powered`/`running` weg.

**Tech Stack:** Angular 19 (Signals), Three.js, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-11-12-zwilling-geraete-koerper-design.md`

## Global Constraints

- Keine Backend-, DB- oder Admin-Seiten-Änderung.
- Three.js nur in `pages/tablet-house/house-scene-renderer.ts`; Render-on-demand, Körper animieren nicht.
- Farben: Fläche `rgba(143,211,255,0.10)`, Rand `#b9e6ff`, aktiv `#5ff2d6`, Bildschirm aktiv `rgba(140,170,255,0.55)`, fertig gelb, Alarm `#ff8f8f`.
- Schwelle 10 W (strikt größer), Messung höchstens 5 min alt.
- Testbasis: nur AppComponent ×2 und HeroComponent rot.

## Review Focus

- Watt genau 10 → nicht aktiv; Messung genau 5 min alt → noch frisch, älter → idle.
- Schalter `on`, Watt `unavailable` oder keine Zahl → idle (nie geraten).
- Kühlschrank `unavailable` → idle, kein Alarm.
- Gerät auf Rasterplatz in L-förmigem Raum: Wand zwischen zwei Rechtecken desselben Raums ist keine Wand.
- Fernseher ohne gespeicherten Platz steht an der Wand, seine Bubble nicht.

---

### Task 1: Körper-Regeln (`house-appliance-bodies.util.ts`)

**Files:**
- Modify: `shared/house/house-devices.ts` (Feld `body?: ApplianceBodyKind` an `switch` und `appliance`, an den neun Einträgen gesetzt)
- Create: `shared/house/house-appliance-bodies.util.ts`, `.spec.ts`

**Interfaces — Produces:**
```ts
export type ApplianceBodyKind = 'washer' | 'dishwasher' | 'fridge' | 'tv' | 'pc' | 'printer' | 'treadmill' | 'coffee';
export type ApplianceBodyState = 'idle' | 'active' | 'finished' | 'alarm';
export const ACTIVE_POWER_THRESHOLD_W = 10;
export const POWER_FRESH_MS = 5 * 60_000;
export interface ApplianceBody {
  deviceId: string; floor: FloorId; kind: ApplianceBodyKind; state: ApplianceBodyState;
  /** Mittelpunkt der Grundfläche (Welt), y = Unterkante. */ base: Vec3;
  /** Drehung um y: lokale +z ist die Vorderseite. */ rotationY: number;
  widthM: number; depthM: number; heightM: number;
}
export function bodyDeviceIds(): ReadonlySet<string>;
export function applianceBodyState(device: HouseDevice, entities: EntityMap, nowMs: number): ApplianceBodyState;
export function buildApplianceBodies(floor: FloorId, entities: EntityMap, positions: DevicePositions, nowMs: number): ApplianceBody[];
```
- Maße/Unterkante wie Spec-Tabelle; Fernseher: Mitte 1,20 m ⇒ Unterkante 0,85 m, zur Wand geschoben (Rückseite auf der Wand). Laufband: Breite 0,8, Tiefe 1,8, Höhe 1,3 (Pult), Renderer zeichnet Lauffläche 0,15 m.
- Wandwahl: Kanten der Rechtecke des eigenen Bereichs; nur Kanten, deren Lotfußpunkt auf der Kante liegt und hinter denen (0,5 Plan-Einheiten nach außen) nicht derselbe Bereich liegt; kleinster Abstand gewinnt, Gleichstand in fester Reihenfolge oben, rechts, unten, links. Ohne Kandidat: Vorderseite +z.
- Tests: alle vier Zustände je Art inkl. Review-Focus-Grenzfälle; Ausrichtung zu jeder der vier Wände; L-Raum-Innenkante; Gleichstand; Fernseher an der Wand; nur Geräte der Etage; Rasterplatz bei ungültigem Platz.

### Task 2: Effekte ausdünnen

**Files:** `shared/house/house-effects.util.ts` (+ spec)
- `effectOf` liefert für Geräte aus `bodyDeviceIds()` weder `powered` noch `running`. Lampen-, Heizungs-, Kontakt-, Bewegungs-Effekte unverändert.
- Test: Fernseher an ⇒ kein Effekt; Waschmaschine läuft ⇒ kein `running`; Lampe an ⇒ weiter `light`.

### Task 3: Renderer und Komponente

**Files:** `pages/tablet-house/house-scene-renderer.ts`, `tablet-house.component.ts` (+ spec)
- Renderer: `setBodies(bodies: readonly ApplianceBody[]): void` — je Etage Gruppe `bodies` in der Layer-Gruppe (Muster `openings`), Quader als Mesh + EdgesGeometry, Merkmal je Art auf der Vorderseite, geteilte Materialien je Zustand, `dispose` räumt auf.
- Komponente: `computed` `applianceBodies` (alle Etagen; Sichtbarkeit regelt der Layer) mit Gleichheitsprüfung, `effect` → `setBodies`, beim Erzeugen des Renderers einmal setzen; Fake-Renderer bekommt `setBodies`.
- Tests: Körper kommen an den Fake; Zustandswechsel aktualisiert; Bubble des Fernsehers bleibt in der Raumansicht.

### Abschluss
- Volle Frontend-Tests, Browserprüfung (Dev-Server :4300 gegen PROD, Mock), Review des Branches, CLAUDE.md-Abschnitt, Commit.
