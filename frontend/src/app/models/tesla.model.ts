/** Fahrzeugstand aus GET /v1/tesla/vehicle (Tessie). Fehlende Werte fehlen im JSON. */
export interface TeslaVehicle {
  vin: string;
  displayName?: string;
  vehicleState?: string;
  batteryLevel?: number;
  rangeKm?: number;
  chargeLimit?: number;
  /** Tesla-Ladezustand: Charging, Complete, Disconnected, Stopped, Starting, NoPower */
  chargingState?: string;
  charging?: boolean;
  pluggedIn?: boolean;
  chargerPowerKw?: number;
  minutesToFullCharge?: number;
  scheduledChargingPending?: boolean;
  /** Haushaltszeit, ISO ohne Zone */
  scheduledChargeAt?: string;
  chargePortOpen?: boolean;
  locked?: boolean;
  climateOn?: boolean;
  defrostOn?: boolean;
  insideTemp?: number;
  outsideTemp?: number;
  atHome?: boolean;
  shiftState?: string;
  carType?: string;
  exteriorColor?: string;
  measuredAt?: string;
}

export interface TeslaStatus {
  configured: boolean;
  vehicle?: TeslaVehicle;
  polledAt?: string;
  error?: string;
}

export type TeslaCommand =
  | 'LOCK' | 'UNLOCK'
  | 'OPEN_CHARGE_PORT' | 'CLOSE_CHARGE_PORT'
  | 'START_CLIMATE' | 'STOP_CLIMATE'
  | 'START_DEFROST' | 'STOP_DEFROST';

export interface TeslaSettings {
  tokenConfigured: boolean;
  vin: string | null;
  pollIntervalMinutes: number;
  minPollIntervalMinutes: number;
  maxPollIntervalMinutes: number;
}

export interface TeslaSettingsUpdate {
  /** undefined/null = unverändert, '' = entfernen */
  apiToken?: string | null;
  vin: string | null;
  pollIntervalMinutes: number;
}

export interface TessieVehicle {
  vin: string;
  displayName: string | null;
}
