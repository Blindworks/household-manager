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
  const previous = points[points.length - 2].consumption;
  const current = points[points.length - 1].consumption;
  if (previous === 0) {
    return null;
  }
  const percent = Math.round(((current - previous) / previous) * 100);
  const sign = percent >= 0 ? '+' : '';
  return `${sign}${percent} % ggü. ${PREVIOUS_LABEL[resolution]}`;
}

/** Verbrauchswert mit Einheit, eine Nachkommastelle, deutsches Komma. */
export function formatConsumption(value: number | null, unit: string): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '–';
  }
  return `${value.toLocaleString('de-DE', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1
  })} ${unit}`;
}
