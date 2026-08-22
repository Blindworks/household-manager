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

/** Anzeige-Eigenschaften einer Messgroesse. */
export interface AirQualityMetric {
  key: AirQualityMetricKey;
  label: string;
  /** Einheit der Y-Achse; leer beim einheitenlosen IAQ-Score. */
  unit: string;
  color: string;
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
  { key: 'iaq', label: 'Luftqualität (IAQ)', unit: '', color: '#22c55e' },
  { key: 'pm25', label: 'PM2.5', unit: 'µg/m³', color: '#f59e0b' },
  { key: 'pm10', label: 'PM10', unit: 'µg/m³', color: '#a855f7' },
  { key: 'voc', label: 'VOC', unit: 'ppb', color: '#38bdf8' },
  { key: 'co', label: 'CO', unit: 'ppm', color: '#fb7185' }
];
