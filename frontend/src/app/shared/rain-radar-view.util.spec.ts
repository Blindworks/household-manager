import { EntityState } from '../models/entity-state.model';
import { buildRainRadarView, RAIN_RADAR_INTENSITY_ENTITY_ID } from './rain-radar-view.util';

describe('buildRainRadarView', () => {
  const now = new Date('2026-11-17T15:00:00').getTime();

  function intensity(state: string, attributes: Record<string, unknown> = {}): EntityState {
    return {
      entityId: RAIN_RADAR_INTENSITY_ENTITY_ID,
      domain: 'SENSOR',
      source: 'RAIN_RADAR',
      sourceRef: 'home',
      friendlyName: 'Regenintensität',
      displayName: 'Regenintensität',
      state,
      attributes: { measuredAt: '2026-11-17T14:55:00', thresholdMmPerHour: 0.3, ...attributes },
      lastChanged: '',
      lastUpdated: ''
    };
  }

  it('zeigt nichts ohne Entität oder bei unavailable', () => {
    expect(buildRainRadarView([], now)).toBeNull();
    expect(buildRainRadarView([intensity('unavailable')], now)).toBeNull();
  });

  it('zeigt trocken, wenn nichts erwartet wird', () => {
    const view = buildRainRadarView([intensity('0.12')], now);
    expect(view).toEqual(jasmine.objectContaining({ tone: 'dry', value: 'trocken', stale: false }));
    expect(view?.title).toContain('14:55');
  });

  it('zeigt die Intensität, wenn es regnet', () => {
    expect(buildRainRadarView([intensity('1.2')], now))
      .toEqual(jasmine.objectContaining({ tone: 'raining', value: '1,2 mm/h', label: 'Regen am Haus' }));
  });

  it('rechnet die Minuten bis zum Regen gegen jetzt', () => {
    const view = buildRainRadarView([intensity('0', { expectedRainAt: '2026-11-17T15:12:00' })], now);
    expect(view).toEqual(jasmine.objectContaining({ tone: 'soon', value: 'in 12 Min.' }));
  });

  it('zeigt späteren Regen über einer Stunde als Uhrzeit', () => {
    const view = buildRainRadarView([intensity('0', { expectedRainAt: '2026-11-17T16:40:00' })], now);
    expect(view).toEqual(jasmine.objectContaining({ tone: 'later', value: 'ab 16:40' }));
  });

  it('dunkelt eine alte Messung ab', () => {
    expect(buildRainRadarView([intensity('0', { measuredAt: '2026-11-17T14:00:00' })], now)?.stale).toBeTrue();
  });
});
