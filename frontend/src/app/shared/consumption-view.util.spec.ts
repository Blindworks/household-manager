import {
  RANGE_OPTIONS,
  compareToPrevious,
  defaultRangeFor,
  formatConsumption
} from './consumption-view.util';
import { ConsumptionPoint } from '../models/meter-consumption-series.model';

describe('consumption-view.util', () => {
  function point(consumption: number): ConsumptionPoint {
    return { periodStart: '2026-08-21', label: 'KW 34', consumption, estimated: false };
  }

  describe('RANGE_OPTIONS', () => {
    it('bietet je Aufloesung drei Zeitraeume', () => {
      expect(RANGE_OPTIONS.WEEK.map(o => o.value)).toEqual(['WEEKS_8', 'WEEKS_26', 'WEEKS_52']);
      expect(RANGE_OPTIONS.MONTH.map(o => o.value)).toEqual(['MONTHS_6', 'MONTHS_12', 'MONTHS_24']);
    });
  });

  describe('defaultRangeFor', () => {
    // Beim Wechsel der Aufloesung soll der Default der NEUEN Aufloesung gelten,
    // nicht der gleiche Index - sonst spraenge man von "8 Wochen" auf "6 Monate".
    it('nennt je Aufloesung ihren Standardzeitraum', () => {
      expect(defaultRangeFor('WEEK')).toBe('WEEKS_26');
      expect(defaultRangeFor('MONTH')).toBe('MONTHS_12');
    });
  });

  describe('compareToPrevious', () => {
    it('nennt die Veraenderung gegenueber der Vorwoche in Prozent', () => {
      expect(compareToPrevious([point(100), point(112)], 'WEEK'))
        .toBe('+12 % ggü. Vorwoche');
    });

    it('nennt einen Rueckgang mit Minuszeichen', () => {
      expect(compareToPrevious([point(100), point(88)], 'WEEK'))
        .toBe('-12 % ggü. Vorwoche');
    });

    it('spricht bei Monaten vom Vormonat', () => {
      expect(compareToPrevious([point(100), point(112)], 'MONTH'))
        .toBe('+12 % ggü. Vormonat');
    });

    // Bei einem einzigen Punkt gibt es nichts zu vergleichen - "+0 %" waere gelogen.
    it('gibt bei weniger als zwei Punkten nichts zurueck', () => {
      expect(compareToPrevious([point(100)], 'WEEK')).toBeNull();
      expect(compareToPrevious([], 'WEEK')).toBeNull();
    });

    // Division durch null: aus 0 auf irgendwas ist keine Prozentaussage.
    it('gibt nichts zurueck, wenn die Vorperiode null war', () => {
      expect(compareToPrevious([point(0), point(38)], 'WEEK')).toBeNull();
    });

    it('rundet auf ganze Prozent', () => {
      expect(compareToPrevious([point(100), point(103.4)], 'WEEK'))
        .toBe('+3 % ggü. Vorwoche');
    });
  });

  describe('formatConsumption', () => {
    it('zeigt eine Nachkommastelle mit deutschem Komma', () => {
      expect(formatConsumption(38.24, 'kWh')).toBe('38,2 kWh');
    });

    it('zeigt bei fehlendem Wert einen Platzhalter', () => {
      expect(formatConsumption(null, 'kWh')).toBe('–');
    });
  });
});
