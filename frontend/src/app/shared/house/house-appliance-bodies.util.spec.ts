import { EntityState } from '../../models/entity-state.model';
import {
  ACTIVE_POWER_THRESHOLD_W, BODY_DEVICE_IDS, applianceBodyState, buildApplianceBodies, nearestWall
} from './house-appliance-bodies.util';
import { EntityMap } from './house-bubbles.util';
import { HOUSE_DEVICES, HouseDevice } from './house-devices';
import { areaFloorBase, toWorldX, toWorldZ } from './house-geometry.util';
import { DevicePositions, deviceAnchor } from './house-placement.util';

const T0 = '2026-11-12T10:00:00';
const NOW = new Date(T0).getTime();

function byBody(area: string, body: string): HouseDevice {
  return HOUSE_DEVICES.find(d => d.area === area && (d.kind === 'switch' || d.kind === 'appliance') && d.body === body)!;
}

function entities(states: Record<string, string>, lastUpdated = T0): EntityMap {
  return new Map(Object.entries(states).map(([entityId, state]) =>
    [entityId, { entityId, state, lastUpdated } as EntityState]));
}

const TV = byBody('wohnzimmer', 'tv') as Extract<HouseDevice, { kind: 'switch' }>;
const FRIDGE = byBody('kueche', 'fridge') as Extract<HouseDevice, { kind: 'switch' }>;
const WASHER = byBody('waschkeller', 'washer') as Extract<HouseDevice, { kind: 'appliance' }>;

describe('BODY_DEVICE_IDS', () => {
  it('umfasst genau die neun Grossgeraete', () => {
    const labels = HOUSE_DEVICES.filter(d => BODY_DEVICE_IDS.has(d.id)).map(d => `${d.area}:${d.label}`).sort();
    expect(labels).toEqual([
      'buero:Drucker', 'buero:PC', 'kueche:Kaffeemaschine', 'kueche:Kühlschrank', 'kueche:Spülmaschine',
      'laufband:Laufband', 'schlafzimmer:Fernseher', 'waschkeller:Waschmaschine', 'wohnzimmer:Fernseher'
    ]);
  });
});

describe('applianceBodyState', () => {
  const power = TV.powerEntityId!;

  it('Steckdosengeraet: an und ueber 10 W ist aktiv', () => {
    expect(applianceBodyState(TV, entities({ [TV.entityId]: 'on', [power]: '85' }), NOW)).toBe('active');
  });

  it('genau 10 W ist Standby', () => {
    expect(applianceBodyState(TV, entities({ [TV.entityId]: 'on', [power]: String(ACTIVE_POWER_THRESHOLD_W) }), NOW))
      .toBe('idle');
  });

  it('Messung 5 min alt gilt nicht mehr, knapp darunter noch', () => {
    const states = { [TV.entityId]: 'on', [power]: '85' };
    expect(applianceBodyState(TV, entities(states), NOW + 5 * 60_000)).toBe('idle');
    expect(applianceBodyState(TV, entities(states), NOW + 5 * 60_000 - 1)).toBe('active');
  });

  it('Watt unlesbar oder keine Zahl ist idle', () => {
    expect(applianceBodyState(TV, entities({ [TV.entityId]: 'on', [power]: 'unavailable' }), NOW)).toBe('idle');
    expect(applianceBodyState(TV, entities({ [TV.entityId]: 'on', [power]: 'abc' }), NOW)).toBe('idle');
    expect(applianceBodyState(TV, entities({ [TV.entityId]: 'on' }), NOW)).toBe('idle');
  });

  it('Schalter aus bei hohem Verbrauch ist idle', () => {
    expect(applianceBodyState(TV, entities({ [TV.entityId]: 'off', [power]: '85' }), NOW)).toBe('idle');
  });

  it('Kuehlschrank: nur ausdruecklich aus ist Alarm, sonst nie aktiv', () => {
    expect(applianceBodyState(FRIDGE, entities({ [FRIDGE.entityId]: 'off' }), NOW)).toBe('alarm');
    expect(applianceBodyState(FRIDGE, entities({ [FRIDGE.entityId]: 'unavailable' }), NOW)).toBe('idle');
    expect(applianceBodyState(FRIDGE, entities({}), NOW)).toBe('idle');
    expect(applianceBodyState(FRIDGE, entities({ [FRIDGE.entityId]: 'on', [FRIDGE.powerEntityId!]: '120' }), NOW))
      .toBe('idle');
  });

  it('Waschmaschine: laeuft vor fertig, Watt egal', () => {
    expect(applianceBodyState(WASHER, entities({ [WASHER.runningEntityId]: 'on', [WASHER.finishedEntityId]: 'on' }), NOW))
      .toBe('active');
    expect(applianceBodyState(WASHER, entities({ [WASHER.finishedEntityId]: 'on', [WASHER.powerEntityId]: '2000' }), NOW))
      .toBe('finished');
    expect(applianceBodyState(WASHER, entities({ [WASHER.powerEntityId]: '2000' }), NOW)).toBe('idle');
  });
});

