import { TimeRange } from './temperature.model';

export type { TimeRange };

/** Ergebnis eines Speedtests. */
export interface SpeedtestSummary {
  testedAt: string;
  downloadMbps: number | null;
  uploadMbps: number | null;
  success: boolean;
  errorMessage: string | null;
}

/** Status eines überwachten Netzwerkgeräts. */
export interface NetworkDeviceStatus {
  id: number;
  name: string;
  host: string;
  reachable: boolean;
  lastSeenAt: string | null;
}

/** Aktueller Gesamtstatus der Netzwerküberwachung. */
export interface NetworkStatusResponse {
  online: boolean;
  latencyMs: number | null;
  gatewayReachable: boolean;
  lastCheckedAt: string | null;
  lastSpeedtest: SpeedtestSummary | null;
  devices: NetworkDeviceStatus[];
}

/** Ein Zeit/Wert-Punkt der Latenz-Historie. */
export interface NetworkLatencyPoint {
  time: string;
  value: number;
}

/** Ein Speedtest-Punkt der Historie. */
export interface NetworkSpeedtestPoint {
  time: string;
  downloadMbps: number | null;
  uploadMbps: number | null;
}

/** Historie von Latenz und Speedtests über einen Zeitraum. */
export interface NetworkHistoryResponse {
  latency: NetworkLatencyPoint[];
  speedtests: NetworkSpeedtestPoint[];
}

/** Pflegbares Netzwerkgerät (Admin-Sicht). */
export interface NetworkDeviceAdminResponse {
  id: number;
  name: string;
  host: string;
  tcpPort: number | null;
  sortOrder: number;
  active: boolean;
}

/** Request-Body zum Anlegen/Ändern eines Netzwerkgeräts. */
export interface NetworkDeviceRequest {
  name: string;
  host: string;
  tcpPort?: number | null;
  sortOrder?: number;
  active?: boolean;
}
