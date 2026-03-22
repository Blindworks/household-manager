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
