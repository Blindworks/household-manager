export interface WeatherConditions {
  time: string;
  temperature: number | null;
  precipitation: number | null;
  windSpeed: number | null;
  windDirection: number | null;
  humidity: number | null;
  pressure: number | null;
  icon: number | null;
}

export interface WeatherForecastHour {
  time: string;
  temperature: number | null;
  precipitation: number | null;
  icon: number | null;
}

export interface WeatherWarning {
  warnId: number | null;
  event: string | null;
  level: number | null;
  headline: string | null;
  description: string | null;
  instruction: string | null;
  start: string | null;
  end: string | null;
}

export interface WeatherOverview {
  stationId: string;
  current: WeatherConditions | null;
  hourlyForecast: WeatherForecastHour[];
  warnings: WeatherWarning[];
  nextRain: string | null;
}

export interface WeatherHistoryReading {
  id: number;
  readingTime: Date;
  temperature: number | null;
  precipitation: number | null;
  windSpeed: number | null;
  windDirection: number | null;
  humidity: number | null;
  pressure: number | null;
  icon: number | null;
}
