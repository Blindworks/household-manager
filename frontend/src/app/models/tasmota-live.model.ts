export interface TasmotaLiveReading {
  readingTime: string;
  momentaneWirkleistung: number;
  posWirkenergieTariflos?: number | null;
}
