/** Betriebsart eines Heizkoerperthermostats. OFF heisst: Zieltemperatur 5 Grad (das Geraet kennt kein echtes Aus). */
export type ThermostatMode = 'MANUAL' | 'FROST_PROTECTION' | 'OFF';

export interface Thermostat {
  id: number;
  name: string;
  online: boolean;
  currentTemp: number | null;
  targetTemp: number | null;
  mode: ThermostatMode;
  /** Ventil offen. */
  heating: boolean;
  batteryLow: boolean;
  /** Lokale Haushaltszeit, null wenn nie gemeldet. */
  lastUpdated: string | null;
}

export interface ThermostatHistoryPoint {
  time: string;
  currentTemp: number | null;
  targetTemp: number | null;
  heating: boolean;
}

export interface ThermostatHistoryResponse {
  points: ThermostatHistoryPoint[];
}

export type ThermostatRange = 'DAY' | 'WEEK';
