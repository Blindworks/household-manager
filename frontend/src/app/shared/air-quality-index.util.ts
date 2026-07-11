/**
 * Mappt den UBA-Luftqualitätsindex (0–4) auf Label, Hintergrund- und Textfarbe.
 * Farben folgen der EEA-/UBA-Farbskala des Luftqualitätsindex.
 */
export interface AirQualityCategory {
  label: string;
  color: string;
  textColor: string;
}

const CATEGORIES: Record<number, AirQualityCategory> = {
  0: { label: 'Sehr gut', color: '#50f0e6', textColor: '#06373a' },
  1: { label: 'Gut', color: '#50ccaa', textColor: '#08312a' },
  2: { label: 'Mäßig', color: '#f0e641', textColor: '#3d3600' },
  3: { label: 'Schlecht', color: '#ff5050', textColor: '#ffffff' },
  4: { label: 'Sehr schlecht', color: '#960032', textColor: '#ffffff' }
};

const UNKNOWN: AirQualityCategory = { label: 'Keine Daten', color: '#94a3b8', textColor: '#ffffff' };

export function airQualityCategory(index: number | null | undefined): AirQualityCategory {
  if (index == null) {
    return UNKNOWN;
  }
  return CATEGORIES[index] ?? UNKNOWN;
}
