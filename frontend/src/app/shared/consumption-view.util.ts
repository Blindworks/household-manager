import {
  ConsumptionPoint,
  ConsumptionRange,
  ConsumptionResolution,
  MeterConsumptionSeries
} from '../models/meter-consumption-series.model';
import { MeterType } from '../models/meter-reading.model';
import { MeterTypeUtils } from '../utils/meter-type.utils';

/** Ein waehlbarer Zeitraum mit seiner Beschriftung. */
export interface RangeOption {
  readonly value: ConsumptionRange;
  readonly label: string;
}

/**
 * Die waehlbaren Zeitraeume je Aufloesung. Einzige Definition - Komponente und
 * Template lesen dieselbe Konstante.
 *
 * Die Aufloesung schaltet die Zeitraeume um, statt beide unabhaengig zu lassen:
 * "8 Wochen monatlich" ergaebe ein Diagramm mit zwei Balken.
 */
export const RANGE_OPTIONS: Record<ConsumptionResolution, readonly RangeOption[]> = {
  WEEK: [
    { value: 'WEEKS_8', label: '8 Wochen' },
    { value: 'WEEKS_26', label: '26 Wochen' },
    { value: 'WEEKS_52', label: '52 Wochen' }
  ],
  MONTH: [
    { value: 'MONTHS_6', label: '6 Monate' },
    { value: 'MONTHS_12', label: '12 Monate' },
    { value: 'MONTHS_24', label: '24 Monate' }
  ],
  // MONTHS_ALL steht bewusst nicht hier: er ist nur Datenquelle der Tabelle.
  YEAR: [{ value: 'YEARS_ALL', label: 'Alle Jahre' }]
};

const DEFAULT_RANGE: Record<ConsumptionResolution, ConsumptionRange> = {
  WEEK: 'WEEKS_26',
  MONTH: 'MONTHS_12',
  YEAR: 'YEARS_ALL'
};

/**
 * Standardzeitraum einer Aufloesung. Beim Umschalten gilt bewusst der Default der
 * NEUEN Aufloesung und nicht der gleiche Index - sonst landete man von "8 Wochen"
 * bei "6 Monaten" und die Ansicht spraenge auf einen ganz anderen Massstab.
 */
export function defaultRangeFor(resolution: ConsumptionResolution): ConsumptionRange {
  return DEFAULT_RANGE[resolution];
}

const PREVIOUS_LABEL: Record<ConsumptionResolution, string> = {
  WEEK: 'Vorwoche',
  MONTH: 'Vormonat',
  // Nie angezeigt (compareToPrevious vergleicht Jahre nicht), nur fuer die
  // Vollstaendigkeit des Records.
  YEAR: 'Vorjahr'
};

/** Liest den zu vergleichenden Wert eines Balkens; null = kein Wert. */
export type PointValueSelector = (point: ConsumptionPoint) => number | null;

const CONSUMPTION_OF: PointValueSelector = p => p.consumption;

/**
 * Veraenderung des letzten Werts gegenueber dem vorletzten, z. B. "+12 % ggü. Vorwoche".
 *
 * Gibt null zurueck, wenn es nichts zu vergleichen gibt: bei Jahren (das laufende ist
 * angebrochen), bei weniger als zwei Punkten,
 * wenn die Vorperiode 0 war, oder wenn der gewaehlte Wert (per Default der Verbrauch)
 * bei einem der beiden Balken fehlt. Ein "+0 %" oder "+∞ %" waere in all diesen Faellen
 * eine Aussage, die die Daten nicht hergeben.
 */
