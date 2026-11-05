import { FLOORS, FloorId, HOUSE_BOUNDS, HOUSE_ROOMS, HouseRoom, PlanRect, STOREY_HEIGHT_M, UNITS_PER_METER, roomById } from './house-plan';

/**
 * Weltkoordinaten der 3D-Hausansicht in Metern: y zeigt nach oben, die
 * Hausmitte liegt bei x = z = 0, die Straßenseite (Skizze unten) bei +z.
 * Einzige Umrechnung von Skizzen-Einheiten in die Szene - Renderer und
 * Bubble-Anker fragen beide hier.
 */
export interface Vec3 { x: number; y: number; z: number }

export interface RoomBox { roomId: string; floor: FloorId; center: Vec3; size: Vec3 }

export interface CameraView { position: Vec3; target: Vec3 }

export type FloorOpacity = 'solid' | 'glass' | 'hidden';

/** Höhe der Raum-Anker (Bubbles, Kameraziel) über dem Etagenboden. */
const ANCHOR_HEIGHT_M = 1.2;

const CENTER_X = (HOUSE_BOUNDS.x1 + HOUSE_BOUNDS.x2) / 2;
const CENTER_Y = (HOUSE_BOUNDS.y1 + HOUSE_BOUNDS.y2) / 2;

function floorLevel(floor: FloorId): number {
  return FLOORS.find(entry => entry.id === floor)?.level ?? 0;
}

/** Höhe des Etagenbodens in Metern. */
export function floorBase(floor: FloorId): number {
  return floorLevel(floor) * STOREY_HEIGHT_M;
}

export function toWorldX(planX: number): number {
  return (planX - CENTER_X) / UNITS_PER_METER;
}

export function toWorldZ(planY: number): number {
  return (planY - CENTER_Y) / UNITS_PER_METER;
}

/** Umkehrung von toWorldX (z. B. für einen Raycast-Treffer). */
export function toPlanX(worldX: number): number {
  return worldX * UNITS_PER_METER + CENTER_X;
}

/** Umkehrung von toWorldZ. */
export function toPlanY(worldZ: number): number {
  return worldZ * UNITS_PER_METER + CENTER_Y;
}

/** Liegt ein Skizzenpunkt in einem der Ausschnitte des Raums (z. B. Gäste-WC im Flur)? */
export function insideCutout(roomId: string, planX: number, planY: number): boolean {
  return (roomById(roomId)?.cutouts ?? []).some(rect =>
    planX >= Math.min(rect.x1, rect.x2) && planX <= Math.max(rect.x1, rect.x2)
    && planY >= Math.min(rect.y1, rect.y2) && planY <= Math.max(rect.y1, rect.y2));
}

function rectArea(rect: PlanRect): number {
  return Math.abs(rect.x2 - rect.x1) * Math.abs(rect.y2 - rect.y1);
}

function rectCenter(rect: PlanRect): { x: number; z: number } {
  return { x: toWorldX((rect.x1 + rect.x2) / 2), z: toWorldZ((rect.y1 + rect.y2) / 2) };
}

/** Je Rechteck eines Raums eine Box mit Geschosshöhe, Boden auf der Etagenhöhe. */
export function roomBoxes(): RoomBox[] {
  return HOUSE_ROOMS.flatMap(room => room.rects.map(rect => {
    const center = rectCenter(rect);
    return {
      roomId: room.id,
      floor: room.floor,
      center: { x: center.x, y: floorBase(room.floor) + STOREY_HEIGHT_M / 2, z: center.z },
      size: {
        x: Math.abs(rect.x2 - rect.x1) / UNITS_PER_METER,
        y: STOREY_HEIGHT_M,
        z: Math.abs(rect.y2 - rect.y1) / UNITS_PER_METER
      }
    };
  }));
}

function requireRoom(roomId: string): HouseRoom {
  const room = roomById(roomId);
  if (!room || room.rects.length === 0) {
    throw new Error(`Unbekannter Raum: ${roomId}`);
  }
  return room;
}

function largestRect(room: HouseRoom): PlanRect {
  return room.rects.reduce((best, rect) => (rectArea(rect) > rectArea(best) ? rect : best));
}

/** Mitte des größten Rechtecks eines Raums, 1,2 m über dem Etagenboden. */
export function roomAnchor(roomId: string): Vec3 {
  const room = requireRoom(roomId);
  const center = rectCenter(largestRect(room));
  return { x: center.x, y: floorBase(room.floor) + ANCHOR_HEIGHT_M, z: center.z };
}

/** Etagen über der gewählten erscheinen nur als Glas-Umriss, die gewählte und alle darunter voll. */
export function floorOpacity(floor: FloorId, selected: FloorId): FloorOpacity {
  return floorLevel(floor) > floorLevel(selected) ? 'glass' : 'solid';
}

/** Blick von der Straßenseite schräg von oben auf die Mitte der gewählten Etage. */
export function cameraForOverview(selected: FloorId): CameraView {
  const base = floorBase(selected);
  return {
    position: { x: 2, y: base + 12, z: 12 },
    target: { x: 0, y: base, z: 0 }
  };
}

/** Kameraabstand für einen Raum: wächst mit der längeren Raumseite, begrenzt auf 4,5–10 m. */
function roomViewDistance(roomId: string): number {
  const rect = largestRect(requireRoom(roomId));
  const longest = Math.max(Math.abs(rect.x2 - rect.x1), Math.abs(rect.y2 - rect.y1)) / UNITS_PER_METER;
  return Math.min(10, Math.max(4.5, longest * 1.4));
}

/** Schräg von oben und von der Straßenseite in den Raum. */
export function cameraForRoom(roomId: string): CameraView {
  const target = roomAnchor(roomId);
  const distance = roomViewDistance(roomId);
  // Richtung (0, 0.8, 0.6) ist bereits normiert.
  return {
    position: { x: target.x, y: target.y + distance * 0.8, z: target.z + distance * 0.6 },
    target
  };
}
