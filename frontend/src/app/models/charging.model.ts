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
  total: number;
  free: number;
  favorite: boolean;
  /** Nur bei Favoriten gefuellt. */
  chargePoints?: ChargePoint[];
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