describe('nearestWall', () => {
  // Kueche: x 20..210, y 240..350
  it('waehlt die naechste Kante, Vorderseite in den Raum', () => {
    expect(nearestWall('kueche', { x: 100, y: 245 })!.front).toEqual({ x: 0, z: 1 });
    expect(nearestWall('kueche', { x: 205, y: 300 })!.front).toEqual({ x: -1, z: 0 });
    expect(nearestWall('kueche', { x: 100, y: 345 })!.front).toEqual({ x: 0, z: -1 });
    expect(nearestWall('kueche', { x: 25, y: 300 })!.front).toEqual({ x: 1, z: 0 });
  });

  it('Gleichstand: oben vor links', () => {
    expect(nearestWall('kueche', { x: 25, y: 245 })!.front).toEqual({ x: 0, z: 1 });
  });

  it('unbekannter Bereich: keine Wand', () => {
    expect(nearestWall('gibt-es-nicht', { x: 100, y: 100 })).toBeNull();
  });
});

describe('buildApplianceBodies', () => {
  const at = (id: string, x: number, y: number): DevicePositions => new Map([[id, { x, y }]]);

  it('liefert nur Koerper der Etage', () => {
    const ground = buildApplianceBodies('ground', entities({}), new Map(), NOW).map(b => b.kind).sort();
    expect(ground).toEqual(['coffee', 'dishwasher', 'fridge', 'treadmill', 'tv']);
    expect(buildApplianceBodies('basement', entities({}), new Map(), NOW).map(b => b.kind)).toEqual(['washer']);
  });

  it('dreht den Ruecken zur naechsten Wand', () => {
    const rot = (x: number, y: number) =>
      buildApplianceBodies('ground', entities({}), at(FRIDGE.id, x, y), NOW).find(b => b.deviceId === FRIDGE.id)!.rotationY;
    expect(rot(100, 245)).toBeCloseTo(0);
    expect(rot(205, 300)).toBeCloseTo(-Math.PI / 2);
    expect(rot(100, 345)).toBeCloseTo(Math.PI);
    expect(rot(25, 300)).toBeCloseTo(Math.PI / 2);
  });

  it('steht genau auf seinem Platz, Unterkante auf dem Boden', () => {
    const body = buildApplianceBodies('ground', entities({}), at(FRIDGE.id, 100, 300), NOW)
      .find(b => b.deviceId === FRIDGE.id)!;
    expect(body.base.x).toBeCloseTo(toWorldX(100));
    expect(body.base.z).toBeCloseTo(toWorldZ(300));
    expect(body.base.y).toBeCloseTo(areaFloorBase('kueche'));
  });

  it('Fernseher rueckt an die Wand, Mitte auf 1,20 m', () => {
    // Wohnzimmer: x 210..420, y 90..270; naechste Wand oben (y 90)
    const body = buildApplianceBodies('ground', entities({}), at(TV.id, 300, 110), NOW)
      .find(b => b.deviceId === TV.id)!;
    expect(body.base.x).toBeCloseTo(toWorldX(300));
    expect(body.base.z).toBeCloseTo(toWorldZ(90) + body.depthM / 2);
    expect(body.base.y + body.heightM / 2).toBeCloseTo(areaFloorBase('wohnzimmer') + 1.2);
  });

  it('ungueltiger Platz faellt auf den Rasterplatz zurueck', () => {
    const positions = at(FRIDGE.id, 400, 100); // liegt im Wohnzimmer, nicht in der Kueche
    const body = buildApplianceBodies('ground', entities({}), positions, NOW).find(b => b.deviceId === FRIDGE.id)!;
    const grid = deviceAnchor(FRIDGE.id, new Map())!;
    expect(body.base.x).toBeCloseTo(grid.x);
    expect(body.base.z).toBeCloseTo(grid.z);
  });

  it('traegt den Zustand', () => {
    const body = buildApplianceBodies('basement', entities({ [WASHER.runningEntityId]: 'on' }), new Map(), NOW)[0];
    expect(body.state).toBe('active');
  });
});
