import { Thermostat } from '../models/thermostat.model';

/** Einzige Definition der Sollwert-Grenzen und des Rasters der Ansicht (spiegelt die Backend-Validierung). */
export const THERMOSTAT_MIN_TARGET = 5;
export const THERMOSTAT_MAX_TARGET = 30;
export const THERMOSTAT_STEP = 0.5;

/** Darstellungs-Zustand einer Kachel. */
export type ThermostatTone = 'offline' | 'heating' | 'idle' | 'frost' | 'off';

/** Rundet auf das 0,5-Raster und klemmt auf 5 bis 30 Grad. */
export function clampTarget(value: number): number {
  const rounded = Math.round(value / THERMOSTAT_STEP) * THERMOSTAT_STEP;
  return Math.min(THERMOSTAT_MAX_TARGET, Math.max(THERMOSTAT_MIN_TARGET, rounded));
}

/** Ein Schritt nach oben (+1) oder unten (-1), an den Grenzen klemmend. */
export function stepTarget(current: number, direction: 1 | -1): number {
  return clampTarget(current + direction * THERMOSTAT_STEP);
}

/**
 * Zustand fuer Farbe und Chips. Offline schlaegt alles; Frostschutz und Aus
 * haben Vorrang vor `heating`, weil dort ein offenes Ventil keine
 * Komfortheizung bedeutet.
 */
export function thermostatTone(thermostat: Thermostat): ThermostatTone {
  if (!thermostat.online) {
    return 'offline';
  }
  switch (thermostat.mode) {
    case 'OFF':
      return 'off';
    case 'FROST_PROTECTION':
      return 'frost';
    case 'MANUAL':
      return thermostat.heating ? 'heating' : 'idle';
  }
}

/** Anteil von 5 bis 30 Grad fuer den Bogen, null wenn kein Wert vorliegt. */
export function arcFraction(target: number | null): number | null {
  if (target === null) {
    return null;
  }
  const fraction = (target - THERMOSTAT_MIN_TARGET) / (THERMOSTAT_MAX_TARGET - THERMOSTAT_MIN_TARGET);
  return Math.min(1, Math.max(0, fraction));
}

/** "20,5" bzw. "–" - nie eine geratene Zahl. */
export function formatTemp(value: number | null): string {
  if (value === null) {
    return '–';
  }
  return value.toFixed(1).replace('.', ',');
}

/** Spalten des Kachelrasters: bis vier Thermostate zwei, darueber drei. */
export function gridColumns(count: number): number {
  return count <= 4 ? 2 : 3;
}
