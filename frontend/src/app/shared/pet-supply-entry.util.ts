/**
 * Rechenregeln des Erfassungs-Dialogs der Vorrats-Kacheln (Dashboard):
 * Stepper, Schnellwahl-Chips und Korrektur-Vorschau. Reine Funktionen ohne
 * Angular - das Raster eines Vorrats (0,5 Dosen / 1 Tablette) wird an genau
 * einer Stelle gerechnet, und Gleitkomma-Reste wie 0,30000000000000004 landen
 * nie im Zahlenfeld.
 *
 * Die Chips leiten sich aus Ziel und Bestand ab, nicht aus Artikelwissen:
 * die Seite kennt die Vorraete nicht namentlich, ein dritter Vorrat braucht
 * hier keine Aenderung.
 */

export interface PurchasePreset {
  amount: number;
  /** true fuer den Chip, der bis zum Zielbestand auffuellt. */
  refill: boolean;
}

/** Nachkommastellen des Rasters (0.5 -> 1, 1 -> 0, 0.25 -> 2). */
function decimalsOf(step: number): number {
  const text = step.toString();
  const dot = text.indexOf('.');
  return dot === -1 ? 0 : text.length - dot - 1;
}

/** Rundet einen Betrag auf das Raster des Vorrats und schneidet Gleitkomma-Reste ab. */
export function snapToStep(amount: number, step: number): number {
  const snapped = Math.round(amount / step) * step;
  return Number(snapped.toFixed(decimalsOf(step)));
}

/**
 * Naechster Stepper-Wert: ein Rasterschritt in die gewaehlte Richtung, nie
 * unter `min`. Ein leeres Feld (null) startet auf `min`. Ein Wert ausserhalb
 * des Rasters wird erst aufs Raster gezogen, sonst bliebe er fuer immer daneben.
 */
export function stepAmount(current: number | null, step: number, direction: 1 | -1, min: number): number {
  if (current === null || Number.isNaN(current)) {
    return min;
  }
  const next = snapToStep(current, step) + direction * step;
  return Math.max(min, snapToStep(next, step));
}

/**
 * Schnellwahl fuer den Einkauf: ein Viertel und die Haelfte des Ziels sowie
 * "Auffuellen" (Ziel minus Bestand). Betraege <= 0 entfallen; entspricht ein
 * Anteil genau dem Auffuellen, bleibt nur der Auffuellen-Chip - er sagt mehr.
 */
export function purchasePresets(
  supply: { targetAmount: number; amountRemaining: number; step: number }
): PurchasePreset[] {
  const refill = snapToStep(supply.targetAmount - supply.amountRemaining, supply.step);
  const fractions = [supply.targetAmount / 4, supply.targetAmount / 2]
    .map(amount => snapToStep(amount, supply.step))
    .filter((amount, index, all) => amount > 0 && amount !== refill && all.indexOf(amount) === index)
    .map(amount => ({ amount, refill: false }));
  return refill > 0 ? [...fractions, { amount: refill, refill: true }] : fractions;
}

/**
 * Differenz einer Korrektur zum aktuellen Bestand (fuer die Vorschauzeile),
 * auf zwei Nachkommastellen gerundet, damit keine Gleitkomma-Reste erscheinen.
 */
export function correctionDelta(newAmount: number | null, current: number): number | null {
  if (newAmount === null || Number.isNaN(newAmount)) {
    return null;
  }
  return Math.round((newAmount - current) * 100) / 100;
}
