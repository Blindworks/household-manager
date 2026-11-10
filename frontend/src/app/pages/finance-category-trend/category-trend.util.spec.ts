import { averagePerMonth, groupTopCategories, monthLabel, OTHER_LABEL, startMonth } from './category-trend.util';
import { CategoryTrendRow } from '../../models/finance.model';

const row = (id: number | null, amounts: number[]): CategoryTrendRow => ({
  categoryId: id, categoryName: `K${id}`, color: null, amounts, total: amounts.reduce((a, b) => a + b, 0)
});

describe('category-trend.util', () => {
  it('fasst alles ab der Grenze zu „Sonstige“ zusammen', () => {
    const rows = [row(1, [10, 10]), row(2, [5, 5]), row(3, [1, 2]), row(4, [3, 0])];
    const grouped = groupTopCategories(rows, 3);
    expect(grouped.length).toBe(3);
    expect(grouped[2].categoryName).toBe(OTHER_LABEL);
    expect(grouped[2].amounts).toEqual([4, 2]);
    expect(grouped[2].total).toBe(6);
  });

  it('lässt wenige Kategorien unverändert', () => {
    const rows = [row(1, [1]), row(2, [2])];
    expect(groupTopCategories(rows, 3)).toBe(rows);
  });

  it('rechnet den Durchschnitt ohne den laufenden Monat', () => {
    const r = row(1, [100, 200, 30]);
    expect(averagePerMonth(r, ['2026-09', '2026-10', '2026-11'], '2026-11')).toBe(150);
  });

  it('zählt Monate ohne Ausgaben als 0 mit', () => {
    expect(averagePerMonth(row(1, [0, 90]), ['2026-08', '2026-09'], '2026-11')).toBe(45);
  });

  it('nimmt beim einzigen, laufenden Monat dessen Wert', () => {
    expect(averagePerMonth(row(1, [40]), ['2026-11'], '2026-11')).toBe(40);
  });

  it('berechnet den Startmonat über den Jahreswechsel', () => {
    expect(startMonth('2026-02', 6)).toBe('2025-09');
    expect(startMonth('2026-11', 12)).toBe('2025-12');
  });

  it('beschriftet Monate kurz', () => {
    expect(monthLabel('2026-11')).toBe('Nov 26');
    expect(monthLabel('2026-03')).toBe('Mär 26');
  });
});
