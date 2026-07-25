import { WasteCollectionEvent } from '../models/waste-collection.model';
import { HubInsight } from './hub-insight.model';
import { relativeDayLabel } from './relative-day.util';

/** @deprecated Alias — neue Aufrufer nutzen HubInsight direkt. */
export type WasteInsight = HubInsight;

/** Bis einschliesslich morgen ist der Termin dringlich — der Indikator wird rot. */
const URGENT_DAYS_UNTIL = 1;
/** Uebermorgen kuendigt sich der Termin an — der Indikator wird gelb. */
const SOON_DAYS_UNTIL = 2;

/**
 * Fasst die anstehenden Termine zu einer einzigen Hub-Meldung zusammen, z. B.
 * "Morgen: Biotonne · Übermorgen: Gelbe Tonne".
 *
 * @returns `null`, wenn nichts ansteht — dann erscheint im Hub kein Muell-Eintrag.
 */
export function buildWasteInsight(events: WasteCollectionEvent[]): WasteInsight | null {
  if (events.length === 0) {
    return null;
  }
  return {
    icon: 'delete',
    tone: toneFor(events),
    title: 'Müllabfuhr',
    text: events.map(describe).join(' · ')
  };
}

/**
 * Faerbt den Indikator nach der Dringlichkeit: heute und morgen rot, uebermorgen gelb,
 * alles Weitere blau.
 *
 * <p>Bei mehreren Terminen zaehlt der naechstliegende — die Meldung fasst sie zu einer
 * Zeile zusammen, und ein "Morgen: Biotonne" darf nicht dadurch verblassen, dass
 * uebermorgen noch etwas folgt.
 */
function toneFor(events: WasteCollectionEvent[]): WasteInsight['tone'] {
  const daysUntil = Math.min(...events.map(event => event.daysUntil));
  if (daysUntil <= URGENT_DAYS_UNTIL) {
    return 'error';
  }
  return daysUntil === SOON_DAYS_UNTIL ? 'tertiary' : 'primary';
}

function describe(event: WasteCollectionEvent): string {
  return `${relativeDayLabel(event.daysUntil, event.date)}: ${event.label}`;
}
