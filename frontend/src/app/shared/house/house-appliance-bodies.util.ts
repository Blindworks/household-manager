import { EntityMap, isOn, livePowerWatts } from './house-bubbles.util';
import { ApplianceBodyKind, HOUSE_DEVICES, HouseDevice } from './house-devices';
import { Vec3, areaFloorBase, toPlanX, toPlanY, toWorldX, toWorldZ } from './house-geometry.util';
import { FloorId, HOUSE_ROOMS, OUTDOOR_AREAS, PlanRect, areaFloor } from './house-plan';
import { DevicePositions, PlanPoint, deviceAnchor, isPointInArea } from './house-placement.util';

/**
 * Grossgeraete als Koerper in der 3D-Hausansicht: Form, Lage, Drehung und Zustand.
 * Einzige Definition; der Renderer zeichnet nur (Muster `openingShape`).
 *
 * Ort ist der Platz aus `house-placement.util.ts` (wie Bubble und Effekt), nur der Fernseher
 * rueckt mit dem Ruecken an die Wand. Gedreht wird automatisch: Ruecken zur naechsten Wand
 * des eigenen Bereichs, Vorderseite in den Raum.
 */
export type ApplianceBodyState = 'idle' | 'active' | 'finished' | 'alarm';

/** Darueber gilt ein Steckdosengeraet als „arbeitet“ (darunter Standby); strikt groesser. */
export const ACTIVE_POWER_THRESHOLD_W = 10;

export interface ApplianceBody {
  deviceId: string;
  floor: FloorId;
  kind: ApplianceBodyKind;
  state: ApplianceBodyState;
  /** Mittelpunkt der Grundflaeche in Weltkoordinaten, y = Unterkante. */
  base: Vec3;
  /** Drehung um die y-Achse; die lokale +z-Richtung ist die Vorderseite. */
  rotationY: number;
  widthM: number;
  depthM: number;
  heightM: number;
}

interface BodyDimensions { widthM: number; depthM: number; heightM: number; bottomM: number }

/** Breite parallel zur Wand, Tiefe in den Raum, Unterkante ueber dem Etagenboden. */
export const BODY_DIMENSIONS: Record<ApplianceBodyKind, BodyDimensions> = {
  washer: { widthM: 0.6, depthM: 0.6, heightM: 0.85, bottomM: 0 },
  dishwasher: { widthM: 0.6, depthM: 0.6, heightM: 0.85, bottomM: 0 },
  fridge: { widthM: 0.6, depthM: 0.65, heightM: 1.8, bottomM: 0 },
  // Mitte auf 1,20 m
  tv: { widthM: 1.2, depthM: 0.06, heightM: 0.7, bottomM: 0.85 },
  pc: { widthM: 0.2, depthM: 0.45, heightM: 0.45, bottomM: 0 },
  printer: { widthM: 0.45, depthM: 0.35, heightM: 0.25, bottomM: 0.9 },
  // Hoehe = Pult am Kopfende; die Lauffläche zeichnet der Renderer flach
  treadmill: { widthM: 0.8, depthM: 1.8, heightM: 1.3, bottomM: 0 },
  coffee: { widthM: 0.3, depthM: 0.4, heightM: 0.4, bottomM: 0.9 }
};

type BodyDevice = Extract<HouseDevice, { kind: 'switch' | 'appliance' }> & { body: ApplianceBodyKind };

function hasBody(device: HouseDevice): device is BodyDevice {
  return (device.kind === 'switch' || device.kind === 'appliance') && !!device.body;
}

/** Ids aller Geraete mit Koerper (sie zeigen keinen „an“-Punkt und keinen Laufbogen mehr). */
export const BODY_DEVICE_IDS: ReadonlySet<string> = new Set(HOUSE_DEVICES.filter(hasBody).map(device => device.id));

export function applianceBodyState(device: HouseDevice, entities: EntityMap, nowMs: number): ApplianceBodyState {
  if (device.kind === 'appliance') {
    if (isOn(entities, device.runningEntityId)) {
      return 'active';
    }
    return isOn(entities, device.finishedEntityId) ? 'finished' : 'idle';
  }
  if (device.kind !== 'switch') {
    return 'idle';
  }
  if (device.body === 'fridge') {
    // Nur ein ausdrueckliches „aus“ ist ein Alarm; ein toter Sensor beweist nichts.
    return entities.get(device.entityId)?.state === 'off' ? 'alarm' : 'idle';
  }
  if (!isOn(entities, device.entityId) || !device.powerEntityId) {
    return 'idle';
  }
  const watts = livePowerWatts(entities, device.powerEntityId, nowMs);
  return watts !== null && watts > ACTIVE_POWER_THRESHOLD_W ? 'active' : 'idle';
}

