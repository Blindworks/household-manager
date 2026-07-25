import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { CalendarService } from '../../services/calendar.service';
import {
  CATEGORY_META, CalendarCategory, CalendarEvent, CalendarEventRequest, CalendarOccurrence
} from '../../models/calendar-event.model';
import { MonthDay, buildMonthGrid } from '../../shared/month-grid.util';
import {
  Frequency, RecurrenceOptions, Weekday, buildRrule, parseRrule
} from '../../shared/rrule.util';

/** Wie viele Termin-Chips eine Tageszelle zeigt; der Rest wird "+n weitere". */
const DAY_CHIP_LIMIT = 3;

/** Formularzustand des Termindialogs (Strings, wie die Inputs sie liefern). */
interface CalendarFormState {
  title: string;
  notes: string;
  category: CalendarCategory;
  allDay: boolean;
  startDate: string;
  startTime: string;
  endTime: string;
  endDate: string;
}

/**
 * Zustand des Wiederholungs-Builders im Dialog.
 *
 * Eigener Typ statt einer Wiederverwendung von RecurrenceOptions mit "as"-Cast: der
 * Dialog muss zusaetzlich "keine Wiederholung" (freq 'NONE') abbilden koennen, ein
 * Wert, den RecurrenceOptions bewusst nicht kennt (das ist die Vertragsgrenze zu
 * buildRrule/parseRrule, die nur echte Wiederholungen beschreiben).
 */
type RecurrenceFormState = Omit<RecurrenceOptions, 'freq'> & { freq: 'NONE' | Frequency };

/**
 * Haushaltskalender: Monatsraster mit Termin-Chips; Anlegen/Bearbeiten laeuft
 * ueber den Termindialog inkl. Wiederholungs-Builder und Serien-Scope-Rueckfrage.
 */
@Component({
  selector: 'app-calendar',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './calendar.component.html',
  styleUrl: './calendar.component.scss'
})
export class CalendarComponent implements OnInit {
  private readonly calendarService = inject(CalendarService);

  readonly categoryMeta = CATEGORY_META;
  readonly categories = Object.keys(CATEGORY_META) as CalendarCategory[];
  readonly weekdayLabels = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
  readonly dayChipLimit = DAY_CHIP_LIMIT;

  /** Angezeigter Monat. */
  viewYear = new Date().getFullYear();
  viewMonth = new Date().getMonth() + 1;

  grid: MonthDay[][] = [];
  /** Vorkommen des sichtbaren Rasters, gruppiert nach ISO-Datum. */
  occurrencesByDate = new Map<string, CalendarOccurrence[]>();
  loadError: string | null = null;

  /** Formularzustand des Termindialogs. */
  form = this.emptyForm();
  /** Wiederholungs-Builder; freq 'NONE' = Einzeltermin. */
  recurrence = CalendarComponent.defaultRecurrence();
  /** Roh-RRULE im "Erweitert"-Modus; hat Vorrang vor dem Builder. */
  advancedMode = false;
  rawRrule = '';

  dialogOpen = false;
  /** Beim Bearbeiten gesetzt; null = Anlegen. */
  editing: { event: CalendarEvent; occurrence: CalendarOccurrence } | null = null;
  /** Offene Scope-Frage bei Serien ('save' | 'delete'); null = keine. */
  scopeQuestion: 'save' | 'delete' | null = null;
  dialogError: string | null = null;
  saving = false;

  readonly weekdayOptions: { code: Weekday; label: string }[] = [
    { code: 'MO', label: 'Mo' }, { code: 'TU', label: 'Di' }, { code: 'WE', label: 'Mi' },
    { code: 'TH', label: 'Do' }, { code: 'FR', label: 'Fr' }, { code: 'SA', label: 'Sa' },
    { code: 'SU', label: 'So' }
  ];
  readonly frequencyOptions: { code: 'NONE' | Frequency; label: string }[] = [
    { code: 'NONE', label: 'Keine' }, { code: 'DAILY', label: 'Täglich' },
    { code: 'WEEKLY', label: 'Wöchentlich' }, { code: 'MONTHLY', label: 'Monatlich' },
    { code: 'YEARLY', label: 'Jährlich' }
  ];

  ngOnInit(): void {
    this.rebuildGrid();
  }

  get monthLabel(): string {
    return new Date(this.viewYear, this.viewMonth - 1, 1)
      .toLocaleDateString('de-DE', { month: 'long', year: 'numeric' });
  }

  previousMonth(): void {
    this.shiftMonth(-1);
  }

  nextMonth(): void {
    this.shiftMonth(1);
  }

  goToToday(): void {
    const now = new Date();
    this.viewYear = now.getFullYear();
    this.viewMonth = now.getMonth() + 1;
    this.rebuildGrid();
  }

  chipsFor(day: MonthDay): CalendarOccurrence[] {
    return this.occurrencesByDate.get(day.isoDate) ?? [];
  }

