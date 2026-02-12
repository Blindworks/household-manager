export interface TapoDiscoveryDevice {
  deviceId: string;
  deviceName?: string | null;
  deviceType?: string | null;
  model?: string | null;
  ip?: string | null;
  online?: boolean;
  fwVer?: string | null;
}

export interface TapoDeviceInfo {
  deviceId: string | null;
  type: string | null;
  model: string | null;
  hwVer: string | null;
  fwVer: string | null;
  mac: string | null;
  nickname: string | null;
  deviceOn: boolean | null;
  onTime: number | null;
  signalLevel: number | null;
  rssi: number | null;
  ip: string | null;
  ssid: string | null;
  overheated: boolean | null;
}

export interface TapoEnergyUsage {
  todayRuntime: number | null;
  monthRuntime: number | null;
  todayEnergy: number | null;
  monthEnergy: number | null;
  currentPower: number | null;
}
