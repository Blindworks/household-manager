import {
  formatDirectPrice,
  formatDistance,
  formatOccupiedDuration,
  formatPower,
  formatPrice,
  parseDirectPrice,
  stationTone,
  superchargerMeta,
  sortStations,
  stationsInView,
  withChargePoints
} from './charging-status.util';
import { MARKER_GEOMETRY, stationIcon, stationPopupText } from './charging-map.util';
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

    it('ist bei Superchargern neutral grau, obwohl nie ein Platz als frei gemeldet wird', () => {
      expect(stationTone(station({ supercharger: true, free: 0, total: 15 }))).toBe('unknown');
    });
  });

  describe('stationsInView', () => {
    const inside = station({ stationId: 'in', lat: 50, lon: 8 });
    const outside = station({ stationId: 'out', lat: 52, lon: 13 });
    const favoriteOutside = station({ stationId: 'fav', lat: 52, lon: 13, favorite: true });
    const nearFrankfurt = (lat: number, lon: number): boolean => Math.abs(lat - 50) < 1 && Math.abs(lon - 8) < 1;

    it('behaelt nur Stationen im Kartenausschnitt, Favoriten immer', () => {
      expect(stationsInView([inside, outside, favoriteOutside], nearFrankfurt).map(s => s.stationId))
        .toEqual(['in', 'fav']);
    });

    it('laesst ohne bekannten Ausschnitt alle Stationen stehen', () => {
      expect(stationsInView([inside, outside], null).length).toBe(2);
    });
  });

  describe('superchargerMeta', () => {
    it('nennt Plaetze, Leistung und dass die Belegung unbekannt ist', () => {
      expect(superchargerMeta(station({ supercharger: true, total: 15, maxPowerKw: 250 })))
        .toBe('15 Plätze · bis 250 kW · Belegung unbekannt');
    });

    it('laesst die Leistung ohne Angabe weg', () => {
      expect(superchargerMeta(station({ supercharger: true, total: 15 })))
        .toBe('15 Plätze · Belegung unbekannt');
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

  describe('formatDirectPrice', () => {
    it('haengt die Notiz mit Mittelpunkt an', () => {
      expect(formatDirectPrice(0.39, 'Aldi-App')).toBe('0,39 €/kWh · Aldi-App');
    });

    it('zeigt ohne Notiz nur den Preis', () => {
      expect(formatDirectPrice(0.39, undefined)).toBe('0,39 €/kWh');
      expect(formatDirectPrice(0.39, '')).toBe('0,39 €/kWh');
    });

    it('liefert leer ohne Preis, auch wenn eine Notiz da ist', () => {
      expect(formatDirectPrice(undefined, 'Aldi-App')).toBe('');
    });
  });

  describe('parseDirectPrice', () => {
    it('versteht Komma und Punkt', () => {
      expect(parseDirectPrice('0,39')).toBe(0.39);
      expect(parseDirectPrice(' 0.395 ')).toBe(0.395);
      expect(parseDirectPrice('2')).toBe(2);
    });

    it('weist Ungueltiges ab', () => {
      for (const bad of ['', 'abc', '0', '-0,1', '2,001', '0,3901', 'NaN', 'Infinity', '1,2,3']) {
        expect(parseDirectPrice(bad)).withContext(bad).toBeUndefined();
      }
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
      expect(html).toContain('Direktpreis');
      expect(html).not.toContain('EnBW-Tarif');
      expect(html).toContain('charging-popup__star');
      expect(html).toContain('charging-popup__value--free');
    });

    it('nennt die Notiz zum Direktpreis im Popup', () => {
      const html = stationPopupText(station({ favorite: true, pricePerKwh: 0.39, priceNote: 'Aldi-App' }));
      expect(html).toContain('0,39 €/kWh');
      expect(html).toContain('Aldi-App');
    });

    it('listet die Ladepunkte eines Favoriten mit Dauer, aber ohne EnBW-Preis', () => {
      const html = stationPopupText(station({
        favorite: true,
        chargePoints: [
          { chargePointId: 'P1', status: 'OCCUPIED', maxPowerKw: 150, occupiedSince: '2026-09-19T11:22:00',
            minimumDuration: false, pricePerKwh: 0.34 },
          { chargePointId: 'P2', status: 'FREE', maxPowerKw: 150, minimumDuration: false, pricePerKwh: 0.34 }
        ]
      }), NOW);
      expect(html).toContain('charging-popup__points');
      expect(html).toContain('charging-popup__point--occupied');
      expect(html).toContain('seit 38 min');
      expect(html).toContain('belegt');
      expect(html).toContain('frei');
      expect(html).not.toContain('charging-popup__point-price');
      expect(html).not.toContain('0,34');
    });

    it('zeigt bei Superchargern Standortdaten statt frei/gesamt und markiert nur-Tesla', () => {
      const html = stationPopupText(station({
        name: 'Tesla Supercharger Pfungstadt', operator: 'Tesla', address: 'Hahnstr. 1, Pfungstadt',
        supercharger: true, teslaOnly: true, total: 15, free: 0, maxPowerKw: 250
      }));
      expect(html).toContain('Tesla Supercharger Pfungstadt');
      expect(html).toContain('15 Plätze · bis 250 kW · Belegung unbekannt');
      expect(html).toContain('nur Tesla');
      expect(html).not.toContain('frei');
    });

    it('laesst "nur Tesla" bei offenen Superchargern weg', () => {
      const html = stationPopupText(station({ supercharger: true, total: 6 }));
      expect(html).not.toContain('nur Tesla');
    });

    it('zeigt ohne Ladepunkte keine Liste', () => {
      expect(stationPopupText(station({}))).not.toContain('charging-popup__points');
    });

    it('laesst die Preiskachel ohne Preis weg und escaped Fremdtexte', () => {
      const html = stationPopupText(station({ name: '<b>x</b>', operator: 'A&B' }));
      expect(html).not.toContain('EnBW-Tarif');
      expect(html).toContain('&lt;b&gt;x&lt;/b&gt;');
      expect(html).toContain('A&amp;B');
    });
  });

  describe('stationIcon', () => {
    it('legt bei der Tropfenform den Anker auf die Spitze (unten Mitte), sonst wandert der Pin beim Zoomen', () => {
      const pin = stationIcon(station({}), 'pin').options;
      expect(pin.iconSize).toEqual([30, 40]);
      expect(pin.iconAnchor).toEqual([15, 40]);
      expect(pin.className).toContain('charging-marker--pin');
    });

    it('nutzt fuer den Kreis die Mitte als Anker', () => {
      const dot = stationIcon(station({})).options;
      expect(dot.iconAnchor).toEqual(MARKER_GEOMETRY.dot.anchor);
      expect(dot.iconAnchor).toEqual([15, 15]);
    });
  });

  describe('stationIcon fuer Supercharger', () => {
    it('zeigt statt einer Freizahl einen Strich im neutralen Ton', () => {
      const icon = stationIcon(station({ supercharger: true, total: 15, free: 0 }), 'pin').options;
      expect(icon.className).toContain('charging-marker--unknown');
      expect(icon.html as string).toContain('–');
    });
  });

  describe('withChargePoints', () => {
    it('leitet frei/gesamt, Leistung und Preis aus den frischen Ladepunkten ab', () => {
      const merged = withChargePoints(station({ free: 0, total: 2, maxPowerKw: 150 }), [
        { chargePointId: 'P1', status: 'FREE', maxPowerKw: 300, minimumDuration: false, pricePerKwh: 0.79 },
        { chargePointId: 'P2', status: 'OCCUPIED', maxPowerKw: 150, minimumDuration: false, pricePerKwh: 0.59 }
      ]);
      expect(merged.free).toBe(1);
      expect(merged.total).toBe(2);
      expect(merged.maxPowerKw).toBe(300);
      expect(merged.pricePerKwh).toBe(0.59);
      expect(merged.chargePoints?.length).toBe(2);
    });

    it('uebernimmt bei Favoriten weder Ladepunkt-Preise noch einen EnBW-Mindestpreis', () => {
      const merged = withChargePoints(station({ favorite: true, pricePerKwh: 0.39 }), [
        { chargePointId: 'P1', status: 'FREE', maxPowerKw: 300, minimumDuration: false, pricePerKwh: 0.79 }
      ]);
      expect(merged.pricePerKwh).toBe(0.39);
      expect(merged.chargePoints?.[0].pricePerKwh).toBeUndefined();
    });

    it('behaelt Leistung und Preis der Liste, wenn die Ladepunkte keine nennen', () => {
      const merged = withChargePoints(station({ maxPowerKw: 150, pricePerKwh: 0.4 }), [
        { chargePointId: 'P1', status: 'FREE', minimumDuration: false }
      ]);
      expect(merged.maxPowerKw).toBe(150);
      expect(merged.pricePerKwh).toBe(0.4);
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

  describe('formatDistance', () => {
    it('zeigt Meter, Kilometer mit Komma und ab 10 km ganze Kilometer', () => {
      expect(formatDistance(450)).toBe('450 m');
      expect(formatDistance(2149)).toBe('2,1 km');
      expect(formatDistance(9949)).toBe('9,9 km');
      expect(formatDistance(10400)).toBe('10 km');
      expect(formatDistance(87300)).toBe('87 km');
      expect(formatDistance(123456)).toBe('123 km');
    });
  });
});
