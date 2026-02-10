export interface TasmotaPollingStatus {
  url: string;
  schedule: string;
  lastPollTime: string | null;
  lastError: string | null;
}
