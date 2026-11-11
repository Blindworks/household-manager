import { HouseOpening, OpeningHinge, OpeningKind, OpeningSize, OpeningSwing } from '../../models/house-opening.model';
import { EntityMap, isOn, isWindowContact } from './house-bubbles.util';
import { HOUSE_DEVICES, HouseDevice } from './house-devices';
import { Vec3, floorBase, toWorldX, toWorldZ } from './house-geometry.util';
import { FloorId, HOUSE_ROOMS, UNITS_PER_METER } from './house-plan';
import { PlanPoint, isPointInArea } from './house-placement.util';

/**
 * Fenster und Tueren an den Waenden des Digitalen Zwillings. Einzige Definition von
 * Groessen, Waenden, Einrasten, Wandseiten, Ueberlappung und 3D-Geometrie; Renderer,
 * Tablet-Ansicht und Admin-Seite fragen alle hier.
 */

/** Waagerechte oder senkrechte Strecke in Plan-Einheiten; normalisiert ist (x1, y1) der kleinere Endpunkt. */
export interface Segment { x1: number; y1: number; x2: number; y2: number }

/** Breite, Bruestung (Unterkante) und Oberkante in Metern ueber dem Etagenboden. */
export interface OpeningDimensions { widthM: number; sillM: number; topM: number }

export const OPENING_DIMENSIONS: Record<OpeningKind, Partial<Record<OpeningSize, OpeningDimensions>>> = {
  WINDOW: {
    SMALL: { widthM: 0.6, sillM: 0.9, topM: 2.1 },
    NORMAL: { widthM: 1.0, sillM: 0.9, topM: 2.1 },
    WIDE: { widthM: 1.6, sillM: 0.9, topM: 2.1 }
  },
  DOOR: {
    NORMAL: { widthM: 0.9, sillM: 0, topM: 2.1 },
    /** Zwei Fluegel mit Anschlag an beiden Enden, je halbe Breite. */
    TERRACE: { widthM: 1.8, sillM: 0, topM: 2.1 }
  }
};

export const SIZES_BY_KIND: Record<OpeningKind, OpeningSize[]> = {
  WINDOW: ['SMALL', 'NORMAL', 'WIDE'],
  DOOR: ['NORMAL', 'TERRACE']
};

export const DEFAULT_SIZE: Record<OpeningKind, OpeningSize> = { WINDOW: 'NORMAL', DOOR: 'NORMAL' };

/** Hoechster senkrechter Abstand eines Punkts zur Wandlinie, bei dem noch eingerastet wird. */
export const SNAP_DISTANCE_UNITS = 12;

/** Oeffnungswinkel eines offenen Fluegels. */
const OPEN_ANGLE_RAD = Math.PI / 3;

export function dimensions(kind: OpeningKind, size: OpeningSize): OpeningDimensions {
  const found = OPENING_DIMENSIONS[kind][size];
  if (!found) {
    throw new Error(`Unbekannte Groesse ${size} fuer ${kind}`);
  }
  return found;
}

export function widthUnits(kind: OpeningKind, size: OpeningSize): number {
  return Math.round(dimensions(kind, size).widthM * UNITS_PER_METER);
}

export function normalizeSegment(s: Segment): Segment {
  const swap = s.x1 > s.x2 || (s.x1 === s.x2 && s.y1 > s.y2);
  return swap ? { x1: s.x2, y1: s.y2, x2: s.x1, y2: s.y1 } : { x1: s.x1, y1: s.y1, x2: s.x2, y2: s.y2 };
}

// ---- Waende -------------------------------------------------------------------------------

type Orientation = 'H' | 'V';

/** Eine Wandlinie: waagerecht (y = coord) oder senkrecht (x = coord), mit verschmolzenen Intervallen. */
interface WallLine { orientation: Orientation; coord: number; intervals: [number, number][] }

/** Lage einer Strecke als Linie plus Intervall entlang der Linie. */
interface LinePosition { orientation: Orientation; coord: number; from: number; to: number }

