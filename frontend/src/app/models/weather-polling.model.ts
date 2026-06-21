export interface WeatherPollingStatus {
  stationId: string;
  schedule: string;
  lastPollTime: string | null;
  lastError: string | null;
}
