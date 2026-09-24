/**
 * Formatiert den letzten Auslösezeitpunkt eines Flows für die Übersicht:
 * „heute, 19:30“, „gestern, 07:00“, sonst „24.09.2026, 19:30“.
 * Ohne (lesbaren) Zeitstempel „Nie“ — die Spalte gibt es erst seit 2026-09-24,
 * ältere Auslösungen sind nicht bekannt.
 */
export function lastTriggeredText(lastTriggeredAt: string | null | undefined, nowMs: number): string {
  if (!lastTriggeredAt) {
    return 'Nie';
  }
  const at = new Date(lastTriggeredAt);
  if (isNaN(at.getTime())) {
    return 'Nie';
  }
  const time = at.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  const today = startOfLocalDay(new Date(nowMs));
  const day = startOfLocalDay(at);
  const dayDiff = Math.round((today - day) / 86_400_000);
  if (dayDiff === 0) {
    return `heute, ${time}`;
  }
  if (dayDiff === 1) {
    return `gestern, ${time}`;
  }
  const date = at.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' });
  return `${date}, ${time}`;
}

function startOfLocalDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}
