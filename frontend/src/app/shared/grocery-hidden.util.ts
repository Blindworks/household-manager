import { GrocerySummaryItem } from '../models/grocery.model';

const STORAGE_KEY = 'grocery.hiddenDeals';
/** So lange bleibt ein Eintrag gemerkt; laenger laeuft kein Wochenangebot. */
const KEEP_MS = 60 * 24 * 60 * 60 * 1000;

/** Schluessel eines Angebots => Zeitpunkt des Ausblendens (Epoch-ms). */
export type HiddenGroceryDeals = Record<string, number>;

/**
 * Einzige Definition von „dasselbe Angebot“ fuers Ausblenden. Grundpreis und Enddatum
 * gehoeren dazu: ein neues Angebot desselben Artikels erscheint wieder.
 */
export function groceryItemKey(item: GrocerySummaryItem): string {
  const deal = item.deal;
  return [
    deal?.productId ?? `${item.ownerUserId ?? 'household'}:${item.productName}`,
    item.chain, deal?.fingerprint ?? '', item.basePrice, deal?.validTo ?? ''
  ].join('|');
}

/** Auf diesem Geraet ausgeblendete Angebote ohne verjaehrte Eintraege; wirft nie. */
export function loadHiddenGroceryDeals(now: number = Date.now()): HiddenGroceryDeals {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}');
    if (typeof parsed !== 'object' || parsed === null) {
      return {};
    }
    return Object.fromEntries(Object.entries(parsed)
      .filter(([, hiddenAt]) => typeof hiddenAt === 'number' && now - hiddenAt < KEEP_MS));
  } catch {
    return {};
  }
}

/** Merkt sich die ausgeblendeten Angebote auf diesem Geraet; wirft nie. */
export function saveHiddenGroceryDeals(hidden: HiddenGroceryDeals): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(hidden));
  } catch {
    /* ohne Speicher gilt das Ausblenden nur bis zum Neuladen */
  }
}
