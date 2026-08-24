import { MeterType } from './meter-reading.model';

/** Laenge einer Verbrauchsperiode. Spiegelt das Backend-Enum ConsumptionResolution. */
export type ConsumptionResolution = 'WEEK' | 'MONTH';

/** Waehlbarer Zeitraum. Spiegelt das Backend-Enum ConsumptionRange. */
export type ConsumptionRange =
  | 'WEEKS_8'
  | 'WEEKS_26'
  | 'WEEKS_52'
  | 'MONTHS_6'
  | 'MONTHS_12'
  | 'MONTHS_24';

/** Ein Balken der Verbrauchsansicht. */
export interface ConsumptionPoint {
  /** ISO-Datum (YYYY-MM-DD): Ablesedatum bei Wochen, Monatserster bei Monaten. */
  readonly periodStart: string;
  /** Beschriftung der X-Achse, z. B. "KW 33" oder "Juli 26". */
  readonly label: string;
  readonly consumption: number;
  /** True, sobald mindestens eine beitragende Ablesung ein Schaetzwert war. */
  readonly estimated: boolean;
}

/** Die Verbrauchsreihe genau eines Zaehlertyps - eine Kachel der Ansicht. */
export interface MeterConsumptionSeries {
  readonly meterType: MeterType;
  /** "kWh" bei Strom, "m³" bei Gas und Wasser. */
  readonly unit: string;
  /** Balken, aeltester zuerst. */
  readonly points: ConsumptionPoint[];
}
