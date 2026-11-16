import { GrocerySummary, GrocerySummaryItem } from '../models/grocery.model';
import { formatBasePrice } from './grocery-format.util';
import { HubInsight } from './hub-insight.model';

const SHOWN_ITEMS = 2;

/**
 * Hub-Karte fuer sichere Schnaeppchen. Website: eigene Eintraege und die des Haushalts;
 * Wandtablet: alle Personen zusammen (ohne Link, dort gibt es keinen Zurueck-Knopf).
 */
export function buildGroceryInsight(
  summary: GrocerySummary,
  viewerUserId: number | null,
  tabletMode: boolean
): HubInsight | null {
  const items = tabletMode
    ? summary.items
    : summary.items.filter(item => item.ownerUserId === null || item.ownerUserId === viewerUserId);
  if (items.length === 0) {
    return null;
  }
  return {
    icon: 'shopping_cart',
    tone: 'secondary',
    title: items.length === 1 ? '1 Schnäppchen' : `${items.length} Schnäppchen`,
    text: items.slice(0, SHOWN_ITEMS).map(describeItem).join(' · '),
    ...(tabletMode ? {} : { link: '/groceries' })
  };
}

function describeItem(item: GrocerySummaryItem): string {
  const app = item.appOnly ? ' mit App' : '';
  const warmingUp = item.warmingUp ? ', noch wenig Vergleichsdaten' : '';
  return `${item.productName} bei ${item.chainName} ${formatBasePrice(item.basePrice, item.baseUnit)}${app}`
    + ` (−${item.savingPercent} %${warmingUp})`;
}
