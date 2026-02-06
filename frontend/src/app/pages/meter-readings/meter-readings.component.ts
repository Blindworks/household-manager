import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { MeterReadingFormComponent } from '../../components/meter-reading-form/meter-reading-form.component';
import { MeterReadingService } from '../../services/meter-reading.service';
import { MeterReading, MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Meter readings page component.
 * Displays meter reading form and reading history.
 */
@Component({
  selector: 'app-meter-readings',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, MeterReadingFormComponent],
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

  /**
   * Gibt die gefilterten Ablesungen zurück
   */
  get filteredReadings(): MeterReading[] {
    return this.readings;
  }
}
