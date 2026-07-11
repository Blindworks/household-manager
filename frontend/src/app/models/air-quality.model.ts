export interface AirQualityComponent {
  code: string;
  symbol: string;
  name: string;
  value: number | null;
  unit: string;
  index: number;
}

export interface AirQualityOverview {
  stationId: string;
  dateTime: string;
  /** 0 = sehr gut … 4 = sehr schlecht, -1 = keine Daten. */
  overallIndex: number;
  incomplete: boolean;
  components: AirQualityComponent[];
}
