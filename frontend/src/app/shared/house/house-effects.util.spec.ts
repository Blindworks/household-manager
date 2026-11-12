import { EntityState } from '../../models/entity-state.model';
import { HOUSE_DEVICES, HouseDevice } from './house-devices';
import { floorBase } from './house-geometry.util';
import { MIN_LIGHT_INTENSITY, buildHouseEffects } from './house-effects.util';
import { deviceAnchor } from './house-placement.util';

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
const NO_POSITIONS = new Map<string, { x: number; y: number }>();
const NO_LINKED: ReadonlySet<string> = new Set<string>();

describe('buildHouseEffects', () => {
  const stehlampe = find('switch', d => d.label === 'Stehlampe');
  const fernseher = find('switch', d => d.area === 'wohnzimmer' && d.label === 'Fernseher');

  it('eingeschaltete Lampe leuchtet, Staerke aus der Helligkeit', () => {
    const effects = buildHouseEffects(['wohnzimmer'], mapOf(entity(stehlampe.entityId, 'on')),
      new Map([[stehlampe.entityId, 40]]), NO_POSITIONS, NO_LINKED);
    expect(effects).toEqual([jasmine.objectContaining({ deviceId: stehlampe.id, kind: 'light', intensity: 0.4 })]);
  });

  it('Lampe ohne bekannte Helligkeit leuchtet voll, sehr dunkle nie unter der Mindeststaerke', () => {
    const on = mapOf(entity(stehlampe.entityId, 'on'));
    expect(buildHouseEffects(['wohnzimmer'], on, NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED)[0].intensity).toBe(1);
    expect(buildHouseEffects(['wohnzimmer'], on, new Map([[stehlampe.entityId, 1]]), NO_POSITIONS, NO_LINKED)[0].intensity)
      .toBe(MIN_LIGHT_INTENSITY);
  });

  it('eingeschaltetes Geraet ohne Lampe und ohne Koerper bekommt den kuehlen Punkt', () => {
    const kuechenzeile = find('switch', d => d.label === 'Küchenzeile');
    const effects = buildHouseEffects(['kueche'], mapOf(entity(kuechenzeile.entityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED);
    expect(effects).toEqual([jasmine.objectContaining({ deviceId: kuechenzeile.id, kind: 'powered' })]);
  });

  it('Grossgeraet mit Koerper bekommt keinen Punkt', () => {
    expect(buildHouseEffects(['wohnzimmer'], mapOf(entity(fernseher.entityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED))
      .toEqual([]);
  });

  it('aus, unavailable oder unbekannt erzeugt keinen Effekt', () => {
    for (const state of ['off', 'unavailable', 'unknown']) {
      const entities = mapOf(entity(stehlampe.entityId, state), entity(fernseher.entityId, state));
      expect(buildHouseEffects(['wohnzimmer'], entities, NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED)).withContext(state).toEqual([]);
    }
  });

  it('heizendes Thermostat glueht, offener Kontakt leuchtet rot', () => {
    const heizung = find('thermostat', d => d.area === 'bad');
    const fenster = find('contact', d => d.area === 'bad');
    const effects = buildHouseEffects(['bad'], mapOf(entity(heizung.heating, 'on'), entity(fenster.entityId, 'on')),
      NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED);
    expect(effects.map(e => [e.deviceId, e.kind])).toEqual([[heizung.id, 'heating'], [fenster.id, 'open']]);
  });

  it('der Gaszaehler-Reedkontakt ist kein Fenster', () => {
    const gas = find('contact', d => d.entityId.includes('sensor_gas'));
    expect(buildHouseEffects([gas.area], mapOf(entity(gas.entityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED)).toEqual([]);
  });

  it('Bewegung leuchtet; eine laufende Maschine mit Koerper bekommt keinen Bogen', () => {
    const melder = find('motion', d => d.area === 'kueche');
    const spuelmaschine = find('appliance', d => d.area === 'kueche');
    const effects = buildHouseEffects(['kueche'], mapOf(
      entity(melder.entityId, 'on'),
      entity(spuelmaschine.switchEntityId, 'on'),
      entity(spuelmaschine.runningEntityId, 'on')
    ), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED);
    expect(effects.map(e => [e.deviceId, e.kind])).toEqual([[melder.id, 'motion']]);
    expect(buildHouseEffects(['kueche'], mapOf(entity(spuelmaschine.switchEntityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED))
      .toEqual([]);
  });

  it('offene Haustuer leuchtet rot vor dem Haus', () => {
    const tuer = find('door', () => true);
    const effects = buildHouseEffects(['front'], mapOf(entity(tuer.contactEntityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED);
    expect(effects).toEqual([jasmine.objectContaining({ deviceId: tuer.id, kind: 'open' })]);
  });

  it('verknuepfterKontaktGluehtNicht', () => {
    const fenster = find('contact', d => d.area === 'bad');
    const entities = mapOf(entity(fenster.entityId, 'on'));
    expect(buildHouseEffects(['bad'], entities, NO_BRIGHTNESS, NO_POSITIONS, new Set([fenster.id]))).toEqual([]);
  });

  it('unverknuepfterKontaktGluehtWeiter', () => {
    const fenster = find('contact', d => d.area === 'bad');
    const entities = mapOf(entity(fenster.entityId, 'on'));
    expect(buildHouseEffects(['bad'], entities, NO_BRIGHTNESS, NO_POSITIONS, new Set(['anderes'])))
      .toEqual([jasmine.objectContaining({ deviceId: fenster.id, kind: 'open' })]);
  });

  it('verknuepfteHaustuerGluehtNicht', () => {
    const tuer = find('door', () => true);
    const entities = mapOf(entity(tuer.contactEntityId, 'on'));
    expect(buildHouseEffects(['front'], entities, NO_BRIGHTNESS, NO_POSITIONS, new Set([tuer.id]))).toEqual([]);
  });

  it('nur die angefragten Bereiche', () => {
    expect(buildHouseEffects(['kueche'], mapOf(entity(stehlampe.entityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED)).toEqual([]);
  });

  it('Effekt sitzt am Anker des Geraets', () => {
    const [effect] = buildHouseEffects(['wohnzimmer'], mapOf(entity(stehlampe.entityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED);
    expect(effect.anchor).toEqual(deviceAnchor(stehlampe.id, NO_POSITIONS)!);
  });

  it('effectFloorYIstEtagenboden: unabhaengig von der Ankerhoehe', () => {
    const licht = find('switch', d => d.area === 'schlafzimmer' && !!d.lamp);
    const [effect] = buildHouseEffects(['schlafzimmer'], mapOf(entity(licht.entityId, 'on')), NO_BRIGHTNESS, NO_POSITIONS, NO_LINKED);
    expect(effect.floorY).toBeCloseTo(floorBase('upper'), 6);
    expect(effect.anchor.y).toBeGreaterThan(effect.floorY + 2);
  });

  it('gespeicherter Platz bestimmt den Anker des Effekts', () => {
    const positions = new Map([[stehlampe.id, { x: 300, y: 150 }]]);
    const [effect] = buildHouseEffects(['wohnzimmer'], mapOf(entity(stehlampe.entityId, 'on')), NO_BRIGHTNESS, positions, NO_LINKED);
    expect(effect.anchor).toEqual(deviceAnchor(stehlampe.id, positions)!);
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
