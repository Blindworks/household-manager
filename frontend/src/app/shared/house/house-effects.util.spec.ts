import { EntityState } from '../../models/entity-state.model';
import { HOUSE_DEVICES, HouseDevice, devicesInArea } from './house-devices';
import { DEVICE_ANCHOR_HEIGHT_M, deviceAnchors } from './house-geometry.util';
import { MIN_LIGHT_INTENSITY, buildHouseEffects, deviceAnchor } from './house-effects.util';

function entity(entityId: string, state: string): EntityState {
  return {
    entityId, domain: 'SWITCH', source: 'X', sourceRef: 'x', friendlyName: entityId, displayName: entityId,
    state, attributes: {}, lastChanged: '2026-11-06T09:00:00', lastUpdated: '2026-11-06T09:00:00'
  };
}

function mapOf(...entities: EntityState[]): Map<string, EntityState> {
  return new Map(entities.map(e => [e.entityId, e]));
}

function find<K extends HouseDevice['kind']>(kind: K, predicate: (d: Extract<HouseDevice, { kind: K }>) => boolean)
  : Extract<HouseDevice, { kind: K }> {
  const found = HOUSE_DEVICES.find(d => d.kind === kind && predicate(d as Extract<HouseDevice, { kind: K }>));
  if (!found) {
    throw new Error(`Geraet ${kind} nicht gefunden`);
  }
  return found as Extract<HouseDevice, { kind: K }>;
}

const NO_BRIGHTNESS = new Map<string, number>();

describe('buildHouseEffects', () => {
  const stehlampe = find('switch', d => d.label === 'Stehlampe');
  const fernseher = find('switch', d => d.area === 'wohnzimmer' && d.label === 'Fernseher');

  it('eingeschaltete Lampe leuchtet, Staerke aus der Helligkeit', () => {
    const effects = buildHouseEffects(['wohnzimmer'], mapOf(entity(stehlampe.entityId, 'on')),
      new Map([[stehlampe.entityId, 40]]));
    expect(effects).toEqual([jasmine.objectContaining({ deviceId: stehlampe.id, kind: 'light', intensity: 0.4 })]);
  });

  it('Lampe ohne bekannte Helligkeit leuchtet voll, sehr dunkle nie unter der Mindeststaerke', () => {
    const on = mapOf(entity(stehlampe.entityId, 'on'));
    expect(buildHouseEffects(['wohnzimmer'], on, NO_BRIGHTNESS)[0].intensity).toBe(1);
    expect(buildHouseEffects(['wohnzimmer'], on, new Map([[stehlampe.entityId, 1]]))[0].intensity)
      .toBe(MIN_LIGHT_INTENSITY);
  });

  it('eingeschaltetes Geraet ohne Lampe bekommt den kuehlen Punkt', () => {
    const effects = buildHouseEffects(['wohnzimmer'], mapOf(entity(fernseher.entityId, 'on')), NO_BRIGHTNESS);
    expect(effects).toEqual([jasmine.objectContaining({ deviceId: fernseher.id, kind: 'powered' })]);
  });

  it('aus, unavailable oder unbekannt erzeugt keinen Effekt', () => {
    for (const state of ['off', 'unavailable', 'unknown']) {
      const entities = mapOf(entity(stehlampe.entityId, state), entity(fernseher.entityId, state));
      expect(buildHouseEffects(['wohnzimmer'], entities, NO_BRIGHTNESS)).withContext(state).toEqual([]);
    }
  });

  it('heizendes Thermostat glueht, offener Kontakt leuchtet rot', () => {
    const heizung = find('thermostat', d => d.area === 'bad');
    const fenster = find('contact', d => d.area === 'bad');
    const effects = buildHouseEffects(['bad'], mapOf(entity(heizung.heating, 'on'), entity(fenster.entityId, 'on')),
      NO_BRIGHTNESS);
    expect(effects.map(e => [e.deviceId, e.kind])).toEqual([[heizung.id, 'heating'], [fenster.id, 'open']]);
  });

  it('der Gaszaehler-Reedkontakt ist kein Fenster', () => {
    const gas = find('contact', d => d.entityId.includes('sensor_gas'));
    expect(buildHouseEffects([gas.area], mapOf(entity(gas.entityId, 'on')), NO_BRIGHTNESS)).toEqual([]);
  });

  it('Bewegung und laufende Maschine; der Schalter der Maschine allein zeigt nichts', () => {
    const melder = find('motion', d => d.area === 'kueche');
    const spuelmaschine = find('appliance', d => d.area === 'kueche');
    const effects = buildHouseEffects(['kueche'], mapOf(
      entity(melder.entityId, 'on'),
      entity(spuelmaschine.switchEntityId, 'on'),
      entity(spuelmaschine.runningEntityId, 'on')
    ), NO_BRIGHTNESS);
    expect(effects.map(e => [e.deviceId, e.kind])).toEqual([[melder.id, 'motion'], [spuelmaschine.id, 'running']]);
    expect(buildHouseEffects(['kueche'], mapOf(entity(spuelmaschine.switchEntityId, 'on')), NO_BRIGHTNESS))
      .toEqual([]);
  });

  it('offene Haustuer leuchtet rot vor dem Haus', () => {
    const tuer = find('door', () => true);
    const effects = buildHouseEffects(['front'], mapOf(entity(tuer.contactEntityId, 'on')), NO_BRIGHTNESS);
    expect(effects).toEqual([jasmine.objectContaining({ deviceId: tuer.id, kind: 'open' })]);
  });

  it('nur die angefragten Bereiche', () => {
    expect(buildHouseEffects(['kueche'], mapOf(entity(stehlampe.entityId, 'on')), NO_BRIGHTNESS)).toEqual([]);
  });

  it('Effekt sitzt am Anker der Bubble, Boden eine Ankerhoehe darunter', () => {
    const [effect] = buildHouseEffects(['wohnzimmer'], mapOf(entity(stehlampe.entityId, 'on')), NO_BRIGHTNESS);
    expect(effect.anchor).toEqual(deviceAnchor(stehlampe.id)!);
    expect(effect.floorY).toBeCloseTo(effect.anchor.y - DEVICE_ANCHOR_HEIGHT_M, 6);
  });
});

describe('deviceAnchor', () => {
  it('entspricht dem Rasterplatz der Raumansicht (Reihenfolge der Geraete im Bereich)', () => {
    for (const area of ['wohnzimmer', 'kueche', 'front']) {
      const devices = devicesInArea(area);
      const anchors = deviceAnchors(area, devices.length);
      devices.forEach((device, i) => expect(deviceAnchor(device.id)).withContext(device.id).toEqual(anchors[i]));
    }
  });

  it('unbekanntes Geraet: null', () => {
    expect(deviceAnchor('gibt-es-nicht')).toBeNull();
  });
});

describe('Lampen in house-devices', () => {
  it('Lampen sind ausdruecklich markiert, andere Schalter nicht', () => {
    const lamps = HOUSE_DEVICES.filter(d => d.kind === 'switch' && d.lamp).map(d => `${d.area}/${d.label}`);
    expect(lamps).toContain('wohnzimmer/Stehlampe');
    expect(lamps).toContain('flur/Licht');
    expect(lamps).toContain('buero/Lichtstreifen');
    expect(lamps).not.toContain('wohnzimmer/Fernseher');
    expect(lamps).not.toContain('laufband/Laufband');
    expect(lamps).not.toContain('buero/PC');
  });
});
