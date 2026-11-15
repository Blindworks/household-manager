import { GrocerySummary, GrocerySummaryItem } from '../models/grocery.model';
import { buildGroceryInsight } from './grocery-insight.util';

function item(over: Partial<GrocerySummaryItem> = {}): GrocerySummaryItem {
  return {
    productName: 'Cola Zero', ownerUserId: null, ownerName: null, chain: 'PENNY', chainName: 'Penny',
    basePrice: 1.43, baseUnit: 'LITER', savingPercent: 23, ...over
  };
}

describe('buildGroceryInsight', () => {
  it('noInsightWithoutItems', () => {
    expect(buildGroceryInsight({ items: [] }, 1, false)).toBeNull();
  });

  it('pluralisesTitle', () => {
    expect(buildGroceryInsight({ items: [item()] }, 1, false)!.title).toBe('1 Schnäppchen');
    expect(buildGroceryInsight({ items: [item(), item()] }, 1, false)!.title).toBe('2 Schnäppchen');
  });

  it('formatsFirstTwoItems', () => {
    const summary: GrocerySummary = {
      items: [item(), item({ productName: 'Butter', chainName: 'Lidl', basePrice: 7.2, baseUnit: 'KILOGRAM', savingPercent: 15 }), item({ productName: 'Dritter' })]
    };
    const insight = buildGroceryInsight(summary, 1, false)!;
    expect(insight.text).toBe('Cola Zero bei Penny 1,43 €/l (−23 %) · Butter bei Lidl 7,20 €/kg (−15 %)');
    expect(insight.icon).toBe('shopping_cart');
    expect(insight.tone).toBe('secondary');
    expect(insight.link).toBe('/groceries');
  });

  it('websiteShowsOwnAndHouseholdOnly', () => {
    const summary: GrocerySummary = {
      items: [item({ productName: 'Fremd', ownerUserId: 9 }), item({ productName: 'Eigen', ownerUserId: 1 }), item({ productName: 'Haushalt' })]
    };
    const insight = buildGroceryInsight(summary, 1, false)!;
    expect(insight.title).toBe('2 Schnäppchen');
    expect(insight.text).not.toContain('Fremd');
  });

  it('tabletShowsAllWithoutLink', () => {
    const summary: GrocerySummary = { items: [item({ ownerUserId: 9 }), item({ ownerUserId: 1 })] };
    const insight = buildGroceryInsight(summary, null, true)!;
    expect(insight.title).toBe('2 Schnäppchen');
    expect(insight.link).toBeUndefined();
  });
});
