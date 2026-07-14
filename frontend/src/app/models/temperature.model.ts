/** Auswählbarer Zeitraum der Temperaturgraphen. */
export type TimeRange = 'DAY' | 'WEEK' | 'MONTH';

/** Ein Zeit/Wert-Punkt einer Messreihe (time als ISO-String). */
export interface TimeValue {
  time: string;
  value: number;
}

/** Quelle eines Temperatursensors. */
export type TemperatureSource = 'ZIGBEE' | 'WEATHER' | 'ALEXA';

/** Zeitreihe eines Temperatursensors inkl. optionaler Luftfeuchtigkeit. */
export interface TemperatureSensorSeries {
  sensorId: string;
  name: string;
  source: TemperatureSource;
  temperature: TimeValue[];
  humidity: TimeValue[];
}
