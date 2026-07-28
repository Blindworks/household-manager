export type ZigbeeMeasurementType =
  | 'TEMPERATURE'
  | 'HUMIDITY'
  | 'PRESSURE'
  | 'CONTACT'
  | 'OCCUPANCY'
  | 'ILLUMINANCE'
  | 'WATER_LEAK';

export interface ZigbeeDevice {
  id: number;
  friendlyName: string;
  ieeeAddress?: string;
  deviceType?: string;
  model?: string;
  lastBatteryPercent?: number;
  lastLinkQuality?: number;
  lastSeen?: string;
}

export interface ZigbeeMeasurement {
  measurementType: ZigbeeMeasurementType;
  value: number;
  unit: string;
  measuredAt: string;
}

export interface ZigbeeLiveEvent {
  friendlyName: string;
  measurementType: ZigbeeMeasurementType;
  value: number;
  unit: string;
  batteryPercent?: number;
  linkQuality?: number;
  measuredAt: string;
}

/** Zustand der Zigbee-Anbindung (GET /api/v1/zigbee/health). */
export interface ZigbeeHealth {
  health: 'OK' | 'STILL' | 'BRIDGE_OFFLINE';
  healthy: boolean;
  lastMessageAt: string;
  silentMinutes: number;
  bridgeState: string | null;
  /** Optional: kann null sein, solange nie eine Bridge-Nachricht kam. */
  lastBridgeStateAt?: string | null;
  offlineDevices: string[];
}
