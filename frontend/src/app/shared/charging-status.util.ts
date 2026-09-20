import { ChargingStation } from '../models/charging.model';

/**
 * Einzige Definition von Farbe, Dauerformat und Sortierung der Ladesaeulen - Tablet und
 * Website fragen dieselben Funktionen.
 */
export type StationTone = 'free' | 'busy' | 'unknown';

export function stationTone(station: Pick<ChargingStation, 'free' | 'total'>): StationTone {
  if (station.total <= 0) {
    return 'unknown';
  }
  return station.free > 0 ? 'free' : 'busy';
}

/**
 * "seit 38 min" / "seit mind. 38 min" / "seit 2 h 05 min". Ein Beginn in der Zukunft
 * (Uhrenversatz zwischen Server und Tablet) wird auf 0 geklemmt statt negativ angezeigt.
 */
export function formatOccupiedDuration(
  occupiedSince: string | undefined,
  minimum: boolean,
  now: Date = new Date()
): string {
  if (!occupiedSince) {
    return '';
  }
  const minutes = Math.max(0, Math.floor((now.getTime() - new Date(occupiedSince).getTime()) / 60_000));
  const prefix = minimum ? 'seit mind. ' : 'seit ';
  if (minutes < 60) {
    return `${prefix}${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = String(minutes % 60).padStart(2, '0');
  return `${prefix}${hours} h ${rest} min`;
}

export function formatPower(kw: number | undefined): string {
  return kw === undefined || kw === null ? '–' : `${Math.round(kw)} kW`;
}

export function formatDistance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters)} m` : `${(meters / 1000).toFixed(1).replace('.', ',')} km`;
}

/** Favoriten zuerst, innerhalb beider Gruppen nach Entfernung. */
export function sortStations(stations: readonly ChargingStation[]): ChargingStation[] {
  return [...stations].sort((a, b) => {
    if (a.favorite !== b.favorite) {
      return a.favorite ? -1 : 1;
    }
    return a.distanceMeters - b.distanceMeters;
  });
}
