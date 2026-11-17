import { ChargingForecastSummary } from '../models/charging.model';

/** Zeilen der Heatmap; der letzte Eintrag fasst Sonntage und Feiertage (Hessen) zusammen. */
export const HEATMAP_DAY_LABELS = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So/Ft'];

export type HeatmapLevel = 'none' | 'low' | 'mid' | 'high' | 'full';

/** "HH:mm" aus einem LocalDateTime-String; die Sekunden koennen fehlen. */
function clock(localDateTime: string): string {
  return localDateTime.substring(11, 16);
}

export function forecastLine(summary: ChargingForecastSummary | undefined): string | null {
  if (!summary) {
    return null;
  }
  if (summary.status === 'INSUFFICIENT_DATA') {
    return `noch zu wenig Daten (${summary.observedDays ?? 0} Tage)`;
  }
  const probability = summary.freeProbability30min !== undefined ? `${summary.freeProbability30min} % in 30 min` : null;
  const freeAt = summary.expectedFreeAt
    ? `frei ca. ${clock(summary.expectedFreeAt)}${summary.expectedFreeLatest ? `–${clock(summary.expectedFreeLatest)}` : ''}`
    : null;
  const parts = [freeAt, probability].filter((part): part is string => part !== null);
  return parts.length > 0 ? parts.join(' · ') : null;
}

export function freeWindow(at?: string, latest?: string): string | null {
  if (!at) {
    return null;
  }
  const base = `frei ca. ${clock(at)}`;
  return latest ? `${base}, meist bis ${clock(latest)}` : base;
}

export function occupiedSinceText(occupiedSince?: string): string | null {
  return occupiedSince ? `belegt seit ${clock(occupiedSince)}` : null;
}

/** Stufe der Heatmap-Zelle; value = Belegungsanteil (0 = nichts belegt, 1 = voll), null = keine Daten (nie mit 0 vermischt). */
export function heatmapLevel(value: number | null): HeatmapLevel {
  if (value === null) {
    return 'none';
  }
  if (value < 0.25) {
    return 'low';
  }
  if (value < 0.5) {
    return 'mid';
  }
  if (value < 0.75) {
    return 'high';
  }
  return 'full';
}

export const FORECAST_HORIZONS_MIN = [15, 30, 60];

export type ProbabilityTone = 'good' | 'warn' | 'bad';

/** Ampelfarbe einer Frei-Wahrscheinlichkeit (Prozent 0–100). */
export function probabilityTone(percent: number): ProbabilityTone {
  if (percent >= 75) {
    return 'good';
  }
  return percent >= 40 ? 'warn' : 'bad';
}

/** Klartext zur Frei-Wahrscheinlichkeit, damit die Zahl ohne Nachdenken lesbar ist. */
export function probabilityWord(percent: number): string {
  if (percent >= 90) {
    return 'fast sicher frei';
  }
  if (percent >= 75) {
    return 'wahrscheinlich frei';
  }
  if (percent >= 40) {
    return 'unsicher';
  }
  return percent >= 15 ? 'eher belegt' : 'kaum Chance';
}
