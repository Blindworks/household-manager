/**
 * Bewertung der Toni-Vorraete. Einzige Definition im Frontend - die Seite
 * /pet-food, die Dashboard-Kacheln und die Tablet-Ansicht fragen dieselbe
 * Funktion.
 *
 * Die Schwelle ist bewusst eine REICHWEITE, keine Stueckzahl: Dosen und
 * Tabletten haben verschiedene Tagesverbraeuche, eine gemeinsame Stueckzahl
 * waere fuer einen der beiden Vorraete falsch. Fuer den Futtervorrat ist das
 * exakt die bisherige Schwelle - 7 Dosen sind bei einer Dose pro Tag genau
 * 7 Tage.
 *
 * Der Telegram-Warnflow traegt dieselbe Zahl ein zweites Mal (Bedingung auf
 * das Attribut daysRemaining). Er lebt in der Flow-Engine und ist von hier aus
 * nicht erreichbar - wer PET_SUPPLY_CRITICAL_DAYS aendert, muss ihn von Hand
 * nachziehen.
 */
export type PetSupplyTone = 'ok' | 'warn' | 'critical';

/** Muss der Flow-Bedingung "daysRemaining < 7" entsprechen. */
export const PET_SUPPLY_CRITICAL_DAYS = 7;

/** Darunter wird gewarnt, auch wenn die Reichweite noch ueber der Schwelle liegt. */
export const PET_SUPPLY_WARN_PERCENT = 25;

export function petSupplyTone(supply: { daysRemaining: number; percent: number }): PetSupplyTone {
  if (supply.daysRemaining < PET_SUPPLY_CRITICAL_DAYS) {
    return 'critical';
  }
  return supply.percent < PET_SUPPLY_WARN_PERCENT ? 'warn' : 'ok';
}

/** Breite des Fuellstandsbalkens in Prozent, geklemmt auf 0..100. */
export function petSupplyBarWidth(percent: number): number {
  return Math.max(0, Math.min(100, percent));
}

/**
 * Schlechtester Ton einer Vorratsliste - fuer Oberflaechen, die mehrere
 * Vorraete in EINER Kachel zeigen (Tablet-Ansicht). Ohne das verschwaende ein
 * leerer Tablettenvorrat hinter einem vollen Futterlager.
 */
export function worstPetSupplyTone(supplies: readonly { daysRemaining: number; percent: number }[]): PetSupplyTone {
  if (supplies.some(supply => petSupplyTone(supply) === 'critical')) {
    return 'critical';
  }
  return supplies.some(supply => petSupplyTone(supply) === 'warn') ? 'warn' : 'ok';
}
