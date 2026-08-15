import { buildDoorInsights } from './door-insight.util';
import { EntityState } from '../models/entity-state.model';

function contact(entityId: string, overrides: Partial<EntityState> = {}): EntityState {
  return {
    entityId,
    domain: 'BINARY_SENSOR',
    source: 'ZIGBEE',
    sourceRef: entityId,
    friendlyName: entityId,
    displayName: entityId,
    state: 'off',
    attributes: {},
    lastChanged: '2026-08-14T17:46:32',
    lastUpdated: '2026-08-14T17:46:32',
    ...overrides
  };
}

/** Bezugszeitpunkt am selben Tag wie die Test-Zeitstempel. */
const NOW_MS = new Date('2026-08-14T19:00:00').getTime();

describe('buildDoorInsights', () => {

  it('liefert keine Karten, wenn beide Tueren zu sind', () => {
    const entities = [
      contact('binary_sensor.zigbee_eingangstuer_contact'),
      contact('binary_sensor.zigbee_terassentuer_contact')
    ];

    expect(buildDoorInsights(entities, NOW_MS)).toEqual([]);
  });

  it('baut je offener Tuer eine Karte mit Uhrzeit', () => {
    const entities = [
      contact('binary_sensor.zigbee_eingangstuer_contact', { state: 'on' }),
      contact('binary_sensor.zigbee_terassentuer_contact', { state: 'on', lastChanged: '2026-08-14T08:05:00' })
    ];

    const insights = buildDoorInsights(entities, NOW_MS);

    expect(insights.length).toBe(2);
    expect(insights[0].title).toBe('Haustür offen');
    expect(insights[0].icon).toBe('door_open');
    expect(insights[0].tone).toBe('tertiary');
    expect(insights[0].text).toBe('Offen seit 17:46 Uhr.');
    expect(insights[1].title).toBe('Terrassentür offen');
    expect(insights[1].text).toBe('Offen seit 08:05 Uhr.');
  });

  it('nennt das Datum, wenn die Tuer seit einem frueheren Tag offen steht', () => {
    const entities = [
      contact('binary_sensor.zigbee_eingangstuer_contact', { state: 'on', lastChanged: '2026-08-13T22:10:00' })
    ];

    expect(buildDoorInsights(entities, NOW_MS)[0].text).toBe('Offen seit 13.08., 22:10 Uhr.');
  });

  it('wertet unavailable nicht als offen', () => {
    const entities = [
      contact('binary_sensor.zigbee_eingangstuer_contact', { state: 'unavailable' })
    ];

    expect(buildDoorInsights(entities, NOW_MS)).toEqual([]);
  });

  it('ignoriert fremde Kontakte wie Fenster', () => {
    const entities = [
      contact('binary_sensor.zigbee_fenster_badezimmer_contact', { state: 'on' })
    ];

    expect(buildDoorInsights(entities, NOW_MS)).toEqual([]);
  });

  it('faellt bei unlesbarem Zeitstempel auf einen neutralen Text zurueck', () => {
    const entities = [
      contact('binary_sensor.zigbee_eingangstuer_contact', { state: 'on', lastChanged: 'kaputt' })
    ];

    expect(buildDoorInsights(entities, NOW_MS)[0].text).toBe('Die Tür ist gerade offen.');
  });
});