function linePosition(s: Segment): LinePosition {
  const n = normalizeSegment(s);
  return n.y1 === n.y2
    ? { orientation: 'H', coord: n.y1, from: n.x1, to: n.x2 }
    : { orientation: 'V', coord: n.x1, from: n.y1, to: n.y2 };
}

function mergeIntervals(intervals: [number, number][]): [number, number][] {
  const sorted = [...intervals].sort((a, b) => a[0] - b[0]);
  const merged: [number, number][] = [];
  for (const [from, to] of sorted) {
    const last = merged[merged.length - 1];
    if (last && from <= last[1]) {
      last[1] = Math.max(last[1], to);
    } else {
      merged.push([from, to]);
    }
  }
  return merged;
}

/** Vereinigung aller Raumrechteck-Kanten der Etage; Aussenbereiche sind keine Waende. */
function wallLines(floor: FloorId): WallLine[] {
  const groups = new Map<string, WallLine>();
  const add = (orientation: Orientation, coord: number, a: number, b: number) => {
    const key = `${orientation}:${coord}`;
    const line = groups.get(key) ?? { orientation, coord, intervals: [] };
    line.intervals.push([Math.min(a, b), Math.max(a, b)]);
    groups.set(key, line);
  };
  for (const room of HOUSE_ROOMS.filter(entry => entry.floor === floor)) {
    for (const r of room.rects) {
      add('H', r.y1, r.x1, r.x2);
      add('H', r.y2, r.x1, r.x2);
      add('V', r.x1, r.y1, r.y2);
      add('V', r.x2, r.y1, r.y2);
    }
  }
  return [...groups.values()].map(line => ({ ...line, intervals: mergeIntervals(line.intervals) }));
}

function findLine(floor: FloorId, orientation: Orientation, coord: number): WallLine | undefined {
  return wallLines(floor).find(line => line.orientation === orientation && line.coord === coord);
}

/** Liegt die ganze Strecke in einem verschmolzenen Wandstueck der Etage? */
export function isOnWall(floor: FloorId, s: Segment): boolean {
  if (s.x1 !== s.x2 && s.y1 !== s.y2) {
    return false;
  }
  const pos = linePosition(s);
  if (pos.from >= pos.to) {
    return false;
  }
  const line = findLine(floor, pos.orientation, pos.coord);
  return !!line && line.intervals.some(([from, to]) => pos.from >= from && pos.to <= to);
}

/**
 * Naechste Wandlinie (senkrechter Abstand <= SNAP_DISTANCE_UNITS), Mitte = Projektion des Punkts,
 * in das verschmolzene Wandstueck um die Projektion geschoben. Null, wenn keine Linie nah genug ist,
 * die Projektion in keinem Stueck liegt oder das Stueck kuerzer als die Breite ist.
 */
export function snapToWall(floor: FloorId, point: PlanPoint, kind: OpeningKind, size: OpeningSize): Segment | null {
  const width = widthUnits(kind, size);
  let best: { line: WallLine; interval: [number, number]; distance: number } | null = null;
  for (const line of wallLines(floor)) {
    const distance = Math.abs((line.orientation === 'H' ? point.y : point.x) - line.coord);
    const projected = line.orientation === 'H' ? point.x : point.y;
    const interval = line.intervals.find(([a, b]) => projected >= a && projected <= b && b - a >= width);
    if (interval && distance <= SNAP_DISTANCE_UNITS && (!best || distance < best.distance)) {
      best = { line, interval, distance };
    }
  }
  if (!best) {
    return null;
  }
  const { line, interval } = best;
  const along = line.orientation === 'H' ? point.x : point.y;
  const from = Math.min(Math.max(along - width / 2, interval[0]), interval[1] - width);
  const to = from + width;
  return line.orientation === 'H'
    ? { x1: from, y1: line.coord, x2: to, y2: line.coord }
    : { x1: line.coord, y1: from, x2: line.coord, y2: to };
}

// ---- Wandseiten ---------------------------------------------------------------------------

/** Raum-Ids beiderseits der Strecke: positive = +y (waagerecht) bzw. +x (senkrecht). */
export interface WallSides { positive: string | null; negative: string | null }

