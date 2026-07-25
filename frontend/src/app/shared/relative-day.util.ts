/** "Heute"/"Morgen"/"Übermorgen", darueber hinaus der Wochentag. */
export function relativeDayLabel(daysUntil: number, isoDate: string): string {
  switch (daysUntil) {
    case 0: return 'Heute';
    case 1: return 'Morgen';
    case 2: return 'Übermorgen';
    default: return weekdayOf(isoDate);
  }
}

/**
 * Wochentag zu einem ISO-Datum. Bewusst aus den Datumsteilen gebaut statt via
 * `new Date('2026-07-20')`: Diese Kurzform parst als UTC-Mitternacht und wuerde bei
 * negativem UTC-Offset den Vortag anzeigen.
 */
export function weekdayOf(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  return new Date(year, month - 1, day)
    .toLocaleDateString('de-DE', { weekday: 'long' });
}
