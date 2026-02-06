import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { MeterReadingService } from '../../services/meter-reading.service';
import { UtilityPriceService } from '../../services/utility-price.service';
import { UtilityPrice } from '../../models/utility-price.model';
import { MeterType, ConsumptionResponse } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Dashboard component - main application dashboard.
 * Displays overview of household utilities and meter readings.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {
  private readonly meterReadingService = inject(MeterReadingService);
  private readonly utilityPriceService = inject(UtilityPriceService);

  private static readonly GAS_ZUSTANDSZAHL = 0.95;
  private static readonly GAS_BRENNWERT = 11.5;

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
        consumption: this.meterReadingService.getConsumptionStats(type),
        currentPrice: this.loadCurrentPrice(type)
      })
    );

    forkJoin(requests).subscribe({
      next: (results) => {
        this.meterData = results.map(result => {
          const type = result.type;
          const latest = result.latestReading;
          const consumption = result.consumption;
          const currentPrice = result.currentPrice;
          const consumptionLast7Days = this.calculateConsumptionLast7Days(consumption);
          const pricePerUnit = currentPrice?.price ?? null;
          const costLast7Days = this.calculateCostLast7Days(
            type,
            consumptionLast7Days,
            pricePerUnit
          );

          return {
            type: MeterTypeUtils.getLabel(type),
            meterType: type,
            iconName: MeterTypeUtils.getIconName(type),
            color: MeterTypeUtils.getColor(type),
            lastReading: latest?.readingValue || 0,
            unit: MeterTypeUtils.getUnit(type),
            consumption: consumptionLast7Days,
            consumptionPeriod: consumption ? 'Letzte 7 Tage' : 'Keine Daten',
            costLast7Days,
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
      iconName: MeterTypeUtils.getIconName(type),
      color: MeterTypeUtils.getColor(type),
      lastReading: 0,
      unit: MeterTypeUtils.getUnit(type),
      consumption: 0,
      consumptionPeriod: 'Keine Daten',
      costLast7Days: null,
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
   * Gets the Lucide icon name for trend
   */
  getTrendIconName(trend: string): string {
    switch (trend) {
      case 'up':
        return 'trending-up';
      case 'down':
        return 'trending-down';
      case 'stable':
        return 'minus';
      default:
        return 'minus';
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

  /**
   * Formats a currency value with 2 decimals
   */
  formatCurrency(value: number | null): string {
    if (value === null || Number.isNaN(value)) {
      return '—';
    }
    return new Intl.NumberFormat('de-DE', {
      style: 'currency',
      currency: 'EUR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(value);
  }

  private loadCurrentPrice(type: MeterType) {
    if (type !== MeterType.ELECTRICITY && type !== MeterType.GAS) {
      return [null as UtilityPrice | null];
    }
    return this.utilityPriceService.getCurrentPrice(type);
  }

  private calculateConsumptionLast7Days(consumption: ConsumptionResponse | null): number {
    if (!consumption || consumption.averageDailyConsumption == null) {
      return 0;
    }
    return Number(consumption.averageDailyConsumption) * 7;
  }

  private calculateCostLast7Days(
    type: MeterType,
    consumptionLast7Days: number,
    pricePerUnit: number | null
  ): number | null {
    if (pricePerUnit == null) {
      return null;
    }
    if (consumptionLast7Days <= 0) {
      return 0;
    }

    const consumptionForPricing = type === MeterType.GAS
      ? consumptionLast7Days * DashboardComponent.GAS_ZUSTANDSZAHL * DashboardComponent.GAS_BRENNWERT
      : consumptionLast7Days;

    return consumptionForPricing * pricePerUnit;
  }
}

/**
 * Interface for meter card data display
 */
interface MeterCardData {
  readonly type: string;
  readonly meterType: MeterType;
  readonly iconName: string;
  readonly color: string;
  readonly lastReading: number;
  readonly unit: string;
  readonly consumption: number;
  readonly consumptionPeriod: string;
  readonly costLast7Days: number | null;
  readonly trend: 'up' | 'down' | 'stable';
  readonly hasData: boolean;
}
