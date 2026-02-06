import { MeterType } from './meter-reading.model';

/**
 * Interface für einen Versorgerpreis
 */
export interface UtilityPrice {
  /** Eindeutige ID des Preises */
  id?: number;

  /** Typ des Zählers (Strom, Gas, Wasser) */
  meterType: MeterType;

  /** Preis pro Einheit in EUR */
  pricePerUnit: number;

  /** Gültig ab Datum */
  validFrom: Date;

  /** Gültig bis Datum (optional) */
  validTo?: Date;

  /** Zeitpunkt der Erstellung */
  createdAt?: Date;

  /** Zeitpunkt der letzten Aktualisierung */
  updatedAt?: Date;
}

/**
 * Request-Interface für das Erstellen eines neuen Preises
 */
export interface UtilityPriceRequest {
  /** Typ des Zählers */
  meterType: MeterType;

  /** Preis pro Einheit in EUR */
  pricePerUnit: number;

  /** Gültig ab Datum im ISO-Format (YYYY-MM-DD) */
  validFrom: string;

  /** Gültig bis Datum im ISO-Format (YYYY-MM-DD) - optional */
  validTo?: string;
}
