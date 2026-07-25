/** Eine Tageszelle des Monatsrasters. */
export interface MonthDay {
  /** ISO-Datum, z. B. "2026-07-25". */
  isoDate: string;
  dayOfMonth: number;
  /** false fuer Randtage der Nachbarmonate. */
  inMonth: boolean;
  isToday: boolean;
}

/**
 * Monatsraster als Wochen (Mo–So) inklusive Randtagen der Nachbarmonate.
 *
 * @param month 1-12
 */
export function buildMonthGrid(year: number, month: number, today: Date): MonthDay[][] {
  const firstOfMonth = new Date(year, month - 1, 1);
  const mondayOffset = (firstOfMonth.getDay() + 6) % 7;
  const cursor = new Date(year, month - 1, 1 - mondayOffset);

  const weeks: MonthDay[][] = [];
  do {
    const week: MonthDay[] = [];
    for (let i = 0; i < 7; i++) {
      week.push({
        isoDate: toIso(cursor),
        dayOfMonth: cursor.getDate(),
        inMonth: cursor.getMonth() === month - 1,
        isToday: sameDay(cursor, today)
      });
      cursor.setDate(cursor.getDate() + 1);
    }
    weeks.push(week);
  } while (cursor.getMonth() === month - 1);
  return weeks;
}

/** ISO-Datum aus den lokalen Datumsteilen (kein toISOString — das kippt per UTC den Tag). */
function toIso(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}
