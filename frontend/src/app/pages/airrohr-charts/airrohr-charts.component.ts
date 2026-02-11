import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { AirrohrService } from '../../services/airrohr.service';
import { AirrohrHistoryReading } from '../../models/airrohr.model';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface AirrohrChartPoint {
  readonly date: Date;
  readonly sdsP1: number | null;
  readonly sdsP2: number | null;
}

interface AirrohrChartSeries {
  readonly points: AirrohrChartPoint[];
}

@Component({
  selector: 'app-airrohr-charts',
  standalone: true,
  imports: [CommonModule, IconComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './airrohr-charts.component.html',
  styleUrl: './airrohr-charts.component.scss'
})
export class AirrohrChartsComponent implements OnInit {
  private readonly airrohrService = inject(AirrohrService);

  selectedYear: number | 'ALL' = 'ALL';
  selectedMonth: number | 'ALL' = 'ALL';
  compareMode = false;
  compareYearA: number | 'ALL' = 'ALL';
  compareMonthA: number | 'ALL' = 'ALL';
  compareYearB: number | 'ALL' = 'ALL';
  compareMonthB: number | 'ALL' = 'ALL';
  availableYears: number[] = [];
  availableMonths: number[] = [];

  singleChartOptions: Record<string, unknown> | null = null;
  compareChartOptionsA: Record<string, unknown> | null = null;
  compareChartOptionsB: Record<string, unknown> | null = null;

  isLoading = true;
  errorMessage: string | null = null;

  private rawPoints: AirrohrChartPoint[] = [];

  ngOnInit(): void {
    this.loadSeries();
  }

  setYear(year: string): void {
    if (year === 'ALL') {
      this.selectedYear = 'ALL';
      this.selectedMonth = 'ALL';
      this.availableMonths = [];
      this.refreshCharts();
      return;
    }
    const parsed = Number(year);
    this.selectedYear = Number.isNaN(parsed) ? 'ALL' : parsed;
    this.selectedMonth = 'ALL';
    this.updateAvailableMonths();
    this.refreshCharts();
  }

  setMonth(month: string): void {
    if (month === 'ALL') {
      this.selectedMonth = 'ALL';
    } else {
      const parsed = Number(month);
      this.selectedMonth = Number.isNaN(parsed) ? 'ALL' : parsed;
    }
    this.refreshCharts();
  }

  setCompareMode(enabled: boolean): void {
    this.compareMode = enabled;
    if (enabled) {
      this.compareYearA = this.selectedYear;
      this.compareMonthA = this.selectedMonth;
      this.compareYearB = this.availableYears[0] ?? 'ALL';
      this.compareMonthB = 'ALL';
    }
    this.refreshCharts();
  }

  setCompareYearA(year: string): void {
    this.compareYearA = this.parseYear(year);
    this.compareMonthA = 'ALL';
    this.refreshCharts();
  }

  setCompareYearB(year: string): void {
    this.compareYearB = this.parseYear(year);
    this.compareMonthB = 'ALL';
    this.refreshCharts();
  }

  setCompareMonthA(month: string): void {
    this.compareMonthA = this.parseMonth(month);
    this.refreshCharts();
  }

  setCompareMonthB(month: string): void {
    this.compareMonthB = this.parseMonth(month);
    this.refreshCharts();
  }

  get selectedSeries(): AirrohrChartSeries {
    return this.getSeriesFor(this.selectedYear, this.selectedMonth);
  }

  get compareSeriesA(): AirrohrChartSeries {
    return this.getSeriesFor(this.compareYearA, this.compareMonthA);
  }

  get compareSeriesB(): AirrohrChartSeries {
    return this.getSeriesFor(this.compareYearB, this.compareMonthB);
  }

  getAvailableMonthsFor(year: number | 'ALL'): number[] {
    if (year === 'ALL') {
      return [];
    }
    return Array.from(
      new Set(
        this.rawPoints
          .filter(point => point.date.getFullYear() === year)
          .map(point => point.date.getMonth() + 1)
      )
    ).sort((a, b) => a - b);
  }

  formatNumber(value: number): string {
    return value.toLocaleString('de-DE', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    });
  }

  private loadSeries(): void {
    this.isLoading = true;
    this.errorMessage = null;

    this.airrohrService.getReadings().subscribe({
      next: readings => {
        this.rawPoints = this.buildPoints(readings);
        this.updateAvailableYears();
        this.refreshCharts();
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Error loading Airrohr readings:', error);
        this.errorMessage = 'Fehler beim Laden der Airrohr-Daten. Bitte erneut versuchen.';
        this.isLoading = false;
      }
    });
  }

  private buildPoints(readings: AirrohrHistoryReading[]): AirrohrChartPoint[] {
    return [...readings]
      .sort((a, b) => a.readingTime.getTime() - b.readingTime.getTime())
      .map(reading => ({
        date: reading.readingTime,
        sdsP1: reading.sdsP1 ?? null,
        sdsP2: reading.sdsP2 ?? null
      }))
      .filter(point => point.sdsP1 != null || point.sdsP2 != null);
  }

  private updateAvailableYears(): void {
    const years = Array.from(new Set(this.rawPoints.map(point => point.date.getFullYear())))
      .sort((a, b) => b - a);
    this.availableYears = years;

    if (this.selectedYear !== 'ALL' && !years.includes(this.selectedYear)) {
      this.selectedYear = 'ALL';
    }
    if (this.selectedYear !== 'ALL') {
      this.updateAvailableMonths();
    } else {
      this.availableMonths = [];
    }
  }

  private updateAvailableMonths(): void {
    if (this.selectedYear === 'ALL') {
      this.availableMonths = [];
      return;
    }
    const months = this.getAvailableMonthsFor(this.selectedYear);
    this.availableMonths = months;
    if (this.selectedMonth !== 'ALL' && !months.includes(this.selectedMonth)) {
      this.selectedMonth = 'ALL';
    }
  }

  private refreshCharts(): void {
    const singleSeries = this.selectedSeries;
    this.singleChartOptions = singleSeries.points.length
      ? this.buildChartOptions(singleSeries, this.selectedYear !== 'ALL' && this.selectedMonth === 'ALL')
      : null;

    if (this.compareMode) {
      const seriesA = this.compareSeriesA;
      const seriesB = this.compareSeriesB;
      this.compareChartOptionsA = seriesA.points.length
        ? this.buildChartOptions(seriesA, this.compareYearA !== 'ALL' && this.compareMonthA === 'ALL')
        : null;
      this.compareChartOptionsB = seriesB.points.length
        ? this.buildChartOptions(seriesB, this.compareYearB !== 'ALL' && this.compareMonthB === 'ALL')
        : null;
    } else {
      this.compareChartOptionsA = null;
      this.compareChartOptionsB = null;
    }
  }

  private buildChartOptions(series: AirrohrChartSeries, useMonthTicks: boolean): Record<string, unknown> {
    const labels = series.points.map(point => this.formatDate(point.date));
    const monthLabels = series.points.map(point => this.formatMonth(point.date.getMonth() + 1));
    let lastMonthLabel = '';

    return {
      grid: {
        left: 56,
        right: 24,
        top: 24,
        bottom: 36,
        containLabel: false
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'line' }
      },
      xAxis: {
        type: 'category',
        data: labels,
        axisLine: {
          lineStyle: { color: 'rgba(51, 65, 85, 0.7)' }
        },
        axisTick: {
          alignWithLabel: true,
          length: 6,
          lineStyle: { color: 'rgba(51, 65, 85, 0.75)' }
        },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 11,
          fontWeight: 400,
          formatter: (value: string, index: number) => {
            if (!useMonthTicks) {
              return value;
            }
            const label = monthLabels[index] ?? '';
            if (label === lastMonthLabel) {
              return '';
            }
            lastMonthLabel = label;
            return label;
          }
        }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: {
          lineStyle: { color: '#e2e8f0', type: 'dashed' }
        },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 11,
          fontWeight: 400,
          formatter: (value: number) => `${this.formatNumber(value)} ug/m3`
        }
      },
      series: [
        {
          name: 'Feinstaub PM10',
          type: 'line',
          data: series.points.map(point => point.sdsP1),
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          connectNulls: true,
          lineStyle: {
            width: 2.5,
            color: '#0ea5e9'
          },
          itemStyle: {
            color: '#0ea5e9'
          }
        },
        {
          name: 'Feinstaub PM2.5',
          type: 'line',
          data: series.points.map(point => point.sdsP2),
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          connectNulls: true,
          lineStyle: {
            width: 2.5,
            color: '#14b8a6'
          },
          itemStyle: {
            color: '#14b8a6'
          }
        }
      ]
    };
  }

  private parseYear(value: string): number | 'ALL' {
    if (value === 'ALL') {
      return 'ALL';
    }
    const parsed = Number(value);
    return Number.isNaN(parsed) ? 'ALL' : parsed;
  }

  private parseMonth(value: string): number | 'ALL' {
    if (value === 'ALL') {
      return 'ALL';
    }
    const parsed = Number(value);
    return Number.isNaN(parsed) ? 'ALL' : parsed;
  }

  private formatDate(date: Date): string {
    return date.toLocaleDateString('de-DE', {
      day: '2-digit',
      month: '2-digit'
    });
  }

  private formatMonth(month: number): string {
    const labels = [
      'Jan', 'Feb', 'Maerz', 'Apr', 'Mai', 'Jun',
      'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'
    ];
    return labels[month - 1] ?? '';
  }

  private getSeriesFor(year: number | 'ALL', month: number | 'ALL'): AirrohrChartSeries {
    const points = this.filterByYearAndMonth(this.rawPoints, year, month);
    return { points };
  }

  private filterByYearAndMonth(
    points: AirrohrChartPoint[],
    year: number | 'ALL',
    month: number | 'ALL'
  ): AirrohrChartPoint[] {
    if (year === 'ALL') {
      return points;
    }
    const yearFiltered = points.filter(point => point.date.getFullYear() === year);
    if (month === 'ALL') {
      return yearFiltered;
    }
    return yearFiltered.filter(point => point.date.getMonth() + 1 === month);
  }
}
