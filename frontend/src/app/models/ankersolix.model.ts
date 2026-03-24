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
  batteryPowerCutoffActive: boolean | null;
}

export interface AnkerSolixAutoControlSettings {
  enabled: boolean;
  thresholdW: number;
  intervalMs: number;
  gridPowerOffsetW: number;
  forceDischargeEnabled: boolean;
  forceDischargeMinBatteryPercent: number;
  batteryPowerCutoffEnabled: boolean;
}

export interface SolixAutoControlReading {
  id: number;
  timestamp: string;
  gridPowerW: number;
  currentOutputW: number;
  targetOutputW: number;
  clampedOutputW: number;
  minLoadW: number;
  maxLoadW: number;
  applied: boolean;
}