export function compareToPrevious(
  points: readonly ConsumptionPoint[],
  resolution: ConsumptionResolution,
  valueOf: PointValueSelector = CONSUMPTION_OF
): string | null {
  // Das juengste Jahr ist fast immer angebrochen: ein halbes Jahr gegen ein volles
  // ergaebe stets ein sattes Minus - eine systematische Falschaussage.
  if (resolution === 'YEAR' || points.length < 2) {
    return null;
  }
  const previousPoint = points[points.length - 2];
  const currentPoint = points[points.length - 1];
  const previous = valueOf(previousPoint);
  const current = valueOf(currentPoint);
  // == statt === faengt auch ein undefined ab: der Wert stammt letztlich aus einer
  // JSON-Antwort, und TypeScript-Typen gelten zur Laufzeit nicht (Muster
  // formatConsumption/formatCost unten) - ohne das rutschte ein undefined durch und
  // ergaebe sichtbar "+NaN %".
  if (previous == null || current == null || previous === 0) {
    return null;
  }
  const percent = Math.round(((current - previous) / previous) * 100);
  const sign = percent >= 0 ? '+' : '';
  const reference = isAdjacent(previousPoint, currentPoint, resolution)
    ? PREVIOUS_LABEL[resolution]
    : GAPPED_LABEL;
  return `${sign}${percent} % ggü. ${reference}`;
}

/** Bezeichnung, wenn zwischen den beiden Balken Perioden fehlen. */
const GAPPED_LABEL = 'letztem Wert';

/** Toleranz fuer eine Ablesewoche: 7 Tage plus Spielraum fuer einen verschobenen Ablesetag. */
const MAX_ADJACENT_WEEK_DAYS = 8;

/**
 * Sind die beiden Balken tatsaechlich benachbarte Perioden?
 *
 * Der Serien-Endpunkt laesst Perioden ohne Ablesung ganz weg, statt eine erfundene
 * Null zu liefern. Die letzten beiden Punkte einer Reihe koennen also weit
 * auseinanderliegen - dann waere "ggue. Vormonat" schlicht falsch.
 */
function isAdjacent(
  previous: ConsumptionPoint,
  current: ConsumptionPoint,
  resolution: ConsumptionResolution
): boolean {
  const from = new Date(previous.periodStart);
  const to = new Date(current.periodStart);
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
    // Ohne verwertbares Datum lieber die vage, aber richtige Aussage.
    return false;
  }
  if (resolution === 'WEEK') {
    const days = (to.getTime() - from.getTime()) / MILLIS_PER_DAY;
    return days <= MAX_ADJACENT_WEEK_DAYS;
  }
  const months =
    (to.getFullYear() - from.getFullYear()) * 12 + (to.getMonth() - from.getMonth());
  return months === 1;
}

const MILLIS_PER_DAY = 24 * 60 * 60 * 1000;

/** Verbrauchswert mit Einheit, eine Nachkommastelle, deutsches Komma. */
export function formatConsumption(value: number | null, unit: string): string {
  // Die undefined-Pruefung ist bewusst da, obwohl der Typ sie ausschliesst: der Wert
  // stammt aus einer JSON-Antwort, und TypeScript-Typen gelten zur Laufzeit nicht.
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '–';
  }
  return `${value.toLocaleString('de-DE', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1
  })} ${unit}`;
}

/**
 * Kostenwert mit Waehrungszeichen und deutscher Zahlformatierung ueber
 * Intl.NumberFormat. Die Nachkommastellenzahl richtet sich nach der Waehrung (EUR:
 * zwei, JPY: keine) - "zwei Nachkommastellen" waere hier keine Garantie.
 */
export function formatCost(value: number | null, currency: string): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '–';
  }
  return value.toLocaleString('de-DE', { style: 'currency', currency });
}

/**
 * Liegt die Periode, die an diesem Datum beginnt, im laufenden Monat bzw. Jahr?
 *
 * Zerlegt den ISO-String selbst, statt new Date("YYYY-MM-DD") zu nutzen: das wird als
 * UTC-Mitternacht gelesen und kann je nach Zeitzone in den Vortag rutschen.
 */
export function isRunningPeriod(
  periodStart: string,
  resolution: 'MONTH' | 'YEAR',
  today: Date = new Date()
): boolean {
  const [year, month] = periodStart.split('-').map(Number);
  if (year !== today.getFullYear()) {
    return false;
  }
  return resolution === 'YEAR' || month === today.getMonth() + 1;
}

