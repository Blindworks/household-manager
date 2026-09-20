import {
  formatOccupiedDuration,
  formatPower,
  formatPrice,
  stationTone,
  sortStations
} from './charging-status.util';
import { stationPopupText } from './charging-map.util';
import { ChargingStation } from '../models/charging.model';

describe('charging-status.util', () => {
  const NOW = new Date('2026-09-19T12:00:00');

  const station = (overrides: Partial<ChargingStation>): ChargingStation => ({
    stationId: 'x', name: 'X', lat: 50, lon: 8, distanceMeters: 1000, total: 2, free: 1, favorite: false,
    ...overrides
  });

  describe('stationTone', () => {
    it('ist gruen bei mindestens einem freien Ladepunkt', () => {
      expect(stationTone(station({ free: 1 }))).toBe('free');
    });

    it('ist rot ohne freien Ladepunkt', () => {
      expect(stationTone(station({ free: 0, total: 2 }))).toBe('busy');
    });

    it('ist grau ohne Ladepunkte (ausser Betrieb oder unbekannt)', () => {
      expect(stationTone(station({ free: 0, total: 0 }))).toBe('unknown');
    });
  });

  describe('formatOccupiedDuration', () => {
    it('zeigt Minuten bei bekanntem Beginn', () => {
      expect(formatOccupiedDuration('2026-09-19T11:22:00', false, NOW)).toBe('seit 38 min');
    });

    it('zeigt "mind." bei unbekanntem Beginn', () => {
      expect(formatOccupiedDuration('2026-09-19T11:22:00', true, NOW)).toBe('seit mind. 38 min');
    });

    it('zeigt Stunden und Minuten ab einer Stunde', () => {
      expect(formatOccupiedDuration('2026-09-19T09:55:00', false, NOW)).toBe('seit 2 h 05 min');
    });

    it('klemmt Zeitversatz in die Zukunft auf null', () => {
      expect(formatOccupiedDuration('2026-09-19T12:03:00', false, NOW)).toBe('seit 0 min');
    });

    it('liefert leer ohne Beginn', () => {
      expect(formatOccupiedDuration(undefined, false, NOW)).toBe('');
    });
  });

  describe('formatPower', () => {
    it('rundet auf ganze kW', () => {
      expect(formatPower(149.6)).toBe('150 kW');
    });

    it('liefert Strich ohne Angabe', () => {
      expect(formatPower(undefined)).toBe('–');
    });
  });

  describe('formatPrice', () => {
    it('formatiert mit Komma und Einheit', () => {
      expect(formatPrice(0.34)).toBe('0,34 €/kWh');
      expect(formatPrice(1.05)).toBe('1,05 €/kWh');
    });

    it('liefert leer ohne Preis', () => {
      expect(formatPrice(undefined)).toBe('');
    });
  });

  describe('stationPopupText', () => {
    it('zeigt Name, Betreiber, Adresse und die Kennzahlen inklusive Preis', () => {
      const html = stationPopupText(station({
        name: 'Lidl Hauptstr.', operator: 'EnBW', address: 'Hauptstr. 1, 12345 Stadt',
        free: 1, total: 2, maxPowerKw: 150, pricePerKwh: 0.34, favorite: true
      }));
      expect(html).toContain('Lidl Hauptstr.');
      expect(html).toContain('EnBW · Hauptstr. 1, 12345 Stadt');
      expect(html).toContain('150 kW');
      expect(html).toContain('0,34 €/kWh');
      expect(html).toContain('charging-popup__star');
      expect(html).toContain('charging-popup__value--free');
    });

    it('laesst die Preiskachel ohne Preis weg und escaped Fremdtexte', () => {
      const html = stationPopupText(station({ name: '<b>x</b>', operator: 'A&B' }));
      expect(html).not.toContain('EnBW-Tarif');
      expect(html).toContain('&lt;b&gt;x&lt;/b&gt;');
      expect(html).toContain('A&amp;B');
    });
  });

  describe('sortStations', () => {
    it('stellt Favoriten voran und sortiert innerhalb nach Entfernung', () => {
      const sorted = sortStations([
        station({ stationId: 'fern', distanceMeters: 5000 }),
        station({ stationId: 'favFern', distanceMeters: 8000, favorite: true }),
        station({ stationId: 'nah', distanceMeters: 500 }),
        station({ stationId: 'favNah', distanceMeters: 3000, favorite: true })
      ]);
      expect(sorted.map(s => s.stationId)).toEqual(['favNah', 'favFern', 'nah', 'fern']);
    });
  });
});
