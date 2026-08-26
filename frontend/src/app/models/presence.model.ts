/** Modelle der Anwesenheits-API (/api/v1/presence). Zeitstempel als ISO-LocalDateTime. */

export interface PresenceDeviceAdmin {
  id: number;
  userId: number;
  name: string;
  host: string;
  active: boolean;
}

export interface PresenceDeviceRequest {
  userId: number;
  name: string;
  host: string;
  active: boolean;
}

/**
 * Kein `host`: `GET /status` ist KIOSK-lesbar (Dashboard-Kachel auf dem
 * Wandtablet), die Geräte-Stammdaten sind bewusst ADMIN. Die Admin-Seite holt
 * die Adresse aus `GET /devices` und verknüpft über `id`.
 */
export interface PresenceDeviceStatus {
  id: number;
  name: string;
  active: boolean;
  lastSeenAt: string | null;
  lastCheckedAt: string | null;
}

export type PresencePersonState = 'on' | 'off' | 'unavailable' | 'unknown';

export interface PresencePersonStatus {
  userId: number;
  displayName: string;
  state: PresencePersonState;
  lastSeenAt: string | null;
  devices: PresenceDeviceStatus[];
}

export interface PresenceStatusResponse {
  householdState: string;
  persons: PresencePersonStatus[];
}

export interface PresenceSettings {
  awayGraceMinutes: number;
}
