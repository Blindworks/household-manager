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

/** Eine Linie innerhalb einer Gruppe. */
export interface AirQualityMetricLine {
  key: AirQualityMetricKey;
  label: string;
  color: string;
}

/**
 * Eine waehlbare Messgroessen-Gruppe der Wandansicht.
 *
 * Gewaehlt wird immer genau EINE Gruppe. Die vier Messgroessen haben vier
 * verschiedene Einheiten; frei kombinierbar wie bei den Temperaturen ergaebe das
 * bis zu vier Y-Achsen in einer Wandkachel - unlesbar. Innerhalb einer Gruppe
 * teilen sich alle Linien eine Einheit und damit eine Achse.
 */
export interface AirQualityMetricGroup {
  key: string;
  label: string;
  /** Einheit der gemeinsamen Y-Achse; leer beim einheitenlosen IAQ-Score. */
  unit: string;
  lines: AirQualityMetricLine[];
}

export const AIR_QUALITY_GROUPS: readonly AirQualityMetricGroup[] = [
  {
    key: 'dust',
    label: 'Feinstaub',
    unit: 'µg/m³',
    lines: [
      { key: 'pm25', label: 'PM2.5', color: '#f59e0b' },
      { key: 'pm10', label: 'PM10', color: '#a855f7' }
    ]
  },
  { key: 'iaq', label: 'IAQ', unit: '', lines: [{ key: 'iaq', label: 'Luftqualität', color: '#22c55e' }] },
  { key: 'voc', label: 'VOC', unit: 'ppb', lines: [{ key: 'voc', label: 'VOC', color: '#38bdf8' }] },
  { key: 'co', label: 'CO', unit: 'ppm', lines: [{ key: 'co', label: 'CO', color: '#fb7185' }] }
];
