import {
  RANGE_OPTIONS,
  compareToPrevious,
  defaultRangeFor,
  formatConsumption
} from './consumption-view.util';
import { ConsumptionPoint } from '../models/meter-consumption-series.model';

describe('consumption-view.util', () => {
  /**
   * Ein Balken. Das Datum zaehlt: compareToPrevious sagt nur dann "Vorwoche" bzw.
   * "Vormonat", wenn die beiden Balken wirklich benachbarte Perioden sind.
   */
  function point(consumption: number, periodStart = '2026-08-21'): ConsumptionPoint {
    return { periodStart, label: 'KW 34', consumption, estimated: false };
  }

  /** Zwei aufeinanderfolgende Ablesewochen. */
  const WEEK_A = '2026-08-14';
  const WEEK_B = '2026-08-21';
  /** Zwei aufeinanderfolgende Kalendermonate. */
  const MONTH_A = '2026-07-01';
  const MONTH_B = '2026-08-01';

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
      expect(compareToPrevious([point(100, WEEK_A), point(112, WEEK_B)], 'WEEK'))
        .toBe('+12 % ggü. Vorwoche');
    });

    it('nennt einen Rueckgang mit Minuszeichen', () => {
      expect(compareToPrevious([point(100, WEEK_A), point(88, WEEK_B)], 'WEEK'))
        .toBe('-12 % ggü. Vorwoche');
    });

    it('spricht bei Monaten vom Vormonat', () => {
      expect(compareToPrevious([point(100, MONTH_A), point(112, MONTH_B)], 'MONTH'))
        .toBe('+12 % ggü. Vormonat');
    });

    // Bei einem einzigen Punkt gibt es nichts zu vergleichen - "+0 %" waere gelogen.
    it('gibt bei weniger als zwei Punkten nichts zurueck', () => {
      expect(compareToPrevious([point(100)], 'WEEK')).toBeNull();
      expect(compareToPrevious([], 'WEEK')).toBeNull();
    });

    // Division durch null: aus 0 auf irgendwas ist keine Prozentaussage.
    it('gibt nichts zurueck, wenn die Vorperiode null war', () => {
      expect(compareToPrevious([point(0, WEEK_A), point(38, WEEK_B)], 'WEEK')).toBeNull();
    });

    it('rundet auf ganze Prozent', () => {
      expect(compareToPrevious([point(100, WEEK_A), point(103.4, WEEK_B)], 'WEEK'))
        .toBe('+3 % ggü. Vorwoche');
    });

    /**
     * Der Serien-Endpunkt laesst Perioden ohne Ablesung ganz weg, statt eine
     * erfundene Null zu liefern. Liegen die letzten beiden Balken deshalb nicht
     * nebeneinander, waere "ggue. Vormonat" schlicht falsch.
     */
    it('sagt nicht "Vormonat", wenn Monate dazwischen fehlen', () => {
      expect(compareToPrevious([point(100, '2026-05-01'), point(112, MONTH_B)], 'MONTH'))
        .toBe('+12 % ggü. letztem Wert');
    });

    it('sagt nicht "Vorwoche", wenn Wochen dazwischen fehlen', () => {
      expect(compareToPrevious([point(100, '2026-07-03'), point(112, WEEK_B)], 'WEEK'))
        .toBe('+12 % ggü. letztem Wert');
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
