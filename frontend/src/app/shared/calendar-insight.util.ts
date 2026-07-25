import { CalendarOccurrence } from '../models/calendar-event.model';
import { HubInsight } from './hub-insight.model';
import { relativeDayLabel } from './relative-day.util';

/** Bis einschliesslich morgen ist der Termin dringlich — der Indikator wird rot. */
const URGENT_DAYS_UNTIL = 1;
/** Uebermorgen kuendigt sich der Termin an — der Indikator wird gelb. */
const SOON_DAYS_UNTIL = 2;

/**
 * Baut aus den naechsten Vorkommen bis zu `max` einzelne Hub-Eintraege
 * (Titel = Terminname, Text = "Morgen, 14:30 Uhr").
 */
export function buildCalendarInsights(
  occurrences: CalendarOccurrence[], max = 3): HubInsight[] {
  return occurrences.slice(0, max).map(occ => ({
    icon: 'event',
    tone: toneFor(occ),
    title: occ.title,
    text: describe(occ)
  }));
}

function toneFor(occ: CalendarOccurrence): HubInsight['tone'] {
  if (occ.daysUntil <= URGENT_DAYS_UNTIL) {
    return 'error';
  }
  return occ.daysUntil === SOON_DAYS_UNTIL ? 'tertiary' : 'primary';
}

function describe(occ: CalendarOccurrence): string {
  const day = relativeDayLabel(occ.daysUntil, occ.occurrenceDate);
  return occ.allDay || !occ.startTime ? day : `${day}, ${occ.startTime} Uhr`;
}
