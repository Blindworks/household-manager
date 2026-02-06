import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { MeterReadingService } from '../../services/meter-reading.service';
import { MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Dashboard component - main application dashboard.
 * Displays overview of household utilities and meter readings.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {
  private readonly meterReadingService = inject(MeterReadingService);

  /** Meter reading data for display */
  meterData: MeterCardData[] = [];

  /** Loading state */
  isLoading = true;

  /** Error message */
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.loadMeterData();
  }

  /**
   * Loads meter data from backend API for all meter types
   */
  private loadMeterData(): void {
    this.isLoading = true;
    this.errorMessage = null;

    const allTypes = MeterTypeUtils.getAllTypes();

    // Load latest reading and consumption for each meter type in parallel
    const requests = allTypes.map(type =>
      forkJoin({
        type: [type],
        latestReading: this.meterReadingService.getLatestReading(type),
        consumption: this.meterReadingService.getConsumptionStats(type)
      })
    );

    forkJoin(requests).subscribe({
      next: (results) => {
        this.meterData = results.map(result => {
          const type = result.type;
          const latest = result.latestReading;
          const consumption = result.consumption;

          return {
            type: MeterTypeUtils.getLabel(type),
            meterType: type,
            icon: MeterTypeUtils.getIcon(type),
            color: MeterTypeUtils.getColor(type),
            lastReading: latest?.readingValue || 0,
            unit: MeterTypeUtils.getUnit(type),
            consumption: consumption?.consumption || 0,
            consumptionPeriod: consumption
              ? `${consumption.daysBetweenReadings} Tage`
              : 'Keine Daten',
            trend: this.calculateTrend(consumption?.consumption || 0),
            hasData: !!latest
          };
        });
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Error loading meter data:', error);
        this.errorMessage = 'Fehler beim Laden der Zählerdaten. Bitte versuchen Sie es später erneut.';
        this.isLoading = false;

        // Fallback to empty data
        this.meterData = this.getEmptyMeterData();
      }
    });
  }

  /**
   * Creates empty meter data as fallback
   */
  private getEmptyMeterData(): MeterCardData[] {
    return MeterTypeUtils.getAllTypes().map(type => ({
      type: MeterTypeUtils.getLabel(type),
      meterType: type,
      icon: MeterTypeUtils.getIcon(type),
      color: MeterTypeUtils.getColor(type),
      lastReading: 0,
      unit: MeterTypeUtils.getUnit(type),
      consumption: 0,
      consumptionPeriod: 'Keine Daten',
      trend: 'stable' as const,
      hasData: false
    }));
  }

  /**
   * Calculates trend based on consumption value
   * TODO: Enhance with historical comparison
   */
  private calculateTrend(consumption: number): 'up' | 'down' | 'stable' {
    if (consumption > 0) {
      return 'up'; // Has consumption
    }
    return 'stable'; // No data or zero consumption
  }

  /**
   * Gets the trend icon based on consumption trend
   */
  getTrendIcon(trend: string): string {
    switch (trend) {
      case 'up':
        return '↗️';
      case 'down':
        return '↘️';
      case 'stable':
        return '→';
      default:
        return '→';
    }
  }

  /**
   * Gets the trend CSS class based on consumption trend
   */
  getTrendClass(trend: string): string {
    return `trend--${trend}`;
  }

  /**
   * Formats number with German locale
   */
  formatNumber(value: number): string {
    return value.toLocaleString('de-DE', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    });
  }
}

/**
 * Interface for meter card data display
 */
interface MeterCardData {
  readonly type: string;
  readonly meterType: MeterType;
  readonly icon: string;
  readonly color: string;
  readonly lastReading: number;
  readonly unit: string;
  readonly consumption: number;
  readonly consumptionPeriod: string;
  readonly trend: 'up' | 'down' | 'stable';
  readonly hasData: boolean;
}
