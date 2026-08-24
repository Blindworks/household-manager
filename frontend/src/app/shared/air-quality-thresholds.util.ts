import { AirQualityMetricKey } from '../models/air-quality-series.model';
import { iaqLevel } from '../models/alexa-air-quality.model';

/**
 * Bewertungsstufe eines Messwerts, 0 = gut bis 4 = sehr schlecht.
 *
 * Die Stufe ist die EINZIGE Farbquelle der Luftqualitaetsansicht: die Linie im
 * Diagramm und der Jetzt-Wert in der Kachel lesen dieselbe Funktion. Ohne das
 * liefen Graph und Zahl auseinander, sobald jemand eine Schwelle anfasst.
 */
export type AirQualityLevel = 0 | 1 | 2 | 3 | 4;

/** Zurueckhaltende Ampel: gruen, gelb, orange, rot, dunkelrot. */
export const AIR_QUALITY_LEVEL_COLORS: readonly string[] = [
  '#4ade80',
  '#facc15',
  '#fb923c',
  '#f87171',
  '#dc2626'
];

export const AIR_QUALITY_LEVEL_LABELS: readonly string[] = [
  'Gut',
  'Mäßig',
  'Erhöht',
  'Schlecht',
  'Sehr schlecht'
];

/**
 * Obere Grenzen der Stufen 0 bis 3 je Messgroesse; darueber gilt Stufe 4.
 * Die Grenze gehoert jeweils noch zur unteren Stufe.
 *
 * Herkunft der Werte:
 * - pm25/pm10: Baender des europaeischen Luftqualitaetsindex der EEA (Tagesmittel).
 * - co: WHO-Richtwerte fuer Innenraeume - 8-Stunden-Mittel rund 9 ppm, 1-Stunden-Mittel
 *   35 ppm; darueber der Bereich, in dem handelsuebliche CO-Melder ausloesen.
 * - voc: KEIN amtlicher Grenzwert. Das sind die in der Sensorik verbreiteten
 *   TVOC-Baender in ppb. Amazon dokumentiert weder Messverfahren noch Bezugsgas,
 *   die Einstufung ist deshalb ein Anhaltspunkt und keine Aussage ueber Gesundheit.
 */
const UPPER_BOUNDS: Record<Exclude<AirQualityMetricKey, 'iaq'>, readonly number[]> = {
  pm25: [10, 20, 25, 50],
  pm10: [20, 40, 50, 100],
  voc: [65, 220, 660, 2200],
  co: [4, 9, 35, 100]
};

/**
 * Stufe eines Messwerts.
 *
 * Der IAQ-Score laeuft andersherum (0-100, hoeher ist besser) und hat mit
 * {@link iaqLevel} bereits eine Schwellendefinition im Haus - die bleibt die
 * einzige Quelle dafuer, statt hier eine zweite Zahlenreihe zu erfinden.
 */
export function airQualityLevel(metric: AirQualityMetricKey, value: number): AirQualityLevel {
  if (metric === 'iaq') {
    switch (iaqLevel(value)) {
      case 'good':
        return 0;
      case 'moderate':
        return 2;
      case 'bad':
        return 3;
      default:
        return 0;
    }
  }

  const bounds = UPPER_BOUNDS[metric];
  for (let level = 0; level < bounds.length; level++) {
    if (value <= bounds[level]) {
      return level as AirQualityLevel;
    }
  }
  return 4;
}

export function airQualityLevelColor(metric: AirQualityMetricKey, value: number): string {
  return AIR_QUALITY_LEVEL_COLORS[airQualityLevel(metric, value)];
}

/** Ein Stueck einer ECharts-visualMap: faerbt den Linienabschnitt nach seinem Wert. */
export interface AirQualityColorPiece {
  gt?: number;
  lte?: number;
  lt?: number;
  gte?: number;
  color: string;
}

/**
 * Baut die Faerbung der Diagrammlinie aus denselben Schwellen. Die Linie traegt
 * damit keine eigene Farbe mehr - sie zeigt, in welchem Band der Wert liegt.
 */
export function airQualityColorPieces(metric: AirQualityMetricKey): AirQualityColorPiece[] {
  if (metric === 'iaq') {
    // Umgekehrte Skala: hohe Werte sind gut. Die Schwellen stammen aus iaqLevel.
    return [
      { lt: 35, color: AIR_QUALITY_LEVEL_COLORS[3] },
      { gte: 35, lt: 65, color: AIR_QUALITY_LEVEL_COLORS[2] },
      { gte: 65, color: AIR_QUALITY_LEVEL_COLORS[0] }
    ];
  }

  const bounds = UPPER_BOUNDS[metric];
  const pieces: AirQualityColorPiece[] = [{ lte: bounds[0], color: AIR_QUALITY_LEVEL_COLORS[0] }];
  for (let level = 1; level < bounds.length; level++) {
    pieces.push({
      gt: bounds[level - 1],
      lte: bounds[level],
      color: AIR_QUALITY_LEVEL_COLORS[level]
    });
  }
  pieces.push({ gt: bounds[bounds.length - 1], color: AIR_QUALITY_LEVEL_COLORS[4] });
  return pieces;
}
