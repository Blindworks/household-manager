export interface KasaDiscoveryDevice {
  ip: string;
  deviceId: string | null;
  model: string | null;
  alias: string | null;
  relayState: boolean;
}

export interface KasaStatus {
  relayState: boolean;
  alias: string | null;
  deviceId: string | null;
}