function roomAt(floor: FloorId, point: PlanPoint): string | null {
  return HOUSE_ROOMS.find(room => room.floor === floor && isPointInArea(room.id, point))?.id ?? null;
}

export function wallSides(floor: FloorId, s: Segment): WallSides {
  const n = normalizeSegment(s);
  const mx = (n.x1 + n.x2) / 2;
  const my = (n.y1 + n.y2) / 2;
  const horizontal = n.y1 === n.y2;
  const offset = (sign: number): PlanPoint => horizontal ? { x: mx, y: my + sign } : { x: mx + sign, y: my };
  return { positive: roomAt(floor, offset(1)), negative: roomAt(floor, offset(-1)) };
}

/** Innenwand: auf beiden Seiten ein Raum. Aussenwand: nur auf einer. */
export function isInnerWall(floor: FloorId, s: Segment): boolean {
  const sides = wallSides(floor, s);
  return sides.positive !== null && sides.negative !== null;
}

/** Aussenwand: zur Seite mit dem Raum. Innenwand: zur Seite des in HOUSE_ROOMS zuerst stehenden Raums. */
export function defaultSwing(floor: FloorId, s: Segment): OpeningSwing {
  const { positive, negative } = wallSides(floor, s);
  if (positive && negative) {
    const index = (id: string) => HOUSE_ROOMS.findIndex(room => room.id === id);
    return index(negative) < index(positive) ? 'NEGATIVE' : 'POSITIVE';
  }
  return negative ? 'NEGATIVE' : 'POSITIVE';
}

/** Gleiche Linie mit echt ueberlappendem Intervall; blosses Beruehren am Endpunkt zaehlt nicht. */
export function overlapsAny(s: Segment, others: readonly Segment[]): boolean {
  const a = linePosition(s);
  return others.some(other => {
    const b = linePosition(other);
    return a.orientation === b.orientation && a.coord === b.coord && a.from < b.to && b.from < a.to;
  });
}

// ---- Anschlag links/rechts ---------------------------------------------------------------

/** Seite des Anschlags, von der `swing`-Seite aus auf die Wand geblickt. */
export type HingeSide = 'LEFT' | 'RIGHT';

/**
 * Anschlag-Endpunkt fuer „links“/„rechts“. Plan-Koordinaten: x nach rechts, y nach unten; man
 * steht auf der `swing`-Seite und blickt auf die Wand. Waagerecht: bei POSITIVE (Blick nach -y)
 * ist links das kleinere x (START). Senkrecht: bei POSITIVE (Blick nach -x) ist links das
 * groessere y (END). NEGATIVE kehrt beides um. START/END beziehen sich auf die normalisierte Strecke.
 */
export function hingeForSide(s: Segment, swing: OpeningSwing, side: HingeSide): OpeningHinge {
  const n = normalizeSegment(s);
  const horizontal = n.y1 === n.y2;
  const leftIsStart = horizontal === (swing === 'POSITIVE');
  return (side === 'LEFT') === leftIsStart ? 'START' : 'END';
}

/** Umkehrung von `hingeForSide`. */
export function sideForHinge(s: Segment, swing: OpeningSwing, hinge: OpeningHinge): HingeSide {
  return hingeForSide(s, swing, 'LEFT') === hinge ? 'LEFT' : 'RIGHT';
}

// ---- Sensoren -----------------------------------------------------------------------------

/** Verknuepfbar: Fenster-/Tuerkontakte (ohne Gaszaehler-Reedkontakt) und die Haustuer. */
export function linkableDevices(): HouseDevice[] {
  return HOUSE_DEVICES.filter(device =>
    (device.kind === 'contact' && isWindowContact(device)) || device.kind === 'door');
}

/**
 * Kontakte, die an einem gezeichneten Element (an einer Wand) haengen: das Element steht fuer sie,
 * als eigenes Geraet (Punkt, Bubble, Liste „Noch ohne Platz“) entfallen sie. Die Haustuer bleibt
 * trotz Verknuepfung ein Geraet — ihre Bubble traegt auch das Schloss. Raum-Hinweise (Fenster offen)
 * lesen die Kontakte weiterhin.
 */
