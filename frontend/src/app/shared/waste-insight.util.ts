import { WasteCollectionEvent } from '../models/waste-collection.model';

/**
 * Ein fertiger Hinweis fuer den Intelligence Hub. Strukturgleich zum dortigen
 * `IntelligenceItem`: Das Dashboard rendert die Meldung selbst, weil die Styles der
 * Hub-Eintraege in seinem eigenen SCSS liegen und Angulars Style-Kapselung sie nicht
 * an eine Kind-Komponente weiterreicht.
 */
export interface WasteInsight {
  readonly icon: string;
  readonly tone: 'primary' | 'secondary' | 'muted' | 'tertiary' | 'error';
  readonly title: string;
  readonly text: string;
}

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
 * Faerbt den Indikator nach der Dringlichkeit: morgen rot, uebermorgen gelb, sonst blau.
 *
 * <p>Bei mehreren Terminen zaehlt der naechstliegende — die Meldung fasst sie zu einer
 * Zeile zusammen, und ein "Morgen: Biotonne" darf nicht dadurch verblassen, dass
 * uebermorgen noch etwas folgt.
 */
function toneFor(events: WasteCollectionEvent[]): WasteInsight['tone'] {
  const daysUntil = Math.min(...events.map(event => event.daysUntil));
  switch (daysUntil) {
    case 1: return 'error';
    case 2: return 'tertiary';
    default: return 'primary';
  }
}

function describe(event: WasteCollectionEvent): string {
  return `${relativeDayLabel(event)}: ${event.label}`;
}

/** "Heute"/"Morgen"/"Übermorgen", darueber hinaus der Wochentag. */
function relativeDayLabel(event: WasteCollectionEvent): string {
  switch (event.daysUntil) {
    case 0: return 'Heute';
    case 1: return 'Morgen';
    case 2: return 'Übermorgen';
    default: return weekdayOf(event.date);
  }
}

/**
 * Wochentag zu einem ISO-Datum. Bewusst aus den Datumsteilen gebaut statt via
 * `new Date('2026-07-20')`: Diese Kurzform parst als UTC-Mitternacht und wuerde bei
 * negativem UTC-Offset den Vortag anzeigen.
 */
function weekdayOf(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number);
  return new Date(year, month - 1, day)
    .toLocaleDateString('de-DE', { weekday: 'long' });
}
