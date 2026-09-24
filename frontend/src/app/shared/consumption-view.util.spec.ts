import {
  RANGE_OPTIONS,
  buildTotalsTable,
  compareToPrevious,
  defaultRangeFor,
  formatConsumption,
  formatCost,
  isRunningPeriod
} from './consumption-view.util';
import { ConsumptionPoint, MeterConsumptionSeries } from '../models/meter-consumption-series.model';
import { MeterType } from '../models/meter-reading.model';

describe('consumption-view.util', () => {
  /**
   * Ein Balken. Das Datum zaehlt: compareToPrevious sagt nur dann "Vorwoche" bzw.
   * "Vormonat", wenn die beiden Balken wirklich benachbarte Perioden sind.
   */
  function point(consumption: number, periodStart = '2026-08-21', cost: number | null = null): ConsumptionPoint {
    return { periodStart, label: 'KW 34', consumption, estimated: false, cost };
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

  describe('compareToPrevious mit Wertselektor', () => {
    it('vergleicht auf Kostenbasis, wenn ein Selektor uebergeben wird', () => {
      const points = [point(10, WEEK_A, 4), point(20, WEEK_B, 5)];
      expect(compareToPrevious(points, 'WEEK', p => p.cost)).toBe('+25 % ggü. Vorwoche');
    });

    it('gibt nichts zurueck, wenn der gewaehlte Wert der Vorperiode fehlt', () => {
      const points = [point(10, WEEK_A, null), point(20, WEEK_B, 5)];
      expect(compareToPrevious(points, 'WEEK', p => p.cost)).toBeNull();
    });

    // Spiegelfall zum Test oben: faellt hier ein Teilausdruck weg (z. B. eine
    // versehentlich entfernte current-Pruefung), faellt kein Test aus, ohne dieses
    // Gegenstueck.
    it('gibt nichts zurueck, wenn der gewaehlte Wert des aktuellen Punkts fehlt', () => {
      const points = [point(10, WEEK_A, 4), point(20, WEEK_B, null)];
      expect(compareToPrevious(points, 'WEEK', p => p.cost)).toBeNull();
    });
  });

  describe('formatCost', () => {
    it('zeigt zwei Nachkommastellen mit Euro-Zeichen', () => {
      // toLocaleString mit style "currency" setzt vor dem Euro-Zeichen ein
      // GESCHUETZTES Leerzeichen (U+00A0), kein gewoehnliches - hier bewusst als
      // \u00a0-Escape geschrieben, damit es im Quelltext sichtbar bleibt.
      expect(formatCost(12.4, 'EUR')).toBe('12,40\u00a0€');
    });

    it('zeigt bei fehlendem Wert einen Platzhalter', () => {
      expect(formatCost(null, 'EUR')).toBe('–');
    });
  });

  describe('Jahres-Aufloesung', () => {
    it('bietet bei Jahr genau "Alle Jahre" als Default', () => {
      expect(RANGE_OPTIONS.YEAR.map(o => o.value)).toEqual(['YEARS_ALL']);
      expect(defaultRangeFor('YEAR')).toBe('YEARS_ALL');
    });

    it('vergleicht bei Jahren nie - ein angebrochenes Jahr gegen ein volles waere falsch', () => {
      expect(compareToPrevious([point(100, '2025-01-01'), point(40, '2026-01-01')], 'YEAR')).toBeNull();
    });
  });

  describe('isRunningPeriod', () => {
    const today = new Date(2026, 8, 24);

    it('erkennt das laufende Jahr', () => {
      expect(isRunningPeriod('2026-01-01', 'YEAR', today)).toBeTrue();
      expect(isRunningPeriod('2025-01-01', 'YEAR', today)).toBeFalse();
    });

    it('erkennt den laufenden Monat', () => {
      expect(isRunningPeriod('2026-09-01', 'MONTH', today)).toBeTrue();
      expect(isRunningPeriod('2025-09-01', 'MONTH', today)).toBeFalse();
      expect(isRunningPeriod('2026-08-01', 'MONTH', today)).toBeFalse();
    });
  });

  describe('buildTotalsTable', () => {
    const today = new Date(2026, 8, 24);

    function p(periodStart: string, consumption: number, cost: number | null, estimated = false): ConsumptionPoint {
      return { periodStart, label: periodStart, consumption, estimated, cost };
    }

    function series(meterType: MeterType, unit: string, points: ConsumptionPoint[]): MeterConsumptionSeries {
      return { meterType, unit, currency: 'EUR', points };
    }

    const years = [
      series(MeterType.ELECTRICITY, 'kWh', [p('2025-01-01', 3000, 900), p('2026-01-01', 1500, null, true)]),
      series(MeterType.WATER, 'm³', [p('2026-01-01', 40, 120)])
    ];
    const months = [
      series(MeterType.ELECTRICITY, 'kWh', [
        p('2025-11-01', 250, 75),
        p('2026-03-01', 300, 90),
        p('2026-09-01', 100, null, true)
      ]),
      series(MeterType.WATER, 'm³', [p('2026-09-01', 4, 12)])
    ];

    const table = buildTotalsTable(years, months, today);

    it('legt je Zaehlertyp der Jahresreihen eine Spalte an', () => {
      expect(table.columns.map(c => c.name)).toEqual(['Strom', 'Wasser']);
    });

    it('stellt das neueste Jahr nach oben', () => {
      expect(table.rows.map(r => r.year)).toEqual([2026, 2025]);
    });

    it('ordnet die Monate chronologisch unter ihr Jahr', () => {
      expect(table.rows[0].months.map(m => m.label)).toEqual(['März', 'September']);
      expect(table.rows[1].months.map(m => m.label)).toEqual(['November']);
    });

    it('formatiert Verbrauch und Kosten je Zelle', () => {
      const cell = table.rows[1].cells[0];
      expect(cell?.consumption).toBe('3.000,0 kWh');
      expect(cell?.cost).toContain('900,00');
      expect(cell?.estimated).toBeFalse();
    });

    it('zeigt fehlende Kosten als Platzhalter und markiert Schaetzwerte', () => {
      const cell = table.rows[0].cells[0];
      expect(cell?.cost).toBe('–');
      expect(cell?.estimated).toBeTrue();
    });

    it('laesst eine Zelle ohne Wert leer statt 0 zu erfinden', () => {
      expect(table.rows[1].cells[1]).toBeNull();
      expect(table.rows[0].months[0].cells[1]).toBeNull();
    });

    it('markiert laufendes Jahr und laufenden Monat', () => {
      expect(table.rows[0].running).toBeTrue();
      expect(table.rows[1].running).toBeFalse();
      expect(table.rows[0].months.map(m => m.running)).toEqual([false, true]);
    });

    it('liefert ohne Daten keine Zeilen', () => {
      expect(buildTotalsTable([], [], today)).toEqual({ columns: [], rows: [] });
    });
  });
});