/** Eine Zaehlerspalte der Summentabelle. */
export interface TotalsColumn {
  readonly meterType: MeterType;
  readonly name: string;
}

/** Eine Tabellenzelle: fertig formatierte Werte einer Periode und eines Zaehlers. */
export interface TotalsCell {
  readonly consumption: string;
  /** "–", wenn fuer die Periode kein vollstaendiger Preis hinterlegt ist. */
  readonly cost: string;
  /** Mindestens eine beitragende Ablesung war ein Schaetzwert. */
  readonly estimated: boolean;
}

/** Zellen in Spaltenreihenfolge; null = dieser Zaehler hat in der Periode keinen Wert. */
type TotalsCells = readonly (TotalsCell | null)[];

export interface TotalsMonthRow {
  /** periodStart des Monats, eindeutig als @for-track. */
  readonly key: string;
  /** Langer Monatsname, z. B. "März". */
  readonly label: string;
  readonly running: boolean;
  readonly cells: TotalsCells;
}

export interface TotalsYearRow {
  readonly year: number;
  readonly label: string;
  readonly running: boolean;
  readonly cells: TotalsCells;
  /** Monate mit Werten, chronologisch. Monate ohne Ablesung fehlen, keine erfundene 0. */
  readonly months: readonly TotalsMonthRow[];
}

export interface TotalsTable {
  readonly columns: readonly TotalsColumn[];
  /** Jahre, das neueste zuerst. */
  readonly rows: readonly TotalsYearRow[];
}

/**
 * Formt Jahres- und Monatsreihen zur Summentabelle.
 *
 * Die Jahressummen kommen fertig vom Server und werden hier NICHT aus den Monaten
 * addiert: so bleibt MeterConsumptionSeriesService die einzige Stelle der Kostenregel
 * "fehlt einer Woche der Preis, entfaellt die Summe der ganzen Periode".
 */
export function buildTotalsTable(
  years: readonly MeterConsumptionSeries[],
  months: readonly MeterConsumptionSeries[],
  today: Date = new Date()
): TotalsTable {
  const columns: TotalsColumn[] = years.map(s => ({
    meterType: s.meterType,
    name: MeterTypeUtils.getLabel(s.meterType)
  }));
  const yearCells = cellLookup(years);
  const monthCells = cellLookup(months);
  const cellsAt = (lookup: CellLookup, periodStart: string): TotalsCells =>
    columns.map(c => lookup.get(c.meterType)?.get(periodStart) ?? null);

  const yearStarts = distinctPeriodStarts(years).sort().reverse();
  const monthStarts = distinctPeriodStarts(months).sort();

  const rows = yearStarts.map(yearStart => {
    const year = Number(yearStart.slice(0, 4));
    return {
      year,
      label: String(year),
      running: isRunningPeriod(yearStart, 'YEAR', today),
      cells: cellsAt(yearCells, yearStart),
      months: monthStarts
        .filter(monthStart => monthStart.startsWith(`${year}-`))
        .map(monthStart => ({
          key: monthStart,
          label: monthName(monthStart),
          running: isRunningPeriod(monthStart, 'MONTH', today),
          cells: cellsAt(monthCells, monthStart)
        }))
    };
  });
  return { columns, rows };
}

type CellLookup = Map<MeterType, Map<string, TotalsCell>>;

function cellLookup(series: readonly MeterConsumptionSeries[]): CellLookup {
  return new Map(
    series.map(s => [
      s.meterType,
      new Map(
        s.points.map(p => [
          p.periodStart,
          {
            consumption: formatConsumption(p.consumption, s.unit),
            cost: formatCost(p.cost, s.currency),
            estimated: p.estimated
          }
        ])
      )
    ])
  );
}

function distinctPeriodStarts(series: readonly MeterConsumptionSeries[]): string[] {
  return [...new Set(series.flatMap(s => s.points.map(p => p.periodStart)))];
}

function monthName(periodStart: string): string {
  const [year, month] = periodStart.split('-').map(Number);
  return new Date(year, month - 1, 1).toLocaleDateString('de-DE', { month: 'long' });
}
