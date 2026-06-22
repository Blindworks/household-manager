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
