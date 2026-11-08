import {
  BALCONY_DOOR_ENTITY_ID,
  BEDROOM_TEMPERATURE_ENTITY_ID,
  buildBalconyColdInsight
} from './balcony-cold-insight.util';
import { EntityState } from '../models/entity-state.model';

function entity(entityId: string, overrides: Partial<EntityState> = {}): EntityState {
  return {
    entityId,
    domain: entityId.startsWith('sensor.') ? 'SENSOR' : 'BINARY_SENSOR',
    source: entityId.startsWith('sensor.') ? 'ALEXA' : 'DIRIGERA',
    sourceRef: entityId,
    friendlyName: entityId,
    displayName: entityId,
    state: 'off',
    attributes: {},
    lastChanged: '2026-11-08T18:00:00',
    lastUpdated: '2026-11-08T18:00:00',
    ...overrides
  };
}

const NOW_MS = new Date('2026-11-08T18:12:00').getTime();

function openDoor(lastChanged = '2026-11-08T18:00:00'): EntityState[] {
  return [entity(BALCONY_DOOR_ENTITY_ID, { state: 'on', lastChanged })];
}

function bedroom(state: string, lastUpdated = '2026-11-08T18:11:00'): EntityState {
  return entity(BEDROOM_TEMPERATURE_ENTITY_ID, { state, lastUpdated });
}

describe('buildBalconyColdInsight', () => {

  it('warnt, wenn die Balkontuer 10 Min. offen ist und es unter 15 °C hat', () => {
    const insight = buildBalconyColdInsight(openDoor(), bedroom('14.6'), NOW_MS);

    expect(insight).not.toBeNull();
    expect(insight!.title).toBe('Schlafzimmer kühlt aus');
    expect(insight!.tone).toBe('error');
    expect(insight!.text).toBe('Balkontür offen, nur noch 14,6 °C. Offen seit 12 Minuten.');
  });

  it('warnt noch nicht, solange die Tuer kuerzer als 10 Min. offen ist', () => {
    expect(buildBalconyColdInsight(openDoor('2026-11-08T18:03:00'), bedroom('13'), NOW_MS)).toBeNull();
  });

  it('warnt nicht bei 15 °C oder waermer', () => {
    expect(buildBalconyColdInsight(openDoor(), bedroom('15'), NOW_MS)).toBeNull();
  });

  it('warnt nicht bei geschlossener oder nicht erreichbarer Tuer', () => {
    const closed = [entity(BALCONY_DOOR_ENTITY_ID, { state: 'off' })];
    const unavailable = [entity(BALCONY_DOOR_ENTITY_ID, { state: 'unavailable' })];

    expect(buildBalconyColdInsight(closed, bedroom('10'), NOW_MS)).toBeNull();
    expect(buildBalconyColdInsight(unavailable, bedroom('10'), NOW_MS)).toBeNull();
  });

  it('raet nicht ohne brauchbare Temperatur', () => {
    expect(buildBalconyColdInsight(openDoor(), undefined, NOW_MS)).toBeNull();
    expect(buildBalconyColdInsight(openDoor(), bedroom('unavailable'), NOW_MS)).toBeNull();
    expect(buildBalconyColdInsight(openDoor(), bedroom(''), NOW_MS)).toBeNull();
    expect(buildBalconyColdInsight(openDoor(), bedroom('12', '2026-11-08T16:00:00'), NOW_MS)).toBeNull();
  });
});
