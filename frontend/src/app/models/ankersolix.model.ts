export interface AnkerSolixLive {
  pvPowerW: number;
  batteryPercent: number;
  batteryPowerW: number;
  gridPowerW: number;
  homePowerW: number;
  timestamp: string;
}

export interface AnkerSolixEnergyDay {
  date: string;
  pvEnergyKwh: number;
  batteryChargeKwh: number;
  batteryDischargeKwh: number;
}

export interface AnkerSolixDeviceParams {
  minLoadW: number;
  maxLoadW: number;
  currentOutputW: number;
}

export interface AnkerSolixAutoControlStatus {
  enabled: boolean;
  thresholdW: number;
  intervalMs: number;
  lastSetOutputW: number | null;
  lastGridPowerW: number | null;
  lastAdjustmentTime: string | null;
  lastSkipReason: string | null;
  forceDischargeActive: boolean | null;
  lastBatteryPercent: number | null;
}

export interface AnkerSolixAutoControlSettings {
  enabled: boolean;
  thresholdW: number;
  intervalMs: number;
  forceDischargeEnabled: boolean;
  forceDischargeMinBatteryPercent: number;
}
