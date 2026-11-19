import { GroceryBaseUnit, GroceryDeal, GroceryProduct, GroceryReferenceKind } from '../models/grocery.model';

const UNIT_LABEL: Record<GroceryBaseUnit, string> = { LITER: 'l', KILOGRAM: 'kg', PIECE: 'Stück' };
const WEEKDAYS = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];

export const GROCERY_UNIT_OPTIONS: { value: GroceryBaseUnit; label: string }[] = [
  { value: 'LITER', label: 'Liter' },
  { value: 'KILOGRAM', label: 'Kilogramm' },
  { value: 'PIECE', label: 'Stück' }
];

export function formatEuro(value: number): string {
  return `${value.toFixed(2).replace('.', ',')} €`;
}

/** "1,43 €/l" - die Bezugseinheit steht immer dabei. */
export function formatBasePrice(value: number, unit: GroceryBaseUnit): string {
  return `${formatEuro(value)}/${UNIT_LABEL[unit]}`;
}

/** "bis Sa 21.11." bzw. "ab Do 19.11." (ab nur, solange es noch nicht gilt). */
export function formatValidity(validFrom: string | null, validTo: string | null, today: Date = new Date()): string {
  const from = parseDay(validFrom);
  const to = parseDay(validTo);
  if (from && from.getTime() > startOfDay(today)) {
    return `ab ${shortDay(from)}`;
  }
  return to ? `bis ${shortDay(to)}` : '';
}

export function formatSaving(percent: number | null): string {
  return percent === null ? '' : `−${percent} %`;
}

/** Vergleichstext einer Ersparnis, z. B. "üblich 1,85 €/l". */
export function formatReference(deal: Pick<GroceryDeal, 'reference' | 'referenceKind' | 'baseUnit'>): string {
  if (deal.reference === null || !deal.referenceKind || !deal.baseUnit) {
    return '';
  }
  const prefix: Record<GroceryReferenceKind, string> = {
    USUAL: 'üblich',
    OWN_NORMAL_PRICE: 'dein Normalpreis',
    ESTIMATED_NORMAL_PRICE: 'normal ca.',
    LOWEST_30_DAYS: '30-Tage-Tiefstpreis'
  };
  return `${prefix[deal.referenceKind]} ${formatBasePrice(deal.reference, deal.baseUnit)}`;
}

/** Maßstab eines Produkts: eigener Normalpreis vor Claudes Schätzung. */
export function formatNormalPrice(
  product: Pick<GroceryProduct, 'normalPricePerUnit' | 'estimatedPricePerUnit' | 'baseUnit'>
): string {
  if (product.normalPricePerUnit !== null) {
    return `Normalpreis ${formatBasePrice(product.normalPricePerUnit, product.baseUnit)} (eigener)`;
  }
  if (product.estimatedPricePerUnit !== null) {
    return `Normalpreis ca. ${formatBasePrice(product.estimatedPricePerUnit, product.baseUnit)} (geschätzt)`;
  }
  return 'Normalpreis wird beim nächsten Suchlauf geschätzt';
}

/** Komma-getrennte Wortliste -> Liste ohne Leereintraege. */
export function parseWordList(text: string): string[] {
  return text.split(',').map(word => word.trim()).filter(word => word.length > 0);
}

function parseDay(iso: string | null): Date | null {
  if (!iso) {
    return null;
  }
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

function shortDay(date: Date): string {
  const day = String(date.getDate()).padStart(2, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${WEEKDAYS[date.getDay()]} ${day}.${month}.`;
}
