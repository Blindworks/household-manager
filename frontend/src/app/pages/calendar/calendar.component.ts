import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CalendarService } from '../../services/calendar.service';
import {
  CATEGORY_META, CalendarCategory, CalendarOccurrence
} from '../../models/calendar-event.model';
import { MonthDay, buildMonthGrid } from '../../shared/month-grid.util';

/** Wie viele Termin-Chips eine Tageszelle zeigt; der Rest wird "+n weitere". */
const DAY_CHIP_LIMIT = 3;

/**
 * Haushaltskalender: Monatsraster mit Termin-Chips; Anlegen/Bearbeiten laeuft
 * ueber den Termindialog (siehe openCreate/openEdit).
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

  /** Wird in Task 15 mit dem Termindialog gefuellt. */
  openCreate(day: MonthDay): void {
  }

  /** Wird in Task 15 mit dem Termindialog gefuellt. */
  openEdit(occurrence: CalendarOccurrence, clickEvent: Event): void {
    clickEvent.stopPropagation();
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
}
