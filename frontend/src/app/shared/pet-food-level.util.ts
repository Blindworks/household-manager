/**
 * Bewertung des Toni-Futtervorrats. Einzige Definition im Frontend - die Seite
 * /pet-food, die Dashboard-Kachel und die Tablet-Ansicht fragen dieselbe Funktion.
 *
 * Der Telegram-Warnflow auf sensor.pet_food_toni_cans traegt dieselbe Schwelle ein
 * zweites Mal. Er lebt in der Flow-Engine und ist von hier aus nicht erreichbar -
 * wer PET_FOOD_CRITICAL_CANS aendert, muss den Flow von Hand nachziehen.
 */
export type PetFoodTone = 'ok' | 'warn' | 'critical';

/** Muss der Flow-Bedingung "sensor.pet_food_toni_cans < 7" entsprechen. */
export const PET_FOOD_CRITICAL_CANS = 7;

/** Darunter wird gewarnt, auch wenn die Dosenzahl noch ueber der Schwelle liegt. */
export const PET_FOOD_WARN_PERCENT = 25;

export function petFoodTone(status: { cansRemaining: number; percent: number }): PetFoodTone {
  if (status.cansRemaining < PET_FOOD_CRITICAL_CANS) {
    return 'critical';
  }
  return status.percent < PET_FOOD_WARN_PERCENT ? 'warn' : 'ok';
}

/** Breite des Fuellstandsbalkens in Prozent, geklemmt auf 0..100. */
export function petFoodBarWidth(percent: number): number {
  return Math.max(0, Math.min(100, percent));
}
