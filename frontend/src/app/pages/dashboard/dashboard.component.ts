import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, forkJoin, interval, startWith, switchMap } from 'rxjs';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { WeatherWidgetComponent } from '../../components/weather-widget/weather-widget.component';
import { MeterReadingService } from '../../services/meter-reading.service';
import { TasmotaLiveService } from '../../services/tasmota-live.service';
import { AirrohrService } from '../../services/airrohr.service';
import { UtilityPriceService } from '../../services/utility-price.service';
import { TasmotaLiveReading } from '../../models/tasmota-live.model';
import { AirrohrReading } from '../../models/airrohr.model';
import { UtilityPrice } from '../../models/utility-price.model';
import { MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Dashboard component - main application dashboard.
 * Displays overview of household utilities and meter readings.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, IconComponent, WeatherWidgetComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly meterReadingService = inject(MeterReadingService);
  private readonly utilityPriceService = inject(UtilityPriceService);
  private readonly liveService = inject(TasmotaLiveService);
  private readonly airrohrService = inject(AirrohrService);
  private liveSubscription?: Subscription;
  private statusSubscription?: Subscription;
  private airrohrSubscription?: Subscription;

  private static readonly GAS_ZUSTANDSZAHL = 0.95;
  private static readonly GAS_BRENNWERT = 11.5;

  /** Meter reading data for display */
  meterData: MeterCardData[] = [];

  /** Loading state */
  isLoading = true;

  /** Error message */
  errorMessage: string | null = null;
  liveReading: TasmotaLiveReading | null = null;
  liveError: string | null = null;
  liveStatus: 'disconnected' | 'connecting' | 'connected' | 'error' = 'disconnected';
  lastLiveUpdate: Date | null = null;
  isReconnecting = false;
  airrohrReading: AirrohrReading | null = null;
  airrohrError: string | null = null;
  lastAirrohrUpdate: Date | null = null;

  ngOnInit(): void {
    this.loadMeterData();
    this.startLiveStream();
    this.startAirrohrPolling();
  }

  ngOnDestroy(): void {
    this.liveSubscription?.unsubscribe();
    this.statusSubscription?.unsubscribe();
    this.airrohrSubscription?.unsubscribe();
    this.liveService.disconnect();
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
        readings: this.meterReadingService.getReadingsByType(type),
        currentPrice: this.loadCurrentPrice(type)
      })
    );

    forkJoin(requests).subscribe({
      next: (results) => {
        this.meterData = results.map(result => {
          const type = result.type;
          const latest = result.latestReading;
          const readings = result.readings ?? [];
          const currentPrice = result.currentPrice;
          const currentWeekConsumption = this.calculateWeeklyConsumption(readings, 0);
          const previousWeekConsumption = this.calculateWeeklyConsumption(readings, 1);
          const consumptionLast7Days = currentWeekConsumption ?? 0;
          const consumptionChange = this.calculateWeeklyChange(
            currentWeekConsumption,
            previousWeekConsumption
          );
          const consumptionChangePercent = this.calculateWeeklyChangePercent(
            currentWeekConsumption,
            previousWeekConsumption
          );
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
            consumptionPeriod: currentWeekConsumption != null ? 'Letzte 7 Tage' : 'Keine Daten',
            costLast7Days,
            trend: this.calculateTrend(consumptionChange),
            previousWeekConsumption,
            consumptionChange,
            consumptionChangePercent,
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
      previousWeekConsumption: null,
      consumptionChange: null,
      consumptionChangePercent: null,
      hasData: false
    }));
  }

  /**
   * Calculates trend based on consumption value
   * TODO: Enhance with historical comparison
   */
  private calculateTrend(consumptionChange: number | null): 'up' | 'down' | 'stable' {
    if (consumptionChange == null || Number.isNaN(consumptionChange)) {
      return 'stable';
    }
    if (Math.abs(consumptionChange) < 0.01) {
      return 'stable';
    }
    return consumptionChange > 0 ? 'up' : 'down';
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

  formatSigned(value: number | null, fractionDigits = 1): string {
    if (value == null || Number.isNaN(value)) {
      return 'â€”';
    }
    const sign = value > 0 ? '+' : value < 0 ? '-' : '';
    const absValue = Math.abs(value);
    return `${sign}${absValue.toLocaleString('de-DE', {
      minimumFractionDigits: 0,
      maximumFractionDigits: fractionDigits
    })}`;
  }

  formatSignedPercent(value: number | null, fractionDigits = 1): string {
    if (value == null || Number.isNaN(value)) {
      return 'â€”';
    }
    const sign = value > 0 ? '+' : value < 0 ? '-' : '';
    const absValue = Math.abs(value);
    return `${sign}${absValue.toLocaleString('de-DE', {
      minimumFractionDigits: 0,
      maximumFractionDigits: fractionDigits
    })}%`;
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

  formatDateTime(value: string | null): string {
    if (!value) {
      return '-';
    }
    return new Date(value).toLocaleString('de-DE');
  }

  formatPower(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
      return '-';
    }
    return `${value.toLocaleString('de-DE', { maximumFractionDigits: 1 })} W`;
  }

  formatDust(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
      return '-';
    }
    return `${value.toLocaleString('de-DE', { maximumFractionDigits: 2 })} ug/m3`;
  }

  getPm10LevelClass(value: number | null | undefined): string {
    return this.getDustLevelClass(value, 20, 40);
  }

  getPm25LevelClass(value: number | null | undefined): string {
    return this.getDustLevelClass(value, 12, 25);
  }

  formatLiveStatus(): string {
    switch (this.liveStatus) {
      case 'connected':
        return 'Verbunden';
      case 'connecting':
        return 'Verbinde...';
      case 'error':
        return 'Fehler';
      default:
        return 'Getrennt';
    }
  }

  reconnectLive(): void {
    this.isReconnecting = true;
    this.liveService.reconnect();
    setTimeout(() => {
      this.isReconnecting = false;
    }, 600);
  }

  private loadCurrentPrice(type: MeterType) {
    if (type !== MeterType.ELECTRICITY && type !== MeterType.GAS) {
      return [null as UtilityPrice | null];
    }
    return this.utilityPriceService.getCurrentPrice(type);
  }

  private calculateWeeklyConsumption(readings: { readingValue: number; readingDate: Date }[], offset: number): number | null {
    if (!readings || readings.length <= offset + 1) {
      return null;
    }
    const current = readings[offset];
    const previous = readings[offset + 1];
    if (!current?.readingDate || !previous?.readingDate) {
      return null;
    }
    const daysBetween = this.calculateDaysBetween(previous.readingDate, current.readingDate);
    if (daysBetween <= 0) {
      return null;
    }
    const consumption = current.readingValue - previous.readingValue;
    const averageDaily = consumption / daysBetween;
    return averageDaily * 7;
  }

  private calculateWeeklyChange(
    currentWeekConsumption: number | null,
    previousWeekConsumption: number | null
  ): number | null {
    if (currentWeekConsumption == null || previousWeekConsumption == null) {
      return null;
    }
    return currentWeekConsumption - previousWeekConsumption;
  }

  private calculateWeeklyChangePercent(
    currentWeekConsumption: number | null,
    previousWeekConsumption: number | null
  ): number | null {
    if (currentWeekConsumption == null || previousWeekConsumption == null) {
      return null;
    }
    if (previousWeekConsumption === 0) {
      return null;
    }
    return ((currentWeekConsumption - previousWeekConsumption) / previousWeekConsumption) * 100;
  }

  private calculateDaysBetween(start: Date, end: Date): number {
    const msPerDay = 24 * 60 * 60 * 1000;
    return Math.floor((end.getTime() - start.getTime()) / msPerDay);
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

  private startLiveStream(): void {
    this.liveSubscription?.unsubscribe();
    this.statusSubscription?.unsubscribe();
    this.liveError = null;
    this.liveSubscription = this.liveService.getLiveStream().subscribe({
      next: (reading) => {
        this.liveReading = reading;
        this.lastLiveUpdate = new Date();
        this.liveError = null;
      },
      error: () => {
        this.liveError = 'Live-Stream nicht verfuegbar.';
      }
    });

    this.statusSubscription = this.liveService.getStatusStream().subscribe({
      next: (status) => {
        this.liveStatus = status;
      }
    });
  }

  private startAirrohrPolling(): void {
    this.airrohrSubscription?.unsubscribe();
    this.airrohrError = null;
    this.airrohrSubscription = interval(15000).pipe(
      startWith(0),
      switchMap(() => this.airrohrService.getCurrentReading())
    ).subscribe({
      next: (reading) => {
        this.airrohrReading = reading;
        this.lastAirrohrUpdate = new Date();
        this.airrohrError = null;
      },
      error: () => {
        this.airrohrError = 'Airrohr-Daten nicht verfuegbar.';
      }
    });
  }

  private getDustLevelClass(
    value: number | null | undefined,
    orangeThreshold: number,
    redThreshold: number
  ): string {
    if (value == null || Number.isNaN(value)) {
      return 'live-card__metric--neutral';
    }
    if (value <= orangeThreshold) {
      return 'live-card__metric--good';
    }
    if (value <= redThreshold) {
      return 'live-card__metric--warn';
    }
    return 'live-card__metric--bad';
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
  readonly previousWeekConsumption: number | null;
  readonly consumptionChange: number | null;
  readonly consumptionChangePercent: number | null;
  readonly hasData: boolean;
}
