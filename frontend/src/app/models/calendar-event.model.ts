/** Feste Kategorienliste — Werte muessen dem Backend-Enum CalendarCategory entsprechen. */
export type CalendarCategory =
  'GENERAL' | 'FAMILY' | 'HEALTH' | 'HOUSEHOLD' | 'WORK' | 'BIRTHDAY';

/** Anzeige-Metadaten je Kategorie (Farbe fuer Chips und Dialog). */
export const CATEGORY_META: Record<CalendarCategory, { label: string; color: string }> = {
  GENERAL:   { label: 'Allgemein',  color: '#64b5f6' },
  FAMILY:    { label: 'Familie',    color: '#ba68c8' },
  HEALTH:    { label: 'Gesundheit', color: '#e57373' },
  HOUSEHOLD: { label: 'Haushalt',   color: '#81c784' },
  WORK:      { label: 'Arbeit',     color: '#ffb74d' },
  BIRTHDAY:  { label: 'Geburtstag', color: '#f06292' }
};

/** Anlege-/Aenderungsdaten eines Termins. */
export interface CalendarEventRequest {
  title: string;
  notes: string | null;
  category: CalendarCategory;
  allDay: boolean;
  /** ISO-Datum, z. B. "2026-08-03". */
  startDate: string;
  /** "HH:mm" oder null (ganztaegig). */
  startTime: string | null;
  endTime: string | null;
  endDate: string | null;
  /** iCal-RRULE; null = Einzeltermin. */
  rrule: string | null;
}

/** Stammdaten eines Termins/einer Serie (Bearbeiten-Dialog). */
export interface CalendarEvent extends CalendarEventRequest {
  id: number;
  recurring: boolean;
}

/** Ein expandiertes Vorkommen, wie es Monatsraster und Hub anzeigen. */
export interface CalendarOccurrence {
  /** Id der Master-Zeile (bei Overrides: der Serie). */
  eventId: number;
  occurrenceDate: string;
  /** Schluessel fuer die Occurrence-Endpoints; null bei Einzelterminen. */
  recurrenceDate: string | null;
  title: string;
  notes: string | null;
  category: CalendarCategory;
  allDay: boolean;
  startTime: string | null;
  endTime: string | null;
  endDate: string | null;
  recurring: boolean;
  /** 0 = heute, 1 = morgen. */
  daysUntil: number;
}
