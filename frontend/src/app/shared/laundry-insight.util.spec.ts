import { LaundryState } from '../models/laundry.model';
import { buildLaundryInsight } from './laundry-insight.util';

describe('buildLaundryInsight', () => {
  const now = new Date(2026, 10, 12, 9, 0, 0);

  const state = (overrides: Partial<LaundryState>): LaundryState => ({
    status: 'drying',
    prompt: 'NONE',
    startedAt: '2026-11-12T08:00:00',
    predictedDryAt: null,
    estimatePaused: false,
    requiredLoad: 1,
    ...overrides
  });

  it('keineKarteBeiIdleOderAsking', () => {
    expect(buildLaundryInsight(null, now)).toBeNull();
    expect(buildLaundryInsight(state({ status: 'idle' }), now)).toBeNull();
    expect(buildLaundryInsight(state({ status: 'asking', prompt: 'HUNG' }), now)).toBeNull();
  });

  it('trocknetMitSchaetzung', () => {
    const insight = buildLaundryInsight(state({ predictedDryAt: '2026-11-13T14:05:00' }), now);

    expect(insight?.title).toBe('Wäsche trocknet');
    expect(insight?.text).toBe('Voraussichtlich trocken morgen gegen 14 Uhr');
    expect(insight?.tapAction).toBe('laundry-removed');
    expect(insight?.tone).toBe('primary');
    expect(insight?.icon).toBe('dry_cleaning');
  });

  it('rundetAufDieNaechsteVolleStunde', () => {
    const insight = buildLaundryInsight(state({ predictedDryAt: '2026-11-12T14:40:00' }), now);

    expect(insight?.text).toBe('Voraussichtlich trocken heute gegen 15 Uhr');
  });

  it('rundenUeberMitternachtWechseltDenTag', () => {
    const insight = buildLaundryInsight(state({ predictedDryAt: '2026-11-12T23:40:00' }), now);

    expect(insight?.text).toBe('Voraussichtlich trocken morgen gegen 0 Uhr');
  });

  it('nutztWochentagAbDreiTagen', () => {
    const insight = buildLaundryInsight(state({ predictedDryAt: '2026-11-16T10:00:00' }), now);

    expect(insight?.text).toBe('Voraussichtlich trocken Montag gegen 10 Uhr');
  });

  it('ohneSchaetzungFolgtSie', () => {
    const insight = buildLaundryInsight(state({}), now);

    expect(insight?.text).toBe('Voraussichtlich trocken – Schätzung folgt');
  });

  it('pausiert', () => {
    const insight = buildLaundryInsight(
      state({ estimatePaused: true, predictedDryAt: '2026-11-13T14:05:00' }), now);

    expect(insight?.text).toBe('Schätzung pausiert – Kellerfühler meldet nichts');
  });

  it('koennteTrockenSein', () => {
    const insight = buildLaundryInsight(state({ status: 'maybe_dry' }), now);

    expect(insight?.title).toBe('Wäsche könnte trocken sein');
    expect(insight?.text).toBe('Die Wäsche im Keller könnte jetzt trocken sein.');
    expect(insight?.tone).toBe('tertiary');
    expect(insight?.tapAction).toBe('laundry-removed');
  });
});
