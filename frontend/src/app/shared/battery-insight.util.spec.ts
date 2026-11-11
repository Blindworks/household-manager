import { buildLockBatteryInsight, buildTrackerBatteryInsight } from './battery-insight.util';
import { NukiLock } from '../models/nuki.model';
import { TractivePet } from '../models/tractive.model';

function pet(overrides: Partial<TractivePet> = {}): TractivePet {
  return {
    trackerId: 'TRACKER1',
    name: 'Toni',
    batteryPercent: 32,
    charging: false,
    zone: 'Zuhause',
    ...overrides
  };
}

describe('buildTrackerBatteryInsight', () => {

  it('liefert keine Karte ohne Tiere', () => {
    expect(buildTrackerBatteryInsight([])).toBeNull();
  });

  it('liefert keine Karte bei vollem Akku', () => {
    expect(buildTrackerBatteryInsight([pet({ batteryPercent: 80 })])).toBeNull();
  });

  it('liefert keine Karte exakt auf der Schwelle', () => {
    expect(buildTrackerBatteryInsight([pet({ batteryPercent: 40 })])).toBeNull();
  });

  it('liefert keine Karte ohne Akkustand', () => {
    // Defensive: batteryPercent fehlt bei Cloud-Ausfall oder stillem Tracker —
    // dann wird nichts geraten (Muster atHome).
    expect(buildTrackerBatteryInsight([pet({ batteryPercent: undefined })])).toBeNull();
  });

  it('liefert keine Karte, waehrend der Tracker laedt', () => {
    expect(buildTrackerBatteryInsight([pet({ charging: true, batteryPercent: 10 })])).toBeNull();
  });

  it('warnt gelb unter 40 Prozent', () => {
    const insight = buildTrackerBatteryInsight([pet()]);

    expect(insight?.title).toBe('Hundetracker');
    expect(insight?.icon).toBe('battery_alert');
    expect(insight?.tone).toBe('tertiary');
    expect(insight?.text).toBe('Tracker-Akku von Toni: 32 %');
  });

  it('warnt rot unter 20 Prozent', () => {
    expect(buildTrackerBatteryInsight([pet({ batteryPercent: 19 })])?.tone).toBe('error');
  });

  it('fasst mehrere Tiere zusammen, der niedrigste Stand bestimmt den Ton', () => {
    const insight = buildTrackerBatteryInsight([
      pet(),
      pet({ trackerId: 'TRACKER2', name: 'Bello', batteryPercent: 12 })
    ]);

    expect(insight?.tone).toBe('error');
    expect(insight?.text).toBe('Tracker-Akku von Toni: 32 % · Tracker-Akku von Bello: 12 %');
  });
});

function lock(overrides: Partial<NukiLock> = {}): NukiLock {
  return {
    smartlockId: 1,
    name: 'Haustür',
    state: 'locked',
    doorState: 'off',
    batteryCharge: 27,
    batteryCritical: false,
    ...overrides
  };
}

describe('buildLockBatteryInsight', () => {

  it('liefert keine Karte ohne Schloss', () => {
    expect(buildLockBatteryInsight([])).toBeNull();
  });

  it('liefert keine Karte exakt auf 30 Prozent', () => {
    expect(buildLockBatteryInsight([lock({ batteryCharge: 30 })])).toBeNull();
  });

  it('warnt gelb unter 30 Prozent', () => {
    const insight = buildLockBatteryInsight([lock()])!;
    expect(insight.tone).toBe('tertiary');
    expect(insight.title).toBe('Türschloss');
    expect(insight.text).toBe('Akku von Haustür: 27 % – bald wechseln');
  });

  it('warnt rot unter 20 Prozent', () => {
    expect(buildLockBatteryInsight([lock({ batteryCharge: 19 })])!.tone).toBe('error');
    expect(buildLockBatteryInsight([lock({ batteryCharge: 20 })])!.tone).toBe('tertiary');
  });

  it('warnt rot, wenn das Schloss den Akku als kritisch meldet', () => {
    const insight = buildLockBatteryInsight([lock({ batteryCharge: null, batteryCritical: true })])!;
    expect(insight.tone).toBe('error');
    expect(insight.text).toBe('Akku von Haustür kritisch – bald wechseln');
  });

  it('raet ohne Akkuwert nichts', () => {
    expect(buildLockBatteryInsight([lock({ batteryCharge: null })])).toBeNull();
  });
});
