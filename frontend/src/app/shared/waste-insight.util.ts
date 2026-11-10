import { WasteCollectionEvent } from '../models/waste-collection.model';
import { HubInsight } from './hub-insight.model';
import { relativeDayLabel } from './relative-day.util';

/** Bis einschliesslich morgen ist der Termin dringlich — der Indikator wird rot. */
const URGENT_DAYS_UNTIL = 1;
/** Uebermorgen kuendigt sich der Termin an — der Indikator wird gelb. */
const SOON_DAYS_UNTIL = 2;

/**
 * Ab dieser Stunde (lokale Zeit) gilt die Abfuhr des heutigen Tages als erledigt —
 * die Tonne ist dann geleert, die Karte waere nur noch Rauschen.
 */
export const WASTE_DONE_HOUR = 10;

/**
 * Baut je anstehendem Termin einen eigenen Hub-Eintrag, z. B. "Morgen: Biotonne".
 * Termine von heute entfallen ab {@link WASTE_DONE_HOUR} Uhr.
 *
 * @returns leere Liste, wenn nichts (mehr) ansteht — dann erscheint im Hub kein Muell-Eintrag.
 */
export function buildWasteInsights(events: WasteCollectionEvent[], now: Date = new Date()): HubInsight[] {
  return events
    .filter(event => !isCollectedToday(event, now))
    .map(event => ({
      icon: 'delete',
      tone: toneFor(event.daysUntil),
      title: 'Müllabfuhr',
      text: `${relativeDayLabel(event.daysUntil, event.date)}: ${event.label}`
    }));
}

function isCollectedToday(event: WasteCollectionEvent, now: Date): boolean {
  return event.daysUntil <= 0 && now.getHours() >= WASTE_DONE_HOUR;
}

/** Heute und morgen rot, uebermorgen gelb, alles Weitere blau. */
function toneFor(daysUntil: number): HubInsight['tone'] {
  if (daysUntil <= URGENT_DAYS_UNTIL) {
    return 'error';
  }
  return daysUntil === SOON_DAYS_UNTIL ? 'tertiary' : 'primary';
}