  overflowCount(day: MonthDay): number {
    return Math.max(0, this.chipsFor(day).length - DAY_CHIP_LIMIT);
  }

  colorFor(occurrence: CalendarOccurrence): string {
    return CATEGORY_META[occurrence.category].color;
  }

  openCreate(day: MonthDay): void {
    this.editing = null;
    this.form = this.emptyForm();
    this.form.startDate = day.isoDate;
    this.recurrence = CalendarComponent.defaultRecurrence();
    this.advancedMode = false;
    this.rawRrule = '';
    this.dialogError = null;
    this.dialogOpen = true;
  }

  /** Laedt die Stammdaten (bei Serien die Master-Zeile) und oeffnet den Dialog. */
  openEdit(occurrence: CalendarOccurrence, clickEvent: Event): void {
    clickEvent.stopPropagation();
    this.calendarService.getEvent(occurrence.eventId).subscribe({
      next: event => {
        this.editing = { event, occurrence };
        this.form = {
          title: event.title,
          notes: event.notes ?? '',
          category: event.category,
          allDay: event.allDay,
          startDate: event.startDate,
          startTime: event.startTime ?? '',
          endTime: event.endTime ?? '',
          endDate: event.endDate ?? ''
        };
        const parsed = event.rrule ? parseRrule(event.rrule) : null;
        if (event.rrule && !parsed) {
          // Regel jenseits des Builders -> nur als Rohtext editierbar
          this.advancedMode = true;
          this.rawRrule = event.rrule;
          this.recurrence = CalendarComponent.defaultRecurrence();
        } else {
          this.advancedMode = false;
          this.rawRrule = event.rrule ?? '';
          this.recurrence = parsed ? { ...parsed } : CalendarComponent.defaultRecurrence();
        }
        this.dialogError = null;
        this.dialogOpen = true;
      },
      error: (err: Error) => (this.loadError = err.message)
    });
  }

  closeDialog(): void {
    this.dialogOpen = false;
    this.scopeQuestion = null;
    this.editing = null;
  }

  toggleWeekday(code: Weekday): void {
    const index = this.recurrence.weekdays.indexOf(code);
    if (index >= 0) {
      this.recurrence.weekdays.splice(index, 1);
    } else {
      this.recurrence.weekdays.push(code);
    }
  }

  /**
   * Speichern-Klick: Serien fragen zuerst nach dem Geltungsbereich - aber nur, wenn das
   * bearbeitete Vorkommen ueberhaupt einzeln adressierbar ist (siehe canAskScope()).
   */
  onSaveClicked(): void {
    if (this.canAskScope()) {
      this.scopeQuestion = 'save';
      return;
    }
    this.save('series');
  }

  /** Loeschen-Klick: Serien fragen zuerst nach dem Geltungsbereich (siehe onSaveClicked). */
  onDeleteClicked(): void {
    if (!this.editing) {
      return;
    }
    if (this.canAskScope()) {
      this.scopeQuestion = 'delete';
      return;
    }
    this.performDelete('series');
  }

  answerScope(scope: 'occurrence' | 'series'): void {
    const question = this.scopeQuestion;
    this.scopeQuestion = null;
    if (question === 'save') {
      this.save(scope);
    } else if (question === 'delete') {
      this.performDelete(scope);
    }
  }

  /**
   * Die "Nur diesen Termin"-Rueckfrage ergibt nur Sinn, wenn das bearbeitete Vorkommen
   * eine eigene recurrenceDate traegt - die ist der Schluessel, den die Occurrence-
   * Endpunkte brauchen. Bei Einzelterminen ist recurrenceDate laut Modell immer null;
   * ohne diese Absicherung wuerde answerScope('occurrence') dort ins Leere laufen.
   */
  private canAskScope(): boolean {
    return this.editing !== null
      && this.editing.event.recurring
      && this.editing.occurrence.recurrenceDate !== null;
  }

  private save(scope: 'occurrence' | 'series'): void {
    const request = this.buildRequest(scope);
    if (!request) {
      return;
    }
    // create/update liefern CalendarEvent, updateOccurrence CalendarOccurrence - der
    // Rueckgabewert wird unten ohnehin nicht gebraucht (nur rebuildGrid()). Auf ein
    // gemeinsames Observable<void> abbilden, statt eine Union von Observable<T>
    // zusammenzubauen: subscribe() auf so einer Union ist kein gueltiger Aufruf mehr,
    // weil TS die Observer-Ueberladungen der beiden T dann nicht mehr vereinen kann.
    let call: Observable<void>;
    if (!this.editing) {
      call = this.calendarService.create(request).pipe(map(() => undefined));
    } else if (scope === 'series') {
      call = this.calendarService.update(this.editing.event.id, request).pipe(map(() => undefined));
    } else {
      const recurrenceDate = this.editing.occurrence.recurrenceDate;
      if (recurrenceDate === null) {
        // canAskScope() verhindert dies schon vorher; dieser Zweig ist der saubere
        // Ersatz fuer eine Non-Null-Assertion auf einen Wert, den TS nicht garantieren kann.
        this.dialogError = 'Dieses Vorkommen laesst sich nicht einzeln aendern.';
        return;
      }
      call = this.calendarService.updateOccurrence(this.editing.event.id, recurrenceDate, request)
        .pipe(map(() => undefined));
    }
    this.saving = true;
    call.subscribe({
      next: () => {
        this.saving = false;
        this.closeDialog();
        this.rebuildGrid();
      },
      error: (err: Error) => {
        this.saving = false;
        this.dialogError = err.message;
      }
    });
  }

