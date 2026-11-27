import { buildApplianceInsights, checkApplianceRun } from './appliance-insight.util';
import { EntityState } from '../models/entity-state.model';

function helper(entityId: string, overrides: Partial<EntityState> = {}): EntityState {
  return {
    entityId,
    domain: 'INPUT_BOOLEAN',
    source: 'MANUAL',
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

describe('buildApplianceInsights', () => {

  it('liefert keine Karte, solange kein Helfer auf on steht', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_fertig'),
      helper('input_boolean.manual_spuelmaschine_fertig')
    ];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('baut fuer die fertige Waschmaschine eine antippbare Karte mit Uhrzeit', () => {
    const entities = [helper('input_boolean.manual_waschmaschine_fertig', { state: 'on' })];

    const insights = buildApplianceInsights(entities, NOW_MS);

    expect(insights.length).toBe(1);
    expect(insights[0].title).toBe('Waschmaschine fertig');
    expect(insights[0].icon).toBe('local_laundry_service');
    expect(insights[0].tone).toBe('primary');
    expect(insights[0].text).toBe('Fertig seit 17:46 Uhr.');
    expect(insights[0].dismissEntityId).toBe('input_boolean.manual_waschmaschine_fertig');
  });

  it('baut zwei Karten in stabiler Reihenfolge, wenn beide Maschinen fertig sind', () => {
    const entities = [
      helper('input_boolean.manual_spuelmaschine_fertig', { state: 'on' }),
      helper('input_boolean.manual_waschmaschine_fertig', { state: 'on' })
    ];

    const titles = buildApplianceInsights(entities, NOW_MS).map(insight => insight.title);

    expect(titles).toEqual(['Waschmaschine fertig', 'Spülmaschine fertig']);
  });

  it('nennt das Datum, wenn die Maschine seit einem frueheren Tag fertig ist', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_fertig', { state: 'on', lastChanged: '2026-08-13T22:10:00' })
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].text).toBe('Fertig seit 13.08., 22:10 Uhr.');
  });

  it('wertet unavailable nicht als fertig', () => {
    const entities = [helper('input_boolean.manual_waschmaschine_fertig', { state: 'unavailable' })];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('ignoriert fremde Helfer wie den Nachtmodus', () => {
    const entities = [helper('input_boolean.manual_nachtmodus', { state: 'on' })];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('faellt bei unlesbarem Zeitstempel auf einen neutralen Text zurueck', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_fertig', { state: 'on', lastChanged: 'kaputt' })
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].text).toBe('Die Maschine ist fertig.');
  });
});

