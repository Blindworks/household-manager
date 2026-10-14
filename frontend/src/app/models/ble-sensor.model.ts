/** Ein vom Wandtablet gesehenes Bluetooth-Thermometer (Admin-Sicht, inkl. nicht uebernommener). */
export interface BleSensorAdminResponse {
  id: number;
  mac: string;
  name: string | null;
  active: boolean;
  tabletId: string | null;
  lastSeenAt: string | null;
  lastTemperature: number | null;
  lastHumidity: number | null;
  batteryPercent: number | null;
  lastRssi: number | null;
}

/** Voll-PUT: `active` immer mitsenden, ein fehlendes Feld gilt serverseitig als aktiv. */
export interface BleSensorUpdateRequest {
  name: string | null;
  active: boolean;
}
