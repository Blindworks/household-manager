/** API-Vertrag von /api/v1/charging (Zeitstempel als LocalDateTime-Strings in Haushaltszeit). */
export type ChargePointStatus = 'FREE' | 'OCCUPIED' | 'OUT_OF_SERVICE' | 'UNKNOWN';

export interface ChargePoint {
  chargePointId: string;
  status: ChargePointStatus;
  maxPowerKw?: number;
  connector?: string;
  /** Beginn der Belegung; fehlt bei freien Ladepunkten. */
  occupiedSince?: string;
  /** true = Beginn unbekannt, occupiedSince ist nur eine Untergrenze ("seit mind."). */
  minimumDuration: boolean;
  /** EnBW-Tarif in Euro je kWh; nur bei Nicht-Favoriten (bei Favoriten gibt das Backend ihn nie aus). */
  pricePerKwh?: number;
}

export type ChargingForecastStatus = 'OK' | 'INSUFFICIENT_DATA';

/** Kurzvorhersage an der Station (nur Favoriten); Zeiten als LocalDateTime-Strings. */
export interface ChargingForecastSummary {
  status: ChargingForecastStatus;
  freeProbability30min?: number;
  expectedFreeAt?: string;
  expectedFreeLatest?: string;
  observedDays?: number;
}

export interface ChargingPointForecast {
  chargePointId: string;
  occupiedSince?: string;
  expectedFreeAt?: string;
  expectedFreeLatest?: string;
}

/** Antwort von GET /stations/{id}/forecast; heatmap[Wochentag Mo..So/Ft][Stunde] = Belegungsanteil (0 = nichts belegt, 1 = voll), null = keine Daten. */
export interface ChargingForecast {
  stationId: string;
  status: ChargingForecastStatus;
  observedDays: number;
  freeProbabilityPercent: Record<string, number>;
  points: ChargingPointForecast[];
  heatmap: (number | null)[][];
}

export interface ChargingStation {
  stationId: string;
  name: string;
  operator?: string;
  address?: string;
  lat: number;
  lon: number;
  distanceMeters: number;
  maxPowerKw?: number;
  /**
   * Bei Favoriten der von Hand gepflegte Direktpreis in Euro je kWh (kein EnBW-Preis, fehlt ohne Eintrag);
   * bei nachgeladenen Nicht-Favoriten der guenstigste Tarif der Ladepunkte.
   */
  pricePerKwh?: number;
  /** Notiz zum Direktpreis (z. B. "Aldi-App"); nur bei Favoriten mit Notiz. */
  priceNote?: string;
  total: number;
  free: number;
  favorite: boolean;
  /** Nur bei Favoriten gefuellt. */
  chargePoints?: ChargePoint[];
  /** Nur bei Favoriten gefuellt. */
  forecast?: ChargingForecastSummary;
  /** Nur bei Tesla-Superchargern (supercharge.info): Standort ohne Belegung, Id mit Praefix `sc-`. */
  supercharger?: boolean;
  /** Nur bei Superchargern gesetzt, wenn fremde Fahrzeuge dort nicht laden duerfen. */
  teslaOnly?: boolean;
}

export interface ChargingStationsResponse {
  configured: boolean;
  home?: { lat: number; lon: number };
  radiusKm?: number;
  minPowerKw?: number;
  lastPolledAt: string | null;
  stations: ChargingStation[];
}

export interface ChargingSettings {
  homeLatitude: number | null;
  homeLongitude: number | null;
  radiusKm: number;
  minPowerKw: number;
}
