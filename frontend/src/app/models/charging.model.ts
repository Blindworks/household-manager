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
  /** EnBW-Tarif in Euro je kWh; nur bei Favoriten und nur wenn die Quelle einen nennt. */
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

/** Antwort von GET /stations/{id}/forecast; heatmap[Wochentag Mo..So/Ft][Stunde] = Anteil frei oder null. */
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
  /** Guenstigster EnBW-Tarif der Ladepunkte in Euro je kWh; nur bei Favoriten. */
  pricePerKwh?: number;
  total: number;
  free: number;
  favorite: boolean;
  /** Nur bei Favoriten gefuellt. */
  chargePoints?: ChargePoint[];
  /** Nur bei Favoriten gefuellt. */
  forecast?: ChargingForecastSummary;
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
