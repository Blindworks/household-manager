import { CalendarOccurrence } from '../models/calendar-event.model';
import { MonthDay } from './month-grid.util';

/** Wie viele Termin-Chips eine Tageszelle zeigt; der Rest wird "+n weitere". */
export const DAY_CHIP_LIMIT = 3;

/**
 * Wie viele Personen-Initialen ein Chip zeigt; der Rest wird "+n". Eine Tageszelle ist rund
 * ein Siebtel der Rasterbreite - ohne Deckel verdraengen die Initialen den Termintitel.
 */
export const CHIP_INITIAL_LIMIT = 2;

/** Aktiver Personenfilter; null = alle Termine. */
export type PersonFilter = number | null;

/** Ein Termin-Chip einer Tageszelle, fertig fuer die Anzeige. */
export interface ChipView {
  /** Fuer den Bearbeiten-Klick; die Anzeige selbst braucht ihn nicht mehr. */
  occurrence: CalendarOccurrence;
  /** null laesst den --chip-color-Default aus dem SCSS greifen. */
  color: string | null;
  /** Nur bei Terminen mit Uhrzeit gesetzt. */
  time: string | null;
  title: string;
  /** Initialen der zugeordneten Personen, gedeckelt; leer = Haushaltstermin. */
  initials: string[];
  /** Wie viele Personen der Chip nicht mehr als Initiale zeigt. */
  hiddenPersons: number;
  /**
   * Vollstaendige Beschriftung fuer title/aria-label. Eine einzelne Initiale ist mehrdeutig
   * ("Anna"/"Anton"), und der Titel wird im Raster oft abgeschnitten.
   */
  label: string;
}

/** Eine Tageszelle mit ihren sichtbaren Chips. */
export interface DayView {
  day: MonthDay;
  chips: ChipView[];
  /** Wie viele Termine des Tages der Deckel abschneidet ("+n weitere"). */
  overflow: number;
}

/**
 * Ein Personenfilter zeigt zusaetzlich IMMER die Termine ohne Zuordnung: "Meine Termine"
 * heisst "mir zugeordnet oder den ganzen Haushalt betreffend". Sonst verschwaende die
 * Muellabfuhr genau dann aus dem Blick, wenn jemand auf sich selbst filtert - der Fall,
 * in dem sie am ehesten uebersehen wird.
 */
export function matchesPersonFilter(occurrence: CalendarOccurrence,
                                    filter: PersonFilter): boolean {
  return filter === null
    || occurrence.persons.length === 0
    || occurrence.persons.some(person => person.id === filter);
}

/**
 * Anzeigemodell einer Tageszelle: gefiltert, gedeckelt und mit fertigen Chips.
 *
 * Bewusst pur (kein Zugriff auf Komponentenzustand), damit das Raster die Chips einmal
 * vorbauen kann statt sie bei jedem Change-Detection-Lauf im Template zu berechnen - und
 * damit die Deckelungs- und Beschriftungslogik ohne DOM pruefbar bleibt.
 *
 * @param occurrences alle Vorkommen dieses Tages, ungefiltert
 */
export function buildDayView(day: MonthDay, occurrences: CalendarOccurrence[],
                             filter: PersonFilter): DayView {
  const visible = occurrences.filter(occ => matchesPersonFilter(occ, filter));
  return {
    day,
    chips: visible.slice(0, DAY_CHIP_LIMIT).map(buildChip),
    // Zaehlt nur die sichtbaren Termine: eine Zaehlung auf der ungefilterten Liste
    // verspraeche Termine, die der Filter gerade ausblendet.
    overflow: Math.max(0, visible.length - DAY_CHIP_LIMIT)
  };
}

function buildChip(occurrence: CalendarOccurrence): ChipView {
  const time = !occurrence.allDay && occurrence.startTime ? occurrence.startTime : null;
  const names = occurrence.persons.map(person => person.displayName);
  const prefix = time ? `${time} ` : '';
  return {
    occurrence,
    color: occurrence.category?.color ?? null,
    time,
    title: occurrence.title,
    initials: occurrence.persons.slice(0, CHIP_INITIAL_LIMIT)
      .map(person => person.displayName.charAt(0).toUpperCase()),
    hiddenPersons: Math.max(0, occurrence.persons.length - CHIP_INITIAL_LIMIT),
    label: names.length ? `${prefix}${occurrence.title} — ${names.join(', ')}`
                        : `${prefix}${occurrence.title}`
  };
}
