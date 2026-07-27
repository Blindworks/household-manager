import { CalendarCategoryRef } from './calendar-category.model';

/** Eine dem Termin zugeordnete Person. */
export interface CalendarPerson {
  id: number;
  displayName: string;
}

/** Anlege-/Aenderungsdaten eines Termins. */
export interface CalendarEventRequest {
  title: string;
  notes: string | null;
  categoryId: number;
  /** Leer = Haushaltstermin. */
  personUserIds: number[];
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
export interface CalendarEvent extends Omit<CalendarEventRequest, 'categoryId' | 'personUserIds'> {
  id: number;
  /**
   * null, wenn die Kategorie nicht aufloesbar war. Der Fremdschluessel schliesst das
   * praktisch aus, aber das Backend liefert bewusst null statt zu werfen — eine fehlende
   * Farbe darf nie den ganzen Monat leeren (siehe CalendarEventService.categoryView).
   */
  category: CalendarCategoryRef | null;
  /** Leer = Haushaltstermin; nie null (das Backend liefert immer eine Liste). */
  persons: CalendarPerson[];
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
  /** null, wenn die Kategorie nicht aufloesbar war — siehe CalendarEvent.category. */
  category: CalendarCategoryRef | null;
  /** Leer = Haushaltstermin; nie null (das Backend liefert immer eine Liste). */
  persons: CalendarPerson[];
  allDay: boolean;
  startTime: string | null;
  endTime: string | null;
  endDate: string | null;
  recurring: boolean;
  /** 0 = heute, 1 = morgen. */
  daysUntil: number;
}
