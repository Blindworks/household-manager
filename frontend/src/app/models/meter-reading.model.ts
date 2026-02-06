/**
 * Enum für die verschiedenen Zählertypen
 */
export enum MeterType {
  ELECTRICITY = 'ELECTRICITY',
  GAS = 'GAS',
  WATER = 'WATER'
}

/**
 * Interface für eine Zählerablesung
 */
export interface MeterReading {
  /** Eindeutige ID der Ablesung */
  id?: number;

  /** Typ des Zählers (Strom, Gas, Wasser) */
  meterType: MeterType;

  /** Abgelesener Zählerstand */
  readingValue: number;

  /** Kalenderwoche der Ablesung */
  readingWeek?: number;

  /** Datum der Ablesung */
  readingDate: Date;

  /** Optionale Notizen zur Ablesung */
  notes?: string;

  /** Berechneter Verbrauch seit letzter Ablesung */
  consumption?: number;

  /** Anzahl Tage seit letzter Ablesung */
  daysSinceLastReading?: number;

  /** Zeitpunkt der Erstellung */
  createdAt?: Date;

  /** Zeitpunkt der letzten Aktualisierung */
  updatedAt?: Date;
}

/**
 * Request-Interface für das Erstellen einer neuen Ablesung
 */
export interface MeterReadingRequest {
  /** Typ des Zählers */
  meterType: MeterType;

  /** Abgelesener Zählerstand */
  readingValue: number;

  /** Kalenderwoche der Ablesung (optional) */
  readingWeek?: number;

  /** Datum der Ablesung im ISO-Format (YYYY-MM-DD) */
  readingDate: string;

  /** Optionale Notizen zur Ablesung */
  notes?: string;
}

/**
 * Response-Interface für Verbrauchsstatistiken
 */
export interface ConsumptionResponse {
  /** Typ des Zählers */
  meterType: MeterType;

  /** Aktueller Zählerstand */
  currentReading: number;

  /** Vorheriger Zählerstand */
  previousReading: number;

  /** Berechneter Verbrauch */
  consumption: number;

  /** Datum der aktuellen Ablesung */
  currentReadingDate: Date;

  /** Datum der vorherigen Ablesung */
  previousReadingDate: Date;

  /** Tage zwischen den Ablesungen */
  daysBetweenReadings: number;

  /** Durchschnittlicher täglicher Verbrauch */
  averageDailyConsumption: number;
}
