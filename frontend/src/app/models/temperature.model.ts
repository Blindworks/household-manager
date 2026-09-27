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

/** Aktueller (jüngster) Wert eines Temperatursensors. */
export interface CurrentTemperatureReading {
  sensorId: string;
  /**
   * Entity-ID der Temperatur-Entity, z. B. "sensor.zigbee_temperatur_aqara_garten_temperature".
   * Stabil gegenueber einem Umbenennen in der App (anders als `name`, der den Custom-Namen traegt).
   */
  entityId: string;
  name: string;
  source: TemperatureSource;
  temperature: number;
  humidity?: number;
  /** ISO-Zeitstempel der Messung. */
  measuredAt: string;
}
