export interface TasmotaPollingStatus {
  enabled: boolean;
  intervalMs: number;
  url: string;
  lastPollTime: string | null;
  lastSuccessTime: string | null;
  lastError: string | null;
}

export interface TasmotaPollingUpdateRequest {
  enabled?: boolean;
  intervalMs?: number;
  url?: string;
}
