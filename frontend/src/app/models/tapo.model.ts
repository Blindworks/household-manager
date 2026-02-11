export interface TapoDiscoveryDevice {
  ip: string;
  hostname: string | null;
  port: number;
  serviceName: string | null;
}
