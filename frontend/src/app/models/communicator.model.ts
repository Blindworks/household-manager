/** Vertrag der Sprach-App „Communicator“ (`/api/v1/communicator`). */

export type CommunicatorCardType = 'climate' | 'switch' | 'mode' | 'lock' | 'info';

export type CommunicatorSuggestionKind = 'thermostat-target' | 'switch' | 'mode' | 'ask';

/** Vorgeschlagener naechster Schritt als Knopf unter einer Karte. */
export interface CommunicatorSuggestion {
  label: string;
  kind: CommunicatorSuggestionKind;
  thermostatId?: number;
  value?: number;
  entityId?: string;
  state?: 'on' | 'off';
  question?: string;
}

/**
 * Eine Karte der Antwort. Welche Felder gesetzt sind, haengt an `type`; fehlende Werte
 * fehlen im JSON (nie `null`). Alle Werte stammen aus dem Live-Zustand des Servers.
 */
export interface CommunicatorCard {
  type: CommunicatorCardType;
  title: string;
  /** Material-Symbols-Name. */
  icon?: string;
  text?: string;
  temperature?: number;
  humidity?: number;
  stale?: boolean;
  thermostatId?: number;
  targetTemp?: number;
  heating?: boolean;
  thermostatMode?: string;
  entityId?: string;
  state?: string;
  powerWatts?: number;
  smartlockId?: number;
  doorState?: string;
  suggestions?: CommunicatorSuggestion[];
}

export interface CommunicatorAnswer {
  /** Verstandener Text; fehlt, wenn die Spracherkennung nichts lieferte. */
  transcript?: string;
  cards: CommunicatorCard[];
}

export interface CommunicatorStatus {
  enabled: boolean;
  aiConfigured: boolean;
  speechConfigured: boolean;
}
