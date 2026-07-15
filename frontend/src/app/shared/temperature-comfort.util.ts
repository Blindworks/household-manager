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
  name: string;
  /** Temperatur, z. B. "21,4°". */
  valueLabel: string;
  /** Komfort-Wort oder "veraltet". */
  statusLabel: string;
  tone: ClimateTone;
  stale: boolean;
}

/** Aufbereitetes View-Model der Klima-Kachel. */
export interface ClimateView {
  /** Außentemperatur als Referenz, z. B. "12°" oder "--". */
  outsideLabel: string;
  rows: TemperatureRow[];
}

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

/** Trennt Außen von Innensensoren und baut die Kachelzeilen. */
export function buildClimateView(
  readings: CurrentTemperatureReading[],
  nowMs: number
): ClimateView {
  const outside = readings.find(r => r.source === 'WEATHER');
  const outsideLabel = outside ? formatCelsius(Math.round(outside.temperature), 0) : '--';

  const rows = readings
    .filter(r => r.source !== 'WEATHER')
    .map(r => toRow(r, nowMs));

  return { outsideLabel, rows };
}

function toRow(reading: CurrentTemperatureReading, nowMs: number): TemperatureRow {
  const stale = nowMs - new Date(reading.measuredAt).getTime() > STALE_THRESHOLD_MS;
  const comfort = comfortRating(reading.temperature);
  return {
    name: reading.name,
    valueLabel: formatCelsius(reading.temperature, 1),
    statusLabel: stale ? 'veraltet' : comfort.label,
    tone: stale ? 'stale' : comfort.tone,
    stale
  };
}

function formatCelsius(value: number, fractionDigits: number): string {
  return `${value.toLocaleString('de-DE', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits
  })}°`;
}
