export interface ShellyStatus {
  deviceName: string;
  ip: string;
  output: boolean;
  power: number | null;
  voltage: number | null;
  current: number | null;
  totalEnergy: number | null;
  reachable: boolean;
}

export interface ShellyReading {
  id: number;
  deviceName: string;
  timestamp: string;
  power: number | null;
  voltage: number | null;
  current: number | null;
  totalEnergy: number | null;
}