export function absorbedDeviceIds(openings: readonly HouseOpening[]): ReadonlySet<string> {
  const ids = new Set<string>();
  for (const o of openings) {
    const device = o.deviceId ? HOUSE_DEVICES.find(entry => entry.id === o.deviceId) : undefined;
    if (device?.kind === 'contact' && isOnWall(o.floor, o)) {
      ids.add(device.id);
    }
  }
  return ids;
}

/** Offen nur bei explizitem `on` des verknuepften Kontakts; ohne/unbekannter Sensor: geschlossen. */
export function openingIsOpen(o: HouseOpening, entities: EntityMap): boolean {
  const device = o.deviceId ? HOUSE_DEVICES.find(entry => entry.id === o.deviceId) : undefined;
  switch (device?.kind) {
    case 'contact':
      return isOn(entities, device.entityId);
    case 'door':
      return isOn(entities, device.contactEntityId);
    default:
      return false;
  }
}

// ---- 3D-Geometrie -------------------------------------------------------------------------

/** Ecken: unten am Anschlag, unten frei, oben frei, oben am Anschlag. */
export interface Panel { corners: [Vec3, Vec3, Vec3, Vec3] }

/** `frame`: unten bei (x1, y1), unten bei (x2, y2), oben bei (x2, y2), oben bei (x1, y1). */
export interface OpeningShape {
  id: number;
  floor: FloorId;
  kind: OpeningKind;
  open: boolean;
  frame: [Vec3, Vec3, Vec3, Vec3];
  panels: Panel[];
}

interface Point2 { x: number; z: number }

function panel(hinge: Point2, direction: Point2, normal: Point2, widthM: number, open: boolean,
               bottom: number, top: number): Panel {
  const cos = open ? Math.cos(OPEN_ANGLE_RAD) : 1;
  const sin = open ? Math.sin(OPEN_ANGLE_RAD) : 0;
  const free: Point2 = {
    x: hinge.x + widthM * (cos * direction.x + sin * normal.x),
    z: hinge.z + widthM * (cos * direction.z + sin * normal.z)
  };
  return {
    corners: [
      { x: hinge.x, y: bottom, z: hinge.z },
      { x: free.x, y: bottom, z: free.z },
      { x: free.x, y: top, z: free.z },
      { x: hinge.x, y: top, z: hinge.z }
    ]
  };
}

export function openingShape(o: HouseOpening, open: boolean): OpeningShape {
  const n = normalizeSegment(o);
  const dims = dimensions(o.kind, o.size);
  const base = floorBase(o.floor);
  const bottom = base + dims.sillM;
  const top = base + dims.topM;
  const start: Point2 = { x: toWorldX(n.x1), z: toWorldZ(n.y1) };
  const end: Point2 = { x: toWorldX(n.x2), z: toWorldZ(n.y2) };
  const lengthM = Math.hypot(end.x - start.x, end.z - start.z);
  const along: Point2 = lengthM > 0 ? { x: (end.x - start.x) / lengthM, z: (end.z - start.z) / lengthM } : { x: 1, z: 0 };
  const back: Point2 = { x: -along.x, z: -along.z };
  const sign = o.swing === 'POSITIVE' ? 1 : -1;
  const horizontal = n.y1 === n.y2;
  const normal: Point2 = horizontal ? { x: 0, z: sign } : { x: sign, z: 0 };

  const panels = o.size === 'TERRACE'
    ? [
        panel(start, along, normal, lengthM / 2, open, bottom, top),
        panel(end, back, normal, lengthM / 2, open, bottom, top)
      ]
    : [o.hinge === 'START'
        ? panel(start, along, normal, lengthM, open, bottom, top)
        : panel(end, back, normal, lengthM, open, bottom, top)];

  return {
    id: o.id,
    floor: o.floor,
    kind: o.kind,
    open,
    frame: [
      { x: start.x, y: bottom, z: start.z },
      { x: end.x, y: bottom, z: end.z },
      { x: end.x, y: top, z: end.z },
      { x: start.x, y: top, z: start.z }
    ],
    panels
  };
}
