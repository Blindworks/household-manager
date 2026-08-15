/** Status und Journal des Toni-Futtervorrats. */
export interface PetFoodStatus {
  cansRemaining: number;
  targetCans: number;
  percent: number;
  daysRemaining: number;
}

export type PetFoodTransactionType = 'FEEDING' | 'PURCHASE' | 'CORRECTION';

export interface PetFoodTransaction {
  occurredAt: string;
  type: PetFoodTransactionType;
  amount: number;
  cansAfter: number;
  note: string | null;
}
