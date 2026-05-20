export interface EnergyLive {
  timestamp: string;
  bkwAltW: number;
  bkwNeuW: number;
  pvTotalW: number;
  gridW: number;
  hausverbrauchW: number;
  gridImporting: boolean;
  shellyReachable: boolean;
  tasmotaReachable: boolean;
}
