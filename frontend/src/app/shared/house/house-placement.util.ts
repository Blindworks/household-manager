import { HOUSE_DEVICES, HouseDevice, devicesInArea } from './house-devices';
import { Vec3, areaFloorBase, deviceAnchors, insideCutout, toWorldX, toWorldZ } from './house-geometry.util';
import { HOUSE_ROOMS, OUTDOOR_AREAS, PlanRect } from './house-plan';

/**
 * Platz eines Geraets in der Szene: gespeicherter Grundrisspunkt, falls er fuer den Bereich
 * des Geraets gueltig ist, sonst Rasterplatz. Einzige Definition fuer Effekt, Kamera und Bubble.
 */
export interface PlanPoint { x: number; y: number }

/** Gespeicherte Plaetze je Geraete-Id. */
export type DevicePositions = ReadonlyMap<string, PlanPoint>;

/** Hoehen ueber dem Etagenboden. */
export const LAMP_HEIGHT_M = 2.3;
export const WALL_HEIGHT_M = 1.2;
export const LOW_HEIGHT_M = 0.5;
/** Die Bubble der Raumansicht sitzt immer auf dieser Hoehe, unabhaengig von der Art. */
export const BUBBLE_HEIGHT_M = 1.2;

const WALL_KINDS: ReadonlySet<HouseDevice['kind']> =
  new Set(['thermostat', 'contact', 'door', 'camera', 'motion', 'button']);

export function deviceHeightM(device: HouseDevice): number {
  if (device.kind === 'switch' && device.lamp) {
    return LAMP_HEIGHT_M;
  }
  return WALL_KINDS.has(device.kind) ? WALL_HEIGHT_M : LOW_HEIGHT_M;
}

function insideRect(rect: PlanRect, point: PlanPoint): boolean {
  return point.x >= Math.min(rect.x1, rect.x2) && point.x <= Math.max(rect.x1, rect.x2)
    && point.y >= Math.min(rect.y1, rect.y2) && point.y <= Math.max(rect.y1, rect.y2);
}

/** In einem Rechteck des Bereichs (Rand inklusive), nicht in einem Ausschnitt; unbekannter Bereich: false. */
export function isPointInArea(areaId: string, point: PlanPoint): boolean {
  const room = HOUSE_ROOMS.find(entry => entry.id === areaId);
  if (room) {
    return room.rects.some(rect => insideRect(rect, point)) && !insideCutout(areaId, point.x, point.y);
  }
  const outdoor = OUTDOOR_AREAS.find(entry => entry.id === areaId);
  return !!outdoor && insideRect(outdoor.rect, point);
}

/** Gespeicherter Punkt, falls gueltig fuer den Bereich des Geraets, sonst null. */
export function validPlacement(device: HouseDevice, positions: DevicePositions): PlanPoint | null {
  const point = positions.get(device.id);
  return point && isPointInArea(device.area, point) ? point : null;
}

function anchorAt(deviceId: string, positions: DevicePositions, heightM: (device: HouseDevice) => number): Vec3 | null {
  const device = HOUSE_DEVICES.find(entry => entry.id === deviceId);
  if (!device) {
    return null;
  }
  const y = areaFloorBase(device.area) + heightM(device);
  const placed = validPlacement(device, positions);
  if (placed) {
    return { x: toWorldX(placed.x), y, z: toWorldZ(placed.y) };
  }
  const unplaced = devicesInArea(device.area).filter(entry => !validPlacement(entry, positions));
  const grid = deviceAnchors(device.area, unplaced.length)[unplaced.indexOf(device)];
  return grid ? { x: grid.x, y, z: grid.z } : null;
}

/** Anker fuer Effekt und Kamera: y = Etagenboden + Hoehe nach Art. */
export function deviceAnchor(deviceId: string, positions: DevicePositions): Vec3 | null {
  return anchorAt(deviceId, positions, deviceHeightM);
}

/** Wie `deviceAnchor`, aber y = Etagenboden + `BUBBLE_HEIGHT_M`. */
export function deviceBubbleAnchor(deviceId: string, positions: DevicePositions): Vec3 | null {
  return anchorAt(deviceId, positions, () => BUBBLE_HEIGHT_M);
}

export function toPositionMap(list: readonly { deviceId: string; x: number; y: number }[]): DevicePositions {
  return new Map(list.map(entry => [entry.deviceId, { x: entry.x, y: entry.y }]));
}

export function samePositions(a: DevicePositions, b: DevicePositions): boolean {
  if (a.size !== b.size) {
    return false;
  }
  for (const [id, point] of a) {
    const other = b.get(id);
    if (!other || other.x !== point.x || other.y !== point.y) {
      return false;
    }
  }
  return true;
}
