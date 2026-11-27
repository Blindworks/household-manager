import {
  teslaBatteryTone,
  teslaButtons,
  teslaCommandStillApplies,
  teslaRangeLabel,
  teslaStaleLabel,
  teslaStatusItems
} from './tesla-view.util';
import { TeslaVehicle } from '../models/tesla.model';

const base: TeslaVehicle = { vin: 'V1', batteryLevel: 55, rangeKm: 275.7 };

describe('tesla-view.util', () => {
  it('färbt den Akku nach Stufen', () => {
    expect(teslaBatteryTone(15)).toBe('low');
    expect(teslaBatteryTone(30)).toBe('mid');
    expect(teslaBatteryTone(55)).toBe('ok');
  });

  it('rundet die Reichweite auf ganze Kilometer', () => {
    expect(teslaRangeLabel(275.7)).toBe('276 km');
    expect(teslaRangeLabel(undefined)).toBe('–');
  });

  it('zeigt Zuhause, Ladeplan und Ladezustand', () => {
    const items = teslaStatusItems({
      ...base, atHome: true, scheduledChargingPending: true, scheduledChargeAt: '2026-11-28T02:00:00',
      chargingState: 'Charging', chargerPowerKw: 11, minutesToFullCharge: 95
    });
    expect(items.map(i => i.text)).toEqual(['Zuhause', 'Laden ab 02:00', 'Lädt · 11 kW · voll in 1 h 35 min']);
  });

  it('ohne Position keine Ortszeile, ohne Plan „Kein Ladeplan“', () => {
    const items = teslaStatusItems({ ...base, chargingState: 'Disconnected' });
    expect(items.map(i => i.text)).toEqual(['Kein Ladeplan', 'Lädt nicht']);
  });

  it('bietet bei unbekanntem Schloss Verriegeln an, nie Entriegeln', () => {
    expect(teslaButtons({ ...base, locked: undefined })[0].command).toBe('LOCK');
    expect(teslaButtons({ ...base, locked: false })[0].command).toBe('LOCK');
  });

  it('Entriegeln fragt nach, die übrigen nicht', () => {
    const buttons = teslaButtons({ ...base, locked: true });
    expect(buttons.filter(b => b.confirm).map(b => b.command)).toEqual(['UNLOCK']);
  });

  it('schaltet Klima, Klappe und Enteisen in die Gegenrichtung', () => {
    const commands = teslaButtons({ ...base, chargePortOpen: true, climateOn: true, defrostOn: true })
      .map(b => b.command);
    expect(commands).toEqual(['LOCK', 'CLOSE_CHARGE_PORT', 'STOP_CLIMATE', 'STOP_DEFROST']);
  });

  it('erkennt, dass ein Befehl nicht mehr passt', () => {
    expect(teslaCommandStillApplies({ ...base, locked: false }, 'UNLOCK')).toBeFalse();
    expect(teslaCommandStillApplies({ ...base, locked: true }, 'UNLOCK')).toBeTrue();
    expect(teslaCommandStillApplies(undefined, 'LOCK')).toBeFalse();
  });

  it('nennt den Stand erst nach 15 Minuten', () => {
    const now = new Date('2026-11-27T09:00:00');
    expect(teslaStaleLabel('2026-11-27T08:50:00', now)).toBeNull();
    expect(teslaStaleLabel('2026-11-27T08:30:00', now)).toBe('Stand 08:30');
    expect(teslaStaleLabel('2026-11-26T18:05:00', now)).toBe('Stand 26.11. 18:05');
  });
});
