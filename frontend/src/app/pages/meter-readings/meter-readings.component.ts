import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { MeterReadingFormComponent } from '../../components/meter-reading-form/meter-reading-form.component';
import { MeterReadingImportComponent } from '../../components/meter-reading-import/meter-reading-import.component';
import { MeterReadingService } from '../../services/meter-reading.service';
import { MeterReading, MeterType } from '../../models/meter-reading.model';
import { toLocalDateString, toLocalDateTimeString } from '../../shared/local-date.util';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Meter readings page component.
 * Displays meter reading form and reading history.
 */
@Component({
  selector: 'app-meter-readings',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, MeterReadingFormComponent, MeterReadingImportComponent],
  templateUrl: './meter-readings.component.html',
  styleUrl: './meter-readings.component.scss'
})
export class MeterReadingsComponent implements OnInit {
  private readonly meterReadingService = inject(MeterReadingService);

  /** Alle Zählerablesungen */
  readings: MeterReading[] = [];

  /** Aktuell ausgewählter Filter-Typ */
  selectedFilter: MeterType | 'ALL' = 'ALL';

  /** Loading-Status */
  isLoading = false;

  /** Error-Message */
  errorMessage: string | null = null;

  /** MeterTypeUtils für Template-Zugriff */
  meterTypeUtils = MeterTypeUtils;

  /** MeterType Enum für Template-Zugriff */
  MeterType = MeterType;

  /** Verfügbare Zählertypen */
  meterTypes = MeterTypeUtils.getAllTypes();

  /** Offene Aktion an einer Ablesung (Korrigieren oder Löschen) */
  action: { kind: 'correct' | 'delete'; reading: MeterReading } | null = null;

  /** Eingaben der Korrektur */
  correction: { value: number | null; date: string; notes: string } = { value: null, date: '', notes: '' };

  actionBusy = false;
  actionError: string | null = null;

  ngOnInit(): void {
    this.loadReadings();
  }

  /**
   * Lädt alle Ablesungen oder filtert nach Typ
   */
  loadReadings(): void {
    this.isLoading = true;
    this.errorMessage = null;

    const request = this.selectedFilter === 'ALL'
      ? this.meterReadingService.getAllReadings()
      : this.meterReadingService.getReadingsByType(this.selectedFilter);

    request.subscribe({
      next: (readings) => {
        this.readings = readings;
        this.isLoading = false;
      },
      error: (error: Error) => {
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  /**
   * Wird aufgerufen, wenn eine neue Ablesung erstellt wurde
   */
  onReadingCreated(): void {
    this.loadReadings();
  }

  /**
   * Filtert nach Zählertyp
   */
  filterByType(type: MeterType | 'ALL'): void {
    this.selectedFilter = type;
    this.loadReadings();
  }

  startCorrection(reading: MeterReading): void {
    this.action = { kind: 'correct', reading };
    this.actionError = null;
    this.correction = {
      value: reading.readingValue,
      date: toLocalDateString(reading.readingDate),
      notes: reading.notes ?? ''
    };
  }

  startDelete(reading: MeterReading): void {
    this.action = { kind: 'delete', reading };
    this.actionError = null;
  }

  cancelAction(): void {
    this.action = null;
    this.actionError = null;
  }

  get today(): string {
    return toLocalDateString(new Date());
  }

  get correctionValid(): boolean {
    const { value, date } = this.correction;
    return value !== null && value > 0 && date !== '' && date <= this.today;
  }

  saveCorrection(): void {
    const reading = this.action?.reading;
    if (reading?.id === undefined || !this.correctionValid || this.actionBusy) {
      return;
    }
    this.runAction(this.meterReadingService.correctReading(reading.id, {
      meterType: reading.meterType,
      readingValue: this.correction.value!,
      readingDate: toLocalDateTimeString(this.correction.date, reading.readingDate),
      notes: this.correction.notes.trim() || undefined
    }));
  }

  confirmDelete(): void {
    const id = this.action?.reading.id;
    if (id === undefined || this.actionBusy) {
      return;
    }
    this.runAction(this.meterReadingService.deleteReading(id));
  }

  private runAction(request: Observable<unknown>): void {
    this.actionBusy = true;
    this.actionError = null;
    request.subscribe({
      next: () => {
        this.actionBusy = false;
        this.action = null;
        this.loadReadings();
      },
      error: (error: Error) => {
        this.actionBusy = false;
        this.actionError = error.message;
      }
    });
  }

  /**
   * Gibt die gefilterten Ablesungen zurück
   */
  get filteredReadings(): MeterReading[] {
    return this.readings;
  }
}
