import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import {
  GridComponent,
  TooltipComponent
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { MeterReadingService } from '../../services/meter-reading.service';
import { MeterReading, MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface ChartPoint {
  readonly date: Date;
  readonly value: number;
  readonly label: string;
}

interface ChartSeries {
  readonly points: ChartPoint[];
  readonly unit: string;
  readonly maxValue: number;
  readonly minValue: number;
}

interface XTick {
  readonly index: number;
  readonly label: string;
}

@Component({
  selector: 'app-consumption-charts',
  standalone: true,
  imports: [CommonModule, IconComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './consumption-charts.component.html',
  styleUrl: './consumption-charts.component.scss'
})
export class ConsumptionChartsComponent implements OnInit {
  private readonly meterReadingService = inject(MeterReadingService);

  readonly chartWidth = 700;
  readonly chartHeight = 260;
  readonly chartPadding = 28;

  readonly MeterTypeUtils = MeterTypeUtils;
  readonly meterTypes = MeterTypeUtils.getAllTypes();
  selectedType: MeterType = MeterType.ELECTRICITY;
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

  private readonly seriesByType = new Map<MeterType, ChartSeries>();
  private readonly rawPointsByType = new Map<MeterType, ChartPoint[]>();
  private readonly readingYearsByType = new Map<MeterType, number[]>();

  ngOnInit(): void {
    this.loadAllSeries();
  }

  selectType(type: MeterType): void {
    this.selectedType = type;
    this.updateAvailableYears();
    this.refreshCharts();
  }

  getSelectedSeries(): ChartSeries | null {
    return this.getSeriesFor(this.selectedYear, this.selectedMonth);
  }

  get selectedSeries(): ChartSeries | null {
    return this.getSelectedSeries();
  }

  setYear(year: string): void {
    if (year === 'ALL') {
      this.selectedYear = 'ALL';
      this.selectedMonth = 'ALL';
      this.availableMonths = [];
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
      return;
    }
    const parsed = Number(month);
    this.selectedMonth = Number.isNaN(parsed) ? 'ALL' : parsed;
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

  getCompareSeriesA(): ChartSeries | null {
    return this.getSeriesFor(this.compareYearA, this.compareMonthA);
  }

  getCompareSeriesB(): ChartSeries | null {
    return this.getSeriesFor(this.compareYearB, this.compareMonthB);
  }

  get compareSeriesA(): ChartSeries | null {
    return this.getCompareSeriesA();
  }

  get compareSeriesB(): ChartSeries | null {
    return this.getCompareSeriesB();
  }

  get compareSeriesAValue(): ChartSeries {
    return this.getCompareSeriesA() ?? {
      points: [],
      unit: this.getSelectedUnit(),
      maxValue: 0,
      minValue: 0
    };
  }

  get compareSeriesBValue(): ChartSeries {
    return this.getCompareSeriesB() ?? {
      points: [],
      unit: this.getSelectedUnit(),
      maxValue: 0,
      minValue: 0
    };
  }

  getSelectedLabel(): string {
    return MeterTypeUtils.getLabel(this.selectedType);
  }

  getSelectedUnit(): string {
    return MeterTypeUtils.getUnit(this.selectedType);
  }

  getColor(): string {
    return MeterTypeUtils.getColor(this.selectedType);
  }

  getIconName(): string {
    return MeterTypeUtils.getIconName(this.selectedType);
  }

  formatNumber(value: number): string {
    return value.toLocaleString('de-DE', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    });
  }

  formatDate(date: Date): string {
    return date.toLocaleDateString('de-DE', {
      day: '2-digit',
      month: '2-digit'
    });
  }

  getPolyline(points: ChartPoint[]): string {
    if (points.length === 0) {
      return '';
    }
    const width = this.chartWidth - this.chartPadding * 2;
    const height = this.chartHeight - this.chartPadding * 2;
    const minValue = Math.min(...points.map(p => p.value));
    const maxValue = Math.max(...points.map(p => p.value));
    const range = maxValue - minValue || 1;

    return points.map((point, index) => {
      const x = points.length === 1
        ? this.chartWidth / 2
        : this.chartPadding + (index / (points.length - 1)) * width;
      const y = this.chartPadding + (1 - (point.value - minValue) / range) * height;
      return `${x},${y}`;
    }).join(' ');
  }

  getAreaPath(points: ChartPoint[]): string {
    if (points.length === 0) {
      return '';
    }
    const polyline = this.getPolyline(points);
    const first = polyline.split(' ')[0];
    const last = polyline.split(' ').at(-1);
    if (!first || !last) {
      return '';
    }
    const lastX = last.split(',')[0];
    const firstX = first.split(',')[0];
    const baseline = this.chartHeight - this.chartPadding;
    return `M${first} L${polyline.replace(/ /g, ' L')} L${lastX},${baseline} L${firstX},${baseline} Z`;
  }

  getPointX(index: number, total: number): number {
    const width = this.chartWidth - this.chartPadding * 2;
    if (total <= 1) {
      return this.chartWidth / 2;
    }
    return this.chartPadding + (index / (total - 1)) * width;
  }

  getPointY(value: number, minValue: number, maxValue: number): number {
    const height = this.chartHeight - this.chartPadding * 2;
    const range = maxValue - minValue || 1;
    return this.chartPadding + (1 - (value - minValue) / range) * height;
  }

  getGridLines(): number[] {
    return [0, 1, 2, 3, 4];
  }

  getGridY(line: number): number {
    const height = this.chartHeight - this.chartPadding * 2;
    return this.chartPadding + (line / 4) * height;
  }

  getGridValue(line: number, series: ChartSeries): number {
    const range = series.maxValue - series.minValue || 1;
    return series.maxValue - (line / 4) * range;
  }

  getXTicks(points: ChartPoint[]): number[] {
    const count = points.length;
    if (count <= 1) {
      return count === 1 ? [0] : [];
    }
    if (count <= 4) {
      return Array.from({ length: count }, (_, i) => i);
    }
    const middle = Math.floor((count - 1) / 2);
    return [0, middle, count - 1];
  }

  getMonthTicks(points: ChartPoint[]): XTick[] {
    if (points.length === 0) {
      return [];
    }
    const monthToIndex = new Map<number, number>();
    points.forEach((point, index) => {
      const month = point.date.getMonth() + 1;
      if (!monthToIndex.has(month)) {
        monthToIndex.set(month, index);
      }
    });
    return Array.from(monthToIndex.entries())
      .sort((a, b) => a[0] - b[0])
      .map(([month, index]) => ({
        index,
        label: this.formatMonth(month)
      }));
  }

  private formatMonth(month: number): string {
    const labels = [
      'Jan', 'Feb', 'Maerz', 'Apr', 'Mai', 'Jun',
      'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'
    ];
    return labels[month - 1] ?? '';
  }

  getTickLabels(points: ChartPoint[], useMonths: boolean): { left: number; label: string }[] {
    const total = points.length;
    if (total === 0) {
      return [];
    }
    const ticks = useMonths
      ? this.getMonthTicks(points).map(tick => ({ index: tick.index, label: tick.label }))
      : this.getXTicks(points).map(index => ({
        index,
        label: this.formatDate(points[index].date)
      }));

    return ticks.map(tick => ({
      left: total <= 1 ? 50 : (tick.index / (total - 1)) * 100,
      label: tick.label
    }));
  }

  private loadAllSeries(): void {
    this.isLoading = true;
    this.errorMessage = null;

    const requests = this.meterTypes.map(type =>
      this.meterReadingService.getReadingsByType(type)
    );

    let completed = 0;
    requests.forEach((request, index) => {
      const type = this.meterTypes[index];
      request.subscribe({
        next: readings => {
          const series = this.buildSeries(readings, type);
          this.seriesByType.set(type, series);
          this.rawPointsByType.set(type, series.points);
          this.readingYearsByType.set(
            type,
            Array.from(new Set(readings.map(reading => reading.readingDate.getFullYear())))
          );
          completed += 1;
          if (completed === requests.length) {
            this.isLoading = false;
            this.updateAvailableYears();
            this.refreshCharts();
          }
        },
        error: (error: Error) => {
          console.error('Error loading readings:', error);
          this.errorMessage = 'Fehler beim Laden der Verbrauchsdaten. Bitte erneut versuchen.';
          this.isLoading = false;
        }
      });
    });
  }

  private buildSeries(readings: MeterReading[], type: MeterType): ChartSeries {
    const sorted = [...readings].sort((a, b) => a.readingDate.getTime() - b.readingDate.getTime());
    const rawPoints: ChartPoint[] = [];

    for (let i = 1; i < sorted.length; i += 1) {
      const prev = sorted[i - 1];
      const current = sorted[i];
      const consumption = Math.max(0, current.readingValue - prev.readingValue);
      rawPoints.push({
        date: current.readingDate,
        value: consumption,
        label: `KW ${current.readingWeek ?? '-'}`
      });
    }

    const points = this.filterOutliers(rawPoints);

    const values = points.map(point => point.value);
    const maxValue = values.length > 0 ? Math.max(...values) : 0;
    const minValue = values.length > 0 ? Math.min(...values) : 0;

    return {
      points,
      unit: MeterTypeUtils.getUnit(type),
      maxValue,
      minValue
    };
  }

  private updateAvailableYears(): void {
    const years = (this.readingYearsByType.get(this.selectedType) ?? [])
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
    const singleSeries = this.getSelectedSeries();
    this.singleChartOptions = singleSeries
      ? this.buildChartOptions(
          singleSeries,
          this.selectedYear !== 'ALL' && this.selectedMonth === 'ALL'
        )
      : null;

    if (this.compareMode) {
      const seriesA = this.getCompareSeriesA();
      const seriesB = this.getCompareSeriesB();
      this.compareChartOptionsA = seriesA
        ? this.buildChartOptions(
            seriesA,
            this.compareYearA !== 'ALL' && this.compareMonthA === 'ALL'
          )
        : null;
      this.compareChartOptionsB = seriesB
        ? this.buildChartOptions(
            seriesB,
            this.compareYearB !== 'ALL' && this.compareMonthB === 'ALL'
          )
        : null;
    } else {
      this.compareChartOptionsA = null;
      this.compareChartOptionsB = null;
    }
  }

  private buildChartOptions(series: ChartSeries, useMonthTicks: boolean): Record<string, unknown> {
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
          formatter: (value: number) => `${this.formatNumber(value)} ${series.unit}`
        }
      },
      series: [
        {
          type: 'line',
          data: series.points.map(point => point.value),
          smooth: true,
          symbol: 'circle',
          symbolSize: 9,
          lineStyle: {
            width: 3.5,
            color: this.getColor()
          },
          itemStyle: {
            color: this.getColor(),
            borderColor: '#ffffff',
            borderWidth: 2
          },
          areaStyle: {
            color: this.getColor(),
            opacity: 0.18
          }
        }
      ]
    };
  }

  getAvailableMonthsFor(year: number | 'ALL'): number[] {
    if (year === 'ALL') {
      return [];
    }
    const points = this.rawPointsByType.get(this.selectedType) ?? [];
    return Array.from(
      new Set(
        points
          .filter(point => point.date.getFullYear() === year)
          .map(point => point.date.getMonth() + 1)
      )
    ).sort((a, b) => a - b);
  }

  private filterByYearAndMonth(
    points: ChartPoint[],
    year: number | 'ALL',
    month: number | 'ALL'
  ): ChartPoint[] {
    if (year === 'ALL') {
      return points;
    }
    const yearFiltered = points.filter(point => point.date.getFullYear() === year);
    if (month === 'ALL') {
      return yearFiltered;
    }
    return yearFiltered.filter(point => point.date.getMonth() + 1 === month);
  }

  private getSeriesFor(
    year: number | 'ALL',
    month: number | 'ALL'
  ): ChartSeries | null {
    const baseSeries = this.seriesByType.get(this.selectedType);
    if (!baseSeries) {
      return null;
    }
    const rawPoints = this.rawPointsByType.get(this.selectedType) ?? baseSeries.points;
    const filteredPoints = this.filterByYearAndMonth(rawPoints, year, month);
    const points = this.filterOutliers(filteredPoints);
    const values = points.map(point => point.value);
    const maxValue = values.length > 0 ? Math.max(...values) : 0;
    const minValue = values.length > 0 ? Math.min(...values) : 0;
    return {
      points,
      unit: baseSeries.unit,
      maxValue,
      minValue
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

  private filterOutliers(points: ChartPoint[]): ChartPoint[] {
    if (points.length < 3) {
      return points;
    }

    const nonZeroValues = points.map(point => point.value).filter(value => value > 0);
    const mean = nonZeroValues.length
      ? nonZeroValues.reduce((sum, value) => sum + value, 0) / nonZeroValues.length
      : 0;

    return points.filter((point, index) => {
      const nextValue = index < points.length - 1 ? points[index + 1].value : null;
      if (nextValue !== 0) {
        return true;
      }
      if (point.value <= 0) {
        return true;
      }
      if (mean === 0) {
        return false;
      }
      return point.value <= mean * 5;
    });
  }
}
