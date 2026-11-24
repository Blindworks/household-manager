import { GrocerySummaryItem } from '../models/grocery.model';
import { groceryItemKey, loadHiddenGroceryDeals, saveHiddenGroceryDeals } from './grocery-hidden.util';

const DAY_MS = 24 * 60 * 60 * 1000;

function item(over: Partial<GrocerySummaryItem> = {}): GrocerySummaryItem {
  return {
    productName: 'Cola Zero', ownerUserId: null, ownerName: null, chain: 'PENNY', chainName: 'Penny',
    basePrice: 1.43, baseUnit: 'LITER', savingPercent: 23, appOnly: false, warmingUp: false, ...over
  };
}

describe('grocery-hidden.util', () => {
  beforeEach(() => localStorage.removeItem('grocery.hiddenDeals'));
  afterEach(() => localStorage.removeItem('grocery.hiddenDeals'));

  it('unterscheidet Angebote nach Kette und Grundpreis, nicht nach Ersparnis', () => {
    expect(groceryItemKey(item({ savingPercent: 30 }))).toBe(groceryItemKey(item()));
    expect(groceryItemKey(item({ basePrice: 1.2 }))).not.toBe(groceryItemKey(item()));
    expect(groceryItemKey(item({ chain: 'LIDL' }))).not.toBe(groceryItemKey(item()));
  });

  it('verwirft verjaehrte Eintraege', () => {
    const now = Date.now();
    saveHiddenGroceryDeals({ frisch: now - DAY_MS, alt: now - 61 * DAY_MS });
    expect(Object.keys(loadHiddenGroceryDeals(now))).toEqual(['frisch']);
  });

  it('wirft bei unlesbarem Speicherstand nicht', () => {
    for (const value of ['{kaputt', 'null', '5']) {
      localStorage.setItem('grocery.hiddenDeals', value);
      expect(loadHiddenGroceryDeals()).toEqual({});
    }
  });
});