  private performDelete(scope: 'occurrence' | 'series'): void {
    if (!this.editing) {
      return;
    }
    let call: Observable<void>;
    if (scope === 'series') {
      call = this.calendarService.delete(this.editing.event.id);
    } else {
      const recurrenceDate = this.editing.occurrence.recurrenceDate;
      if (recurrenceDate === null) {
        this.dialogError = 'Dieses Vorkommen laesst sich nicht einzeln loeschen.';
        return;
      }
      call = this.calendarService.deleteOccurrence(this.editing.event.id, recurrenceDate);
    }
    this.saving = true;
    call.subscribe({
      next: () => {
        this.saving = false;
        this.closeDialog();
        this.rebuildGrid();
      },
      error: (err: Error) => {
        this.saving = false;
        this.dialogError = err.message;
      }
    });
  }

  /** Formular -> Request; einfache Client-Vorpruefung, die harte Pruefung macht das Backend. */
  private buildRequest(scope: 'occurrence' | 'series'): CalendarEventRequest | null {
    if (!this.form.title.trim()) {
      this.dialogError = 'Der Titel darf nicht leer sein.';
      return null;
    }
    if (!this.form.startDate) {
      this.dialogError = 'Das Startdatum fehlt.';
      return null;
    }
    if (!this.form.allDay && !this.form.startTime) {
      this.dialogError = 'Ein Termin mit Uhrzeit braucht eine Start-Uhrzeit.';
      return null;
    }
    // "Nur dieser Termin" traegt nie eine RRULE - das Vorkommen bleibt Teil der Serie
    const rrule = scope === 'occurrence' ? null : this.currentRrule();
    return {
      title: this.form.title.trim(),
      notes: this.form.notes.trim() || null,
      category: this.form.category,
      allDay: this.form.allDay,
      startDate: this.form.startDate,
      startTime: this.form.allDay ? null : this.form.startTime || null,
      endTime: this.form.allDay ? null : this.form.endTime || null,
      endDate: this.form.endDate || null,
      rrule
    };
  }

  private currentRrule(): string | null {
    if (this.advancedMode) {
      return this.rawRrule.trim() || null;
    }
    // Destrukturieren statt eines "as RecurrenceOptions"-Casts: nach der freq==='NONE'-
    // Pruefung auf der lokalen Konstante ist freq fuer TS ohne weiteres Zutun als
    // Frequency erkennbar, buildRrule bekommt also ein echtes RecurrenceOptions.
    const { freq, ...rest } = this.recurrence;
    if (freq === 'NONE') {
      return null;
    }
    const options: RecurrenceOptions = { freq, ...rest };
    return buildRrule(options, this.form.startDate);
  }

  private shiftMonth(delta: number): void {
    const shifted = new Date(this.viewYear, this.viewMonth - 1 + delta, 1);
    this.viewYear = shifted.getFullYear();
    this.viewMonth = shifted.getMonth() + 1;
    this.rebuildGrid();
  }

  protected rebuildGrid(): void {
    this.grid = buildMonthGrid(this.viewYear, this.viewMonth, new Date());
    this.loadOccurrences();
  }

  private loadOccurrences(): void {
    const from = this.grid[0][0].isoDate;
    const lastWeek = this.grid[this.grid.length - 1];
    const to = lastWeek[6].isoDate;
    this.calendarService.getOccurrences(from, to).subscribe({
      next: occurrences => {
        this.loadError = null;
        this.occurrencesByDate = new Map();
        for (const occ of occurrences) {
          const list = this.occurrencesByDate.get(occ.occurrenceDate) ?? [];
          list.push(occ);
          this.occurrencesByDate.set(occ.occurrenceDate, list);
        }
      },
      error: (err: Error) => (this.loadError = err.message)
    });
  }

  private emptyForm(): CalendarFormState {
    return {
      title: '', notes: '', category: 'GENERAL', allDay: true,
      startDate: '', startTime: '', endTime: '', endDate: ''
    };
  }

  private static defaultRecurrence(): RecurrenceFormState {
    return {
      freq: 'NONE', interval: 1, weekdays: [],
      monthlyMode: 'DAY_OF_MONTH', endType: 'NEVER', untilDate: null, count: null
    };
  }
}
