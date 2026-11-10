import { CategoryTrendRow } from '../../models/finance.model';

/** Wie viele Kategorien das Diagramm einzeln zeigt; der Rest wird zu „Sonstige“ zusammengefasst. */
export const CHART_CATEGORY_LIMIT = 8;
export const OTHER_LABEL = 'Sonstige';
export const OTHER_COLOR = '#b0bec5';

export function categoryKey(row: CategoryTrendRow): string {
  return row.categoryId == null ? 'none' : String(row.categoryId);
}

/**
 * Größte Kategorien einzeln, alle übrigen als eine Zeile „Sonstige“ (Summe je Monat).
 * Erwartet die Zeilen nach Gesamtsumme absteigend, wie sie der Server liefert.
 */
export function groupTopCategories(rows: CategoryTrendRow[], limit = CHART_CATEGORY_LIMIT): CategoryTrendRow[] {
  if (rows.length <= limit) {
    return rows;
  }
  const top = rows.slice(0, limit - 1);
  const rest = rows.slice(limit - 1);
  const months = rest[0].amounts.length;
  const amounts = Array.from({ length: months }, (_, i) => rest.reduce((sum, r) => sum + r.amounts[i], 0));
  return [...top, {
    categoryId: null,
    categoryName: OTHER_LABEL,
    color: OTHER_COLOR,
    amounts,
    total: amounts.reduce((a, b) => a + b, 0)
  }];
}

/**
 * Durchschnitt je Monat über die abgeschlossenen Monate. Der laufende Monat (falls im Zeitraum)
 * zählt nicht mit, sonst zöge der halbe Monat den Schnitt nach unten. Besteht der Zeitraum nur
 * aus dem laufenden Monat, ist es dessen Wert.
 */
export function averagePerMonth(row: CategoryTrendRow, months: string[], currentMonth: string): number {
  const values = row.amounts.filter((_, i) => months[i] !== currentMonth);
  if (values.length === 0) {
    return row.amounts.reduce((a, b) => a + b, 0);
  }
  return values.reduce((a, b) => a + b, 0) / values.length;
}

const MONTH_NAMES = ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'];

/** „2026-11“ ⇒ „Nov 26“ */
export function monthLabel(month: string): string {
  const [year, m] = month.split('-').map(Number);
  return `${MONTH_NAMES[m - 1]} ${String(year).slice(2)}`;
}

/** Endmonat und Anzahl Monate ⇒ Startmonat (yyyy-MM), sodass der Zeitraum genau count Monate umfasst. */
export function startMonth(endMonth: string, count: number): string {
  const [year, m] = endMonth.split('-').map(Number);
  const date = new Date(year, m - count, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}
