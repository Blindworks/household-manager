/**
 * Mappt DWD-Icon-Codes (icon1h) auf Emoji + deutsche Beschreibung.
 * Codes folgen der WarnWetter-App-Konvention. Unbekannte Codes -> Standard.
 */
export interface WeatherSymbol {
  emoji: string;
  /** Name eines Lucide-Icons aus der app-icon-Komponente. */
  icon: string;
  label: string;
}

const ICONS: Record<number, WeatherSymbol> = {
  1: { emoji: '☀️', icon: 'sun', label: 'Klar' },
  2: { emoji: '🌤️', icon: 'cloud-sun', label: 'Leicht bewölkt' },
  3: { emoji: '⛅', icon: 'cloud-sun', label: 'Wolkig' },
  4: { emoji: '☁️', icon: 'cloud', label: 'Bedeckt' },
  5: { emoji: '🌫️', icon: 'cloud-fog', label: 'Nebel' },
  6: { emoji: '🌫️', icon: 'cloud-fog', label: 'Gefrierender Nebel' },
  7: { emoji: '🌦️', icon: 'cloud-drizzle', label: 'Leichter Regen' },
  8: { emoji: '🌧️', icon: 'cloud-rain', label: 'Regen' },
  9: { emoji: '🌧️', icon: 'cloud-rain-wind', label: 'Starker Regen' },
  10: { emoji: '🌧️', icon: 'cloud-rain', label: 'Gefrierender Regen' },
  11: { emoji: '🌨️', icon: 'cloud-snow', label: 'Schneeregen' },
  12: { emoji: '🌨️', icon: 'cloud-snow', label: 'Leichter Schnee' },
  13: { emoji: '❄️', icon: 'cloud-snow', label: 'Schnee' },
  14: { emoji: '🌦️', icon: 'cloud-drizzle', label: 'Leichter Schauer' },
  15: { emoji: '🌧️', icon: 'cloud-rain', label: 'Schauer' },
  16: { emoji: '⛈️', icon: 'cloud-lightning', label: 'Gewitter' }
};

const DEFAULT_SYMBOL: WeatherSymbol = { emoji: '🌡️', icon: 'thermometer', label: 'Unbekannt' };

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
