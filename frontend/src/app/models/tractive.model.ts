/** Anmeldezustand der Tractive-Integration. */
export interface TractiveAuthStatus {
  authenticated: boolean;
  email?: string;
  expiresAt?: string;
}

/** Ein Haustier mit letztem bekanntem Stand. */
export interface TractivePet {
  trackerId: string;
  name: string;
  latitude?: number;
  longitude?: number;
  accuracy?: number;
  sensorUsed?: string;
  lastSeen?: string;
  batteryPercent?: number;
  charging?: boolean;
  /** Zonenname, 'away' ausserhalb aller Zonen oder 'unknown' ohne Position. */
  zone: string;
  /** undefined, wenn keine Aussage moeglich ist – dann wird kein Badge gezeigt. */
  atHome?: boolean;
}

/** Ein aus der Positionshistorie abgeleiteter Spaziergang. */
export interface TractiveWalk {
  start: string;
  end: string;
  durationMinutes: number;
  distanceMeters: number;
}
