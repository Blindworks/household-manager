/**
 * Die Definition von „zu Hause" fuer den Hundetracker.
 * Ohne Koordinaten (beide null) existiert die Zu-Hause-Entitaet nicht.
 */
export interface TractiveHomeSettings {
  homeLatitude: number | null;
  homeLongitude: number | null;
  homeRadiusMeters: number;
  homeArrivalRadiusMeters: number;
  poweredOffAfterMinutes: number;
  poweredOffMinBatteryPercent: number;
  homeZoneName: string;
}
