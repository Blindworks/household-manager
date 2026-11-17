/** Ein 5-Minuten-Bild des Regenradars (`forecast` = Vorhersage statt Messung). */
export interface RainRadarFrame {
  /** Lokale Haushaltszeit `yyyy-MM-ddTHH:mm:ss`. */
  time: string;
  mmPerHour: number;
  forecast: boolean;
}

/** Antwort von `GET /v1/rain-radar/forecast`; ohne Abruf seit dem Start leer. */
export interface RainRadarForecast {
  measuredAt: string | null;
  mmPerHour: number | null;
  raining: boolean;
  expectedRainAt: string | null;
  peakNextHourMmPerHour: number | null;
  thresholdMmPerHour: number;
  source: string;
  frames: RainRadarFrame[];
}
