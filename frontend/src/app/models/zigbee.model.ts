export type ZigbeeMeasurementType =
  | 'TEMPERATURE'
  | 'HUMIDITY'
  | 'PRESSURE'
  | 'CONTACT'
  | 'OCCUPANCY'
  | 'ILLUMINANCE'
  | 'WATER_LEAK';

export type ZigbeeDeviceHealthStatus = 'OFFLINE' | 'INTERVIEW' | 'UNKNOWN' | 'SILENT' | 'ACTIVE';

export interface ZigbeeDeviceHealth {
  status: ZigbeeDeviceHealthStatus;
  basis: 'zigbee2mqtt' | 'registry' | 'last-message' | null;
  lastHeardAt: string | null;
  silentSeconds: number | null;
}

export interface ZigbeeDeviceEntity {
  entityId: string;
  displayName: string;
  domain: string;
  state: string;
  lastChanged: string;
  lastUpdated: string;
}

/** GET /api/v1/zigbee/devices — Grundlage ist zigbee2mqtt's Verzeichnis. */
export interface ZigbeeDevice {
  /** DB-Id; null, wenn das Geraet noch nie gesendet hat. */
  id: number | null;
  friendlyName: string;
  ieeeAddress: string | null;
  /** false = nur in unserer Tabelle, zigbee2mqtt kennt es nicht (mehr). */
  knownToBridge: boolean;
  type: string | null;
  powerSource: string | null;
  battery: boolean;
  interviewCompleted: boolean | null;
  supported: boolean | null;
  model: string | null;
  vendor: string | null;
  description: string | null;
  lastBatteryPercent: number | null;
  lastLinkQuality: number | null;
  lastSeen: string | null;
  health: ZigbeeDeviceHealth;
  entities: ZigbeeDeviceEntity[];
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

/** GET /api/v1/zigbee/bridge und SSE 'bridge-info'. */
export interface ZigbeeBridgeStatus {
  version: string | null;
  connected: boolean;
  registryLoaded: boolean;
  permitJoin: boolean;
  permitJoinEnd: string | null;
  /** null, solange nie eine bridge/info kam. */
  availabilityCheckEnabled: boolean | null;
}

export type ZigbeeBridgeEventType = 'device_joined' | 'device_interview' | 'device_announce' | 'device_leave';

/** GET /api/v1/zigbee/bridge/events und SSE 'bridge-event'. */
export interface ZigbeeBridgeEvent {
  type: ZigbeeBridgeEventType;
  friendlyName: string | null;
  ieeeAddress: string | null;
  status: 'started' | 'successful' | 'failed' | null;
  model: string | null;
  vendor: string | null;
  receivedAt: string;
}

export interface FlowReference {
  flowId: number;
  name: string;
  enabled: boolean;
}

export interface PurgeResult {
  friendlyName: string;
  measurements: number;
  entities: number;
}
