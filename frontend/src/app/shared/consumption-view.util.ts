import {
  ConsumptionPoint,
  ConsumptionRange,
  ConsumptionResolution
} from '../models/meter-consumption-series.model';

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
  ]
};

const DEFAULT_RANGE: Record<ConsumptionResolution, ConsumptionRange> = {
  WEEK: 'WEEKS_26',
  MONTH: 'MONTHS_12'
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
  MONTH: 'Vormonat'
};

/**
 * Veraenderung des letzten Werts gegenueber dem vorletzten, z. B. "+12 % ggü. Vorwoche".
 *
 * Gibt null zurueck, wenn es nichts zu vergleichen gibt: bei weniger als zwei Punkten
 * oder wenn die Vorperiode 0 war. Ein "+0 %" oder "+∞ %" waere in beiden Faellen eine
 * Aussage, die die Daten nicht hergeben.
 */
export function compareToPrevious(
  points: readonly ConsumptionPoint[],
  resolution: ConsumptionResolution
): string | null {
  if (points.length < 2) {
    return null;
  }
  const previousPoint = points[points.length - 2];
  const currentPoint = points[points.length - 1];
  if (previousPoint.consumption === 0) {
    return null;
  }
  const percent = Math.round(
    ((currentPoint.consumption - previousPoint.consumption) / previousPoint.consumption) * 100
  );
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
