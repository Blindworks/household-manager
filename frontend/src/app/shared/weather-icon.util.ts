/**
 * Mappt DWD-Icon-Codes (icon1h) auf Emoji + deutsche Beschreibung.
 * Codes folgen der WarnWetter-App-Konvention. Unbekannte Codes -> Standard.
 */
export interface WeatherSymbol {
  emoji: string;
  label: string;
}

const ICONS: Record<number, WeatherSymbol> = {
  1: { emoji: '☀️', label: 'Klar' },
  2: { emoji: '🌤️', label: 'Leicht bewölkt' },
  3: { emoji: '⛅', label: 'Wolkig' },
  4: { emoji: '☁️', label: 'Bedeckt' },
  5: { emoji: '🌫️', label: 'Nebel' },
  6: { emoji: '🌫️', label: 'Gefrierender Nebel' },
  7: { emoji: '🌦️', label: 'Leichter Regen' },
  8: { emoji: '🌧️', label: 'Regen' },
  9: { emoji: '🌧️', label: 'Starker Regen' },
  10: { emoji: '🌧️', label: 'Gefrierender Regen' },
  11: { emoji: '🌨️', label: 'Schneeregen' },
  12: { emoji: '🌨️', label: 'Leichter Schnee' },
  13: { emoji: '❄️', label: 'Schnee' },
  14: { emoji: '🌦️', label: 'Leichter Schauer' },
  15: { emoji: '🌧️', label: 'Schauer' },
  16: { emoji: '⛈️', label: 'Gewitter' }
};

const DEFAULT_SYMBOL: WeatherSymbol = { emoji: '🌡️', label: 'Unbekannt' };

export function weatherSymbol(icon: number | null | undefined): WeatherSymbol {
  if (icon == null) {
    return DEFAULT_SYMBOL;
  }
  return ICONS[icon] ?? DEFAULT_SYMBOL;
}

/**
 * Schweregrad einer Warnung aus dem DWD-`level`.
 * Robust gegen verschiedene Skalen (1-4 oder Vielfache von 10).
 */
export type WarnSeverity = 'info' | 'moderate' | 'severe' | 'extreme';

export function warnSeverity(level: number | null | undefined): WarnSeverity {
  if (level == null) {
    return 'info';
  }
  const normalized = level >= 10 ? Math.floor(level / 10) : level;
  if (normalized >= 4) return 'extreme';
  if (normalized === 3) return 'severe';
  if (normalized === 2) return 'moderate';
  return 'info';
}
