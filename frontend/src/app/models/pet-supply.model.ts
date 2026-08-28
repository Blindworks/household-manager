/**
 * Ein Vorrat fuer Toni (Futter, VomiSan-Tabletten) und sein Journal.
 *
 * `step` ist das Eingaberaster des Vorrats (Dosen 0,5 / Tabletten 1) und
 * steuert die Zahlenfelder; `perDay` ist der Tagesverbrauch und macht die
 * Reichweite nachvollziehbar. Beide kommen vom Server, damit ein dritter
 * Vorrat ohne Frontend-Aenderung auskommt.
 */
export interface PetSupply {
  key: string;
  name: string;
  unit: string;
  amountRemaining: number;
  targetAmount: number;
  step: number;
  perDay: number;
  percent: number;
  daysRemaining: number;
}

export type PetSupplyTransactionType = 'FEEDING' | 'PURCHASE' | 'CORRECTION';

export interface PetSupplyTransaction {
  occurredAt: string;
  type: PetSupplyTransactionType;
  amount: number;
  amountAfter: number;
  note: string | null;
}
