import { ChargePoint, ChargingStation } from '../models/charging.model';

/**
 * Einzige Definition von Farbe, Dauerformat und Sortierung der Ladesaeulen - Tablet und
 * Website fragen dieselben Funktionen.
 */
export type StationTone = 'free' | 'busy' | 'unknown';

export function stationTone(station: Pick<ChargingStation, 'free' | 'total' | 'supercharger'>): StationTone {
  // Supercharger melden nie eine Belegung: "0 frei" waere Rot, aber nichts ist bekannt.
  if (station.supercharger || station.total <= 0) {
    return 'unknown';
  }
  return station.free > 0 ? 'free' : 'busy';
}

/** "15 Plätze · bis 250 kW · Belegung unbekannt" - alles, was ein Supercharger-Standort hergibt. */
export function superchargerMeta(station: Pick<ChargingStation, 'total' | 'maxPowerKw'>): string {
  const parts = [`${station.total} Plätze`];
  if (station.maxPowerKw !== undefined && station.maxPowerKw !== null) {
    parts.push(`bis ${Math.round(station.maxPowerKw)} kW`);
  }
  parts.push('Belegung unbekannt');
  return parts.join(' · ');
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

/** "0,34 €/kWh"; leer ohne Preis (kein erfundener Wert). */
export function formatPrice(eurPerKwh: number | undefined): string {
  if (eurPerKwh === undefined || eurPerKwh === null) {
    return '';
  }
  return `${eurPerKwh.toFixed(2).replace('.', ',')} €/kWh`;
}

/**
 * Liste neben der Karte: nur Stationen im sichtbaren Ausschnitt, Favoriten bleiben immer
 * stehen (sonst verschwaenden sie beim Verschieben aus dem Blick). Ohne bekannten Ausschnitt
 * (Karte noch nicht aufgebaut) bleibt die Liste vollstaendig.
 */
export function stationsInView(
  stations: readonly ChargingStation[],
  isVisible: ((lat: number, lon: number) => boolean) | null
): ChargingStation[] {
  if (!isVisible) {
    return [...stations];
  }
  return stations.filter(s => s.favorite || isVisible(s.lat, s.lon));
}

/** "0,39 €/kWh · Aldi-App"; ohne Preis leer (eine Notiz allein sagt nichts). */
export function formatDirectPrice(eurPerKwh: number | undefined, note: string | undefined): string {
  const price = formatPrice(eurPerKwh);
  return price && note ? `${price} · ${note}` : price;
}

/** Hoechstgrenzen des Direktpreises; verbindlich ist das Backend, hier nur Bedienhilfe. */
export const DIRECT_PRICE_MAX = 2;
export const DIRECT_PRICE_NOTE_MAX = 60;

/**
 * Liest "0,39" oder "0.39" als Zahl; {@code undefined} bei allem Ungueltigen (leer, <= 0, > 2,
 * mehr als drei Nachkommastellen, kein Zahlenformat).
 */
export function parseDirectPrice(input: string): number | undefined {
  const text = input.trim().replace(',', '.');
  if (!/^\d+(\.\d{1,3})?$/.test(text)) {
    return undefined;
  }
  const value = Number(text);
  return value > 0 && value <= DIRECT_PRICE_MAX ? value : undefined;
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

/**
 * Ergaenzt eine Station um frisch nachgeladene Ladepunkte und leitet die Kopfzahlen daraus ab:
 * frei/gesamt, Hoechstleistung und guenstigster Preis. Die Umkreisliste ist bis zu fuenf
 * Minuten alt, die Ladepunkte sind frisch - zwei Quellen nebeneinander widersprachen sich
 * sichtbar ("0/2 frei" ueber einem freien Ladepunkt). Dieselbe Regel wie im Backend fuer
 * Favoriten (`ChargingQueryService.favoriteRow`).
 */
export function withChargePoints(station: ChargingStation, points: ChargePoint[]): ChargingStation {
  const powers = points.map(p => p.maxPowerKw).filter((p): p is number => p !== undefined && p !== null);
  // Favoriten zeigen nie einen EnBW-Preis: der Stationspreis bleibt der Direktpreis, Ladepunkt-Preise entfallen.
  if (station.favorite) {
    return {
      ...station,
      chargePoints: points.map(({ pricePerKwh: _enbwPrice, ...point }) => point),
      total: points.length,
      free: points.filter(p => p.status === 'FREE').length,
      maxPowerKw: powers.length > 0 ? Math.max(...powers) : station.maxPowerKw
    };
  }
  const prices = points.map(p => p.pricePerKwh).filter((p): p is number => p !== undefined && p !== null);
  return {
    ...station,
    chargePoints: points,
    total: points.length,
    free: points.filter(p => p.status === 'FREE').length,
    maxPowerKw: powers.length > 0 ? Math.max(...powers) : station.maxPowerKw,
    pricePerKwh: prices.length > 0 ? Math.min(...prices) : station.pricePerKwh
  };
}
