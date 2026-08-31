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

/**
 * Formatiert "<Praefix> seit 42 Minuten." aus dem `lastChanged` einer Entitaet.
 * Anders als {@link sinceText} nennt diese Fassung die verstrichene Dauer statt der
 * Startuhrzeit — bei einer laufenden Maschine ist "seit 42 Minuten" griffiger als
 * "seit 17:46 Uhr".
 *
 * <p>Ein Zeitstempel aus der Zukunft liefert den Rueckfalltext statt einer negativen
 * Dauer: Server- und Browseruhr koennen minimal auseinanderlaufen, und
 * "seit -1 Minuten" waere sichtbarer Unsinn.
 *
 * @param prefix Zustandswort des Aufrufers, z. B. "Laeuft".
 * @param fallback Text bei unlesbarem oder zukuenftigem Zeitstempel.
 */
export function elapsedText(lastChanged: string, nowMs: number, prefix: string, fallback: string): string {
  const since = new Date(lastChanged);
  if (isNaN(since.getTime())) {
    return fallback;
  }
  const minutes = Math.floor((nowMs - since.getTime()) / 60_000);
  if (minutes < 0) {
    return fallback;
  }
  if (minutes < 1) {
    return `${prefix} seit weniger als einer Minute.`;
  }
  if (minutes < 60) {
    return `${prefix} seit ${minutes} ${minutes === 1 ? 'Minute' : 'Minuten'}.`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0
    ? `${prefix} seit ${hours} Std.`
    : `${prefix} seit ${hours} Std. ${rest} Min.`;
}