/** Koerper aller markierten Geraete der Etage. */
export function buildApplianceBodies(floor: FloorId, entities: EntityMap, positions: DevicePositions,
                                     nowMs: number): ApplianceBody[] {
  const bodies: ApplianceBody[] = [];
  for (const device of HOUSE_DEVICES) {
    if (!hasBody(device) || areaFloor(device.area) !== floor) {
      continue;
    }
    const anchor = deviceAnchor(device.id, positions);
    if (!anchor) {
      continue;
    }
    bodies.push(bodyFor(device, floor, anchor, applianceBodyState(device, entities, nowMs)));
  }
  return bodies;
}

interface WallEdge { foot: PlanPoint; distance: number; front: { x: number; z: number } }

function bodyFor(device: BodyDevice, floor: FloorId, anchor: Vec3, state: ApplianceBodyState): ApplianceBody {
  const dims = BODY_DIMENSIONS[device.body];
  const point: PlanPoint = { x: toPlanX(anchor.x), y: toPlanY(anchor.z) };
  const wall = nearestWall(device.area, point);
  const front = wall?.front ?? { x: 0, z: 1 };
  let x = anchor.x;
  let z = anchor.z;
  if (device.body === 'tv' && wall) {
    // Nur senkrecht zur Wand verschieben: Rueckseite liegt auf der Wand.
    x = front.x === 0 ? x : toWorldX(wall.foot.x) + front.x * dims.depthM / 2;
    z = front.z === 0 ? z : toWorldZ(wall.foot.y) + front.z * dims.depthM / 2;
  }
  return {
    deviceId: device.id, floor, kind: device.body, state,
    base: { x, y: areaFloorBase(device.area) + dims.bottomM, z },
    rotationY: Math.atan2(front.x, front.z),
    widthM: dims.widthM, depthM: dims.depthM, heightM: dims.heightM
  };
}

function areaRects(areaId: string): readonly PlanRect[] {
  return HOUSE_ROOMS.find(room => room.id === areaId)?.rects
    ?? OUTDOOR_AREAS.filter(area => area.id === areaId).map(area => area.rect);
}

/** Abstand nach aussen, an dem geprueft wird, ob hinter einer Kante noch derselbe Bereich liegt. */
const OUTSIDE_PROBE = 0.5;

/**
 * Naechste Wand des eigenen Bereichs: Kante eines seiner Rechtecke, deren Lotfusspunkt auf der
 * Kante liegt und hinter der nicht derselbe Bereich weitergeht. Gleichstand: oben, rechts, unten, links.
 */
export function nearestWall(areaId: string, point: PlanPoint): WallEdge | null {
  let best: WallEdge | null = null;
  for (const rect of areaRects(areaId)) {
    const minX = Math.min(rect.x1, rect.x2);
    const maxX = Math.max(rect.x1, rect.x2);
    const minY = Math.min(rect.y1, rect.y2);
    const maxY = Math.max(rect.y1, rect.y2);
    const insideX = point.x >= minX && point.x <= maxX;
    const insideY = point.y >= minY && point.y <= maxY;
    // Plan-y waechst mit Welt-z; die Vorderseite zeigt von der Wand weg.
    const candidates: (WallEdge & { valid: boolean })[] = [
      { valid: insideX, foot: { x: point.x, y: minY }, distance: Math.abs(point.y - minY), front: { x: 0, z: 1 } },
      { valid: insideY, foot: { x: maxX, y: point.y }, distance: Math.abs(maxX - point.x), front: { x: -1, z: 0 } },
      { valid: insideX, foot: { x: point.x, y: maxY }, distance: Math.abs(maxY - point.y), front: { x: 0, z: -1 } },
      { valid: insideY, foot: { x: minX, y: point.y }, distance: Math.abs(point.x - minX), front: { x: 1, z: 0 } }
    ];
    for (const { valid, ...edge } of candidates) {
      const behind: PlanPoint = {
        x: edge.foot.x - edge.front.x * OUTSIDE_PROBE,
        y: edge.foot.y - edge.front.z * OUTSIDE_PROBE
      };
      if (valid && !isPointInArea(areaId, behind) && (!best || edge.distance < best.distance)) {
        best = edge;
      }
    }
  }
  return best;
}

/** Gleichheit fuer `computed`: nur bei echter Aenderung neu zeichnen. */
export function sameBodies(a: readonly ApplianceBody[], b: readonly ApplianceBody[]): boolean {
  return a.length === b.length && a.every((body, i) => {
    const other = b[i];
    return body.deviceId === other.deviceId && body.state === other.state && body.rotationY === other.rotationY
      && body.base.x === other.base.x && body.base.y === other.base.y && body.base.z === other.base.z;
  });
}
