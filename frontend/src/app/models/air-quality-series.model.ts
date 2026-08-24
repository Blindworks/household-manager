import { TimeValue } from './temperature.model';

/** Quelle einer Luftqualitaets-Zeitreihe. */
export type AirQualitySource = 'AIRROHR' | 'ALEXA';

/** Schluessel einer einzelnen Messgroesse, wie ihn das Backend in der Map fuehrt. */
export type AirQualityMetricKey = 'pm25' | 'pm10' | 'iaq' | 'voc' | 'co';

/** Luftqualitaets-Zeitreihen genau eines Sensors. Fehlt eine Groesse, fehlt ihr Schluessel. */
export interface AirQualitySensorSeries {
  sensorId: string;
  name: string;
  source: AirQualitySource;
  metrics: Partial<Record<AirQualityMetricKey, TimeValue[]>>;
}

/**
 * Anzeige-Eigenschaften einer Messgroesse.
 *
 * Bewusst OHNE eigene Farbe: die Linie wird nach den Grenzwerten der Messgroesse
 * eingefaerbt (`shared/air-quality-thresholds.util.ts`), nicht nach ihrer Identitaet.
 * Eine Farbe pro Groesse waere bloss bunt und truege keine Aussage.
 */
export interface AirQualityMetric {
  key: AirQualityMetricKey;
  label: string;
  /** Einheit der Y-Achse; leer beim einheitenlosen IAQ-Score. */
  unit: string;
}

/**
 * Alle Messgroessen in Anzeigereihenfolge.
 *
 * Jede Groesse bekommt ihren eigenen Graphen - die vier Einheiten (µg/m³, Score
 * 0-100, ppb, ppm) liessen sich ohnehin nicht sinnvoll auf eine Achse legen.
 * Die Reihenfolge fuehrt den zusammenfassenden IAQ-Score voran, danach die
 * Einzelgroessen.
 */
export const AIR_QUALITY_METRICS: readonly AirQualityMetric[] = [
  { key: 'iaq', label: 'Luftqualität (IAQ)', unit: '' },
  { key: 'pm25', label: 'PM2.5', unit: 'µg/m³' },
  { key: 'pm10', label: 'PM10', unit: 'µg/m³' },
  { key: 'voc', label: 'VOC', unit: 'ppb' },
  { key: 'co', label: 'CO', unit: 'ppm' }
];
