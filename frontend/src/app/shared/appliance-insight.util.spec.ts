import { buildApplianceInsights } from './appliance-insight.util';
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

  it('macht die Lauf-Karte NICHT antippbar', () => {
    const entities = [
      helper('input_boolean.manual_spuelmaschine_laeuft', { state: 'on', lastChanged: RUNNING_SINCE })
    ];

    expect(buildApplianceInsights(entities, NOW_MS)[0].dismissEntityId).toBeUndefined();
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
});