describe('buildApplianceInsights — laufende Maschinen', () => {

  /** Zeitstempel 42 Minuten vor NOW_MS. */
  const RUNNING_SINCE = new Date(NOW_MS - 42 * 60_000).toISOString();

  it('liefert keine Karte, solange kein Laeuft-Helfer auf on steht', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft'),
      helper('input_boolean.manual_spuelmaschine_laeuft')
    ];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('baut fuer die laufende Waschmaschine eine Karte mit verstrichener Laufzeit', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE })
    ];

    const insights = buildApplianceInsights(entities, NOW_MS);

    expect(insights.length).toBe(1);
    expect(insights[0].title).toBe('Waschmaschine läuft');
    expect(insights[0].icon).toBe('local_laundry_service');
    expect(insights[0].tone).toBe('secondary');
    expect(insights[0].text).toBe('Läuft seit 42 Minuten.');
  });

  it('makes the running card a check card, never a direct dismiss', () => {
    const entities = [
      helper('input_boolean.manual_spuelmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE })
    ];

    const insight = buildApplianceInsights(entities, NOW_MS)[0];
    expect(insight.dismissEntityId).toBeUndefined();
    expect(insight.checkApplianceEntityId).toBe('input_boolean.manual_spuelmaschine_laeuft');
  });

  it('shows the current wattage of a fresh reading', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE }),
      power('1234.4', NOW_MS - 60_000)
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].text).toBe('Läuft seit 42 Minuten. Aktuell 1.234 W.');
  });

  it('leaves out a stale wattage', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE }),
      power('3', NOW_MS - 10 * 60_000)
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].text).toBe('Läuft seit 42 Minuten.');
  });

  it('wertet unavailable nicht als laufend', () => {
    const entities = [helper('input_boolean.manual_waschmaschine_laeuft', { state: 'unavailable' })];

    expect(buildApplianceInsights(entities, NOW_MS)).toEqual([]);
  });

  it('faellt bei unlesbarem Zeitstempel auf einen neutralen Text zurueck', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on', lastChanged: 'kaputt' })
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].text).toBe('Die Maschine läuft gerade.');
  });

  it('stellt fertige Maschinen vor laufende', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE }),
      helper('input_boolean.manual_spuelmaschine_fertig', { state: 'on' })
    ];

    const titles = buildApplianceInsights(entities, NOW_MS).map(insight => insight.title);

    expect(titles).toEqual(['Spülmaschine fertig', 'Waschmaschine läuft']);
  });

  it('baut zwei Lauf-Karten in stabiler Reihenfolge, wenn beide Maschinen laufen', () => {
    const entities = [
      helper('input_boolean.manual_spuelmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE }),
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE })
    ];

    const titles = buildApplianceInsights(entities, NOW_MS).map(insight => insight.title);

    expect(titles).toEqual(['Waschmaschine läuft', 'Spülmaschine läuft']);
  });

  it('warnt auf der Lauf-Karte, wenn die Steckdose nicht erreichbar ist', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on' }),
      helper('sensor.meross_2112156531504590863548e1e9817420_power', {
        domain: 'SENSOR', source: 'MEROSS', state: '1200', attributes: { deviceClass: 'power', reachable: false }
      })
    ];

    const insights = buildApplianceInsights(entities, NOW_MS);

    expect(insights.length).toBe(1);
    expect(insights[0].tone).toBe('tertiary');
    expect(insights[0].text).toContain('Steckdose nicht erreichbar');
  });

  it('laesst die Lauf-Karte unveraendert, solange die Steckdose erreichbar ist', () => {
    const entities = [
      helper('input_boolean.manual_waschmaschine_laeuft', { state: 'on' }),
      helper('sensor.meross_2112156531504590863548e1e9817420_power', {
        domain: 'SENSOR', source: 'MEROSS', state: '1200', attributes: { deviceClass: 'power', reachable: true }
      })
    ];

    const insights = buildApplianceInsights(entities, NOW_MS);

    expect(insights[0].tone).toBe('secondary');
    expect(insights[0].text).not.toContain('Steckdose');
  });
});

const WASHER_POWER = 'sensor.meross_2112156531504590863548e1e9817420_power';
const WASHER_RUNNING = 'input_boolean.manual_waschmaschine_laeuft';

function power(state: string, updatedMs: number, attributes: Record<string, unknown> = { reachable: true }): EntityState {
  const stamp = new Date(updatedMs).toISOString();
  return helper(WASHER_POWER, { domain: 'SENSOR', source: 'MEROSS', state, attributes, lastChanged: stamp, lastUpdated: stamp });
}

describe('checkApplianceRun', () => {

  it('reports stopped for a fresh reading below 5 W', () => {
    expect(checkApplianceRun(WASHER_RUNNING, [power('0.8', NOW_MS - 30_000)], NOW_MS)).toBe('stopped');
  });

  it('reports running at 5 W and above', () => {
    expect(checkApplianceRun(WASHER_RUNNING, [power('5', NOW_MS - 30_000)], NOW_MS)).toBe('running');
  });

  it('does not trust a stale reading', () => {
    expect(checkApplianceRun(WASHER_RUNNING, [power('0', NOW_MS - 6 * 60_000)], NOW_MS)).toBe('unknown');
  });

  it('does not trust an unreachable plug', () => {
    expect(checkApplianceRun(WASHER_RUNNING, [power('0', NOW_MS, { reachable: false })], NOW_MS)).toBe('unknown');
  });

  it('is unknown without a sensor, for unavailable and for an unknown helper', () => {
    expect(checkApplianceRun(WASHER_RUNNING, [], NOW_MS)).toBe('unknown');
    expect(checkApplianceRun(WASHER_RUNNING, [power('unavailable', NOW_MS)], NOW_MS)).toBe('unknown');
    expect(checkApplianceRun('input_boolean.manual_nachtmodus', [power('0', NOW_MS)], NOW_MS)).toBe('unknown');
  });
});
