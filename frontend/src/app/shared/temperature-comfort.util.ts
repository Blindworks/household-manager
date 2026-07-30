import { CurrentTemperatureReading } from '../models/temperature.model';

/** Komfort-Ton einer Innentemperatur (steuert Farbe + Label). */
export type ComfortTone = 'cool' | 'comfortable' | 'warm' | 'hot';

/** Ton einer Kachelzeile inkl. Sonderfall "veraltet". */
export type ClimateTone = ComfortTone | 'stale';

export interface ComfortRating {
  label: string;
  tone: ComfortTone;
}

/** Eine Innensensor-Zeile der Kachel. */
export interface TemperatureRow {
  /** Sensor-Id der zugrundeliegenden Messung (Schlüssel für den Detaildialog). */
  sensorId: string;
  name: string;
  /** Temperatur, z. B. "21,4°". */
  valueLabel: string;
  /** Komfort-Wort oder "veraltet". */
  statusLabel: string;
  tone: ClimateTone;
  stale: boolean;
}

/** Primärer Außenfühler (realer Sensor am Haus, z. B. Garten). */
export interface OutdoorReading {
  /** Sensor-Id der zugrundeliegenden Messung (Schlüssel für den Detaildialog). */
  sensorId: string;
  name: string;
  /** Temperatur, z. B. "11,4°". */
  valueLabel: string;
  stale: boolean;
}

/** Aufbereitetes View-Model der Klima-Kachel. */
export interface ClimateView {
  /** Reale Außenfühler, erster = primär. Leer, wenn keiner gemeldet hat. */
  outdoor: OutdoorReading[];
  /** DWD-/Wetter-Außentemperatur als sekundäre Referenz, z. B. "12°" oder "--". */
  weatherLabel: string;
  /** Innensensor-Zeilen. */
  rows: TemperatureRow[];
}

/**
 * Sensornamen, die als reale Außenfühler (nicht als Innenraum) behandelt werden.
 * Erweiterbar um weitere Außenfühler, ohne die Logik zu ändern.
 */
export const OUTDOOR_SENSOR_NAMES: readonly string[] = ['Temperatur Aqara Garten'];

/** Messung gilt als veraltet, wenn älter als diese Schwelle. */
const STALE_THRESHOLD_MS = 60 * 60 * 1000;

/** Bildet eine Innentemperatur auf ein Komfortband ab. */
export function comfortRating(celsius: number): ComfortRating {
  if (celsius < 19) {
    return { label: 'frisch', tone: 'cool' };
  }
  if (celsius < 23) {
    return { label: 'angenehm', tone: 'comfortable' };
  }
  if (celsius <= 25) {
    return { label: 'warm', tone: 'warm' };
  }
  return { label: 'heiß', tone: 'hot' };
}

/**
 * Teilt die Messwerte in primären Außenfühler, DWD-Referenz und Innensensor-Zeilen.
 * Außenfühler werden über {@link OUTDOOR_SENSOR_NAMES} erkannt (Name, case-insensitiv).
 */
export function buildClimateView(
  readings: CurrentTemperatureReading[],
  nowMs: number,
  outdoorNames: readonly string[] = OUTDOOR_SENSOR_NAMES
): ClimateView {
  const outdoorSet = new Set(outdoorNames.map(name => name.trim().toLowerCase()));
  const isOutdoorSensor = (reading: CurrentTemperatureReading): boolean =>
    reading.source !== 'WEATHER' && outdoorSet.has(reading.name.trim().toLowerCase());

  const weather = readings.find(reading => reading.source === 'WEATHER');
  const weatherLabel = weather ? formatCelsius(Math.round(weather.temperature), 0) : '--';

  const outdoor = readings
    .filter(isOutdoorSensor)
    .map(reading => toOutdoorReading(reading, nowMs));

  const rows = readings
    .filter(reading => reading.source !== 'WEATHER' && !isOutdoorSensor(reading))
    .map(reading => toRow(reading, nowMs));

  return { outdoor, weatherLabel, rows };
}

/**
 * Detailansicht eines einzelnen Sensors (Dialog hinter einer Kachelzeile).
 * `humidityLabel` ist null, wenn der Sensor keine Feuchte meldet.
 */
export interface SensorDetail {
  sensorId: string;
  name: string;
  /** Temperatur, z. B. "21,4 °C". */
  temperatureLabel: string;
  /** Luftfeuchte, z. B. "48 %", oder null. */
  humidityLabel: string | null;
  /** Komfort-Wort oder "veraltet". */
  statusLabel: string;
  tone: ClimateTone;
  /** ISO-Zeitstempel der Messung. */
  measuredAt: string;
  stale: boolean;
}

/**
 * Baut die Detailansicht zu einer Sensor-Id aus den aktuellen Messwerten.
 * Liefert null, wenn zu der Id keine Messung (mehr) vorliegt — der Dialog
 * darf dann keinen veralteten Wert weiterzeigen.
 */
export function buildSensorDetail(
  readings: CurrentTemperatureReading[],
  sensorId: string,
  nowMs: number
): SensorDetail | null {
  const reading = readings.find(candidate => candidate.sensorId === sensorId);
  if (!reading) {
    return null;
  }
  const stale = isStale(reading, nowMs);
  const comfort = comfortRating(reading.temperature);
  return {
    sensorId: reading.sensorId,
    name: reading.name,
    temperatureLabel: `${formatNumber(reading.temperature, 1)} °C`,
    humidityLabel:
      reading.humidity === undefined || reading.humidity === null
        ? null
        : `${formatNumber(reading.humidity, 0)} %`,
    statusLabel: stale ? 'veraltet' : comfort.label,
    tone: stale ? 'stale' : comfort.tone,
    measuredAt: reading.measuredAt,
    stale
  };
}

function toOutdoorReading(reading: CurrentTemperatureReading, nowMs: number): OutdoorReading {
  return {
    sensorId: reading.sensorId,
    name: reading.name,
    valueLabel: formatCelsius(reading.temperature, 1),
    stale: isStale(reading, nowMs)
  };
}

function toRow(reading: CurrentTemperatureReading, nowMs: number): TemperatureRow {
  const stale = isStale(reading, nowMs);
  const comfort = comfortRating(reading.temperature);
  return {
    sensorId: reading.sensorId,
    name: reading.name,
    valueLabel: formatCelsius(reading.temperature, 1),
    statusLabel: stale ? 'veraltet' : comfort.label,
    tone: stale ? 'stale' : comfort.tone,
    stale
  };
}

function isStale(reading: CurrentTemperatureReading, nowMs: number): boolean {
  return nowMs - new Date(reading.measuredAt).getTime() > STALE_THRESHOLD_MS;
}

function formatCelsius(value: number, fractionDigits: number): string {
  return `${formatNumber(value, fractionDigits)}°`;
}

function formatNumber(value: number, fractionDigits: number): string {
  return value.toLocaleString('de-DE', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits
  });
}
