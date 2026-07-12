export interface AlexaAirQualityReading {
  id: number;
  applianceId: string;
  deviceName: string;
  readingTime: Date;
  iaq: number | null;
  pm25: number | null;
  voc: number | null;
  co: number | null;
  temperature: number | null;
  humidity: number | null;
}

export type AlexaAirQualityMetricKey = 'iaq' | 'pm25' | 'voc' | 'co' | 'temperature' | 'humidity';

export interface AlexaAirQualityMetric {
  key: AlexaAirQualityMetricKey;
  label: string;
  unit: string;
}

export const ALEXA_AIR_QUALITY_METRICS: AlexaAirQualityMetric[] = [
  { key: 'iaq', label: 'Luftqualitaet (IAQ)', unit: '' },
  { key: 'pm25', label: 'Feinstaub PM2.5', unit: 'µg/m³' },
  { key: 'voc', label: 'VOC', unit: 'ppb' },
  { key: 'co', label: 'Kohlenmonoxid', unit: 'ppm' },
  { key: 'temperature', label: 'Temperatur', unit: '°C' },
  { key: 'humidity', label: 'Luftfeuchte', unit: '%' }
];

export type IaqLevel = 'good' | 'moderate' | 'bad' | 'unknown';

/** Amazons IAQ-Skala: 0-100, hoeher = besser. Ab 65 gut, ab 35 maessig, darunter schlecht. */
export function iaqLevel(iaq: number | null): IaqLevel {
  if (iaq === null || iaq === undefined || Number.isNaN(iaq)) {
    return 'unknown';
  }
  if (iaq >= 65) {
    return 'good';
  }
  if (iaq >= 35) {
    return 'moderate';
  }
  return 'bad';
}
