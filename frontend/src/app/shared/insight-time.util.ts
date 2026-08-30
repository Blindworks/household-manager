/**
 * Formatiert "<Praefix> seit 17:46 Uhr." aus dem `lastChanged` einer Entitaet.
 * Liegt der Zeitpunkt vor dem heutigen Tag, steht zusaetzlich das Datum davor
 * ("Offen seit 13.08., 22:10 Uhr.") — sonst waere eine Uhrzeit ohne Datum irrefuehrend.
 *
 * @param prefix Zustandswort des Aufrufers, z. B. "Offen" oder "Fertig".
 * @param fallback Text bei unlesbarem Zeitstempel. Bewusst ein eigener Parameter:
 * er laesst sich nicht aus dem Praefix bilden ("Die Tuer ist gerade offen.").
 */
export function sinceText(lastChanged: string, nowMs: number, prefix: string, fallback: string): string {
  const since = new Date(lastChanged);
  if (isNaN(since.getTime())) {
    return fallback;
  }
  const time = since.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  if (isSameLocalDay(since, new Date(nowMs))) {
    return `${prefix} seit ${time} Uhr.`;
  }
  const date = since.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' });
  return `${prefix} seit ${date}, ${time} Uhr.`;
}

function isSameLocalDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate();
}
