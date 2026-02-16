export interface SmartDevice {
  id: number;
  deviceType: 'KASA' | 'TAPO' | 'MEROSS';
  externalDeviceId: string;
  deviceName: string;
  model: string | null;
  ipAddress: string | null;
  isOnline: boolean;
  isPoweredOn: boolean;
  capabilities: string[];
  metadata: Record<string, any> | null;
  createdAt: string;
  updatedAt: string;
}

export interface SmartDeviceScanRequest {
  deviceType: 'KASA' | 'TAPO' | 'MEROSS';
}

export interface SmartDeviceUpdateRequest {
  deviceName: string;
  metadata: Record<string, any>;
}
