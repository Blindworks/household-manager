/** Modelle der IKEA-Dirigera-Admin-API (/v1/dirigera). */
export interface DirigeraHub {
  host: string | null;
  paired: boolean;
  hubId: string | null;
  /** ISO-Instant */
  lastSuccessAt: string | null;
  lastError: string | null;
  /** steht der Ereignisstrom (WebSocket) fuer Tastendruecke und Kontakte gerade */
  eventStreamConnected: boolean;
}

export type DirigeraPairingState = 'IDLE' | 'PRESS_BUTTON' | 'PAIRED' | 'FAILED';

export interface DirigeraPairingStatus {
  state: DirigeraPairingState;
  /** ISO-Instant, nur bei PRESS_BUTTON */
  expiresAt: string | null;
  error: string | null;
}

/** ENVIRONMENT = Thermometer (TIMMERFLOTTE), CONTACT = Fenster-/Türkontakt. */
export type DirigeraSensorKind = 'ENVIRONMENT' | 'CONTACT';

export interface DirigeraSensor {
  id: number;
  kind: DirigeraSensorKind;
  name: string | null;
  room: string | null;
  model: string | null;
  temperature: number | null;
  humidity: number | null;
  /** nur bei Kontakten; null = Zustand unbekannt */
  open: boolean | null;
  batteryPercent: number | null;
  reachable: boolean;
  knownToHub: boolean;
  /** lokale Haushaltszeit, ISO ohne Zone */
  lastSeenAt: string | null;
}

/** Eine Taste eines Tasters (BILRESA): eine Zeile je Taste, nicht je Gerät. */
export interface DirigeraButton {
  deviceId: string;
  /** Ereignis-Entität, auf die Flows triggern */
  entityId: string;
  name: string | null;
  /** 'top' | 'bottom' laut Hub, sonst roh */
  position: string | null;
  room: string | null;
  model: string | null;
  batteryPercent: number | null;
  /** single | double | hold (roh, falls unbekannt); null = noch nie gedrückt */
  lastAction: string | null;
  /** lokale Haushaltszeit, ISO ohne Zone */
  lastPressedAt: string | null;
}
