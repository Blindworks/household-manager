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
  selectedDay: number | 'ALL' = 'ALL';
  compareMode = false;
  compareYearA: number | 'ALL' = 'ALL';
  compareMonthA: number | 'ALL' = 'ALL';
  compareDayA: number | 'ALL' = 'ALL';
  compareYearB: number | 'ALL' = 'ALL';
  compareMonthB: number | 'ALL' = 'ALL';
  compareDayB: number | 'ALL' = 'ALL';
  availableYears: number[] = [];
  availableMonths: number[] = [];
  availableDays: number[] = [];

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
      this.selectedDay = 'ALL';
      this.availableMonths = [];
      this.availableDays = [];
      this.refreshCharts();
      return;
    }
    const parsed = Number(year);
    this.selectedYear = Number.isNaN(parsed) ? 'ALL' : parsed;
    this.selectedMonth = 'ALL';
    this.selectedDay = 'ALL';
    this.availableDays = [];
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
    this.selectedDay = 'ALL';
    this.availableDays = this.getAvailableDaysFor(this.selectedYear, this.selectedMonth);
    this.refreshCharts();
  }

  setDay(day: string): void {
    if (day === 'ALL') {
      this.selectedDay = 'ALL';
    } else {
      const parsed = Number(day);
      this.selectedDay = Number.isNaN(parsed) ? 'ALL' : parsed;
    }
    this.refreshCharts();
  }

  setCompareMode(enabled: boolean): void {
    this.compareMode = enabled;
    if (enabled) {
      this.compareYearA = this.selectedYear;
      this.compareMonthA = this.selectedMonth;
      this.compareDayA = 'ALL';
      this.compareYearB = this.availableYears[0] ?? 'ALL';
      this.compareMonthB = 'ALL';
      this.compareDayB = 'ALL';
    }
    this.refreshCharts();
  }

  setCompareYearA(year: string): void {
    this.compareYearA = this.parseYear(year);
    this.compareMonthA = 'ALL';
    this.compareDayA = 'ALL';
    this.refreshCharts();
  }

  setCompareYearB(year: string): void {
    this.compareYearB = this.parseYear(year);
    this.compareMonthB = 'ALL';
    this.compareDayB = 'ALL';
    this.refreshCharts();
  }

  setCompareMonthA(month: string): void {
    this.compareMonthA = this.parseMonth(month);
    this.compareDayA = 'ALL';
    this.refreshCharts();
  }

  setCompareMonthB(month: string): void {
    this.compareMonthB = this.parseMonth(month);
    this.compareDayB = 'ALL';
    this.refreshCharts();
  }

  setCompareDayA(day: string): void {
    if (day === 'ALL') {
      this.compareDayA = 'ALL';
    } else {
      const parsed = Number(day);
      this.compareDayA = Number.isNaN(parsed) ? 'ALL' : parsed;
    }
    this.refreshCharts();
  }

  setCompareDayB(day: string): void {
    if (day === 'ALL') {
      this.compareDayB = 'ALL';
    } else {
      const parsed = Number(day);
      this.compareDayB = Number.isNaN(parsed) ? 'ALL' : parsed;
    }
    this.refreshCharts();
  }

  get selectedSeries(): AirrohrChartSeries {
    return this.getSeriesFor(this.selectedYear, this.selectedMonth, this.selectedDay);
  }

  get compareSeriesA(): AirrohrChartSeries {
    return this.getSeriesFor(this.compareYearA, this.compareMonthA, this.compareDayA);
  }

  get compareSeriesB(): AirrohrChartSeries {
    return this.getSeriesFor(this.compareYearB, this.compareMonthB, this.compareDayB);
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

  getAvailableDaysFor(year: number | 'ALL', month: number | 'ALL'): number[] {
    if (year === 'ALL' || month === 'ALL') return [];
    return Array.from(
      new Set(
        this.rawPoints
          .filter(p => p.date.getFullYear() === year && p.date.getMonth() + 1 === month)
          .map(p => p.date.getDate())
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
        this.applyDefaultSelection();
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

  /**
   * Preselect the current month so the initial view is scoped to it instead of
   * showing the entire history. Falls back to the most recent month with data.
   */
  private applyDefaultSelection(): void {
    if (!this.rawPoints.length) {
      return;
    }
    const now = new Date();
    const currentYear = now.getFullYear();
    const currentMonth = now.getMonth() + 1;
    const hasCurrentMonth = this.rawPoints.some(
      point => point.date.getFullYear() === currentYear && point.date.getMonth() + 1 === currentMonth
    );
    if (hasCurrentMonth) {
      this.selectedYear = currentYear;
      this.selectedMonth = currentMonth;
      return;
    }
    const latest = this.rawPoints.reduce((a, b) => (a.date.getTime() >= b.date.getTime() ? a : b));
    this.selectedYear = latest.date.getFullYear();
    this.selectedMonth = latest.date.getMonth() + 1;
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
      this.availableDays = [];
    }
  }

  private updateAvailableMonths(): void {
    if (this.selectedYear === 'ALL') {
      this.availableMonths = [];
      this.availableDays = [];
      return;
    }
    const months = this.getAvailableMonthsFor(this.selectedYear);
    this.availableMonths = months;
    if (this.selectedMonth !== 'ALL' && !months.includes(this.selectedMonth)) {
      this.selectedMonth = 'ALL';
    }
    this.availableDays = this.getAvailableDaysFor(this.selectedYear, this.selectedMonth);
  }

  private refreshCharts(): void {
    const isDayView = this.selectedYear !== 'ALL' && this.selectedMonth !== 'ALL' && this.selectedDay !== 'ALL';
    const isMonthView = this.selectedYear !== 'ALL' && this.selectedMonth !== 'ALL' && this.selectedDay === 'ALL';
    const singleSeries = this.getSeriesFor(this.selectedYear, this.selectedMonth, this.selectedDay);
    const useMonthTicks = this.selectedYear !== 'ALL' && this.selectedMonth === 'ALL';
    const monthContext = isMonthView
      ? { year: this.selectedYear as number, month: this.selectedMonth as number }
      : undefined;
    this.singleChartOptions = singleSeries.points.length
      ? this.buildChartOptions(singleSeries, useMonthTicks, isDayView, monthContext)
      : null;

    if (this.compareMode) {
      const seriesA = this.getSeriesFor(this.compareYearA, this.compareMonthA, this.compareDayA);
      const seriesB = this.getSeriesFor(this.compareYearB, this.compareMonthB, this.compareDayB);
      const isDayViewA = this.compareYearA !== 'ALL' && this.compareMonthA !== 'ALL' && this.compareDayA !== 'ALL';
      const isDayViewB = this.compareYearB !== 'ALL' && this.compareMonthB !== 'ALL' && this.compareDayB !== 'ALL';
      const isMonthViewA = this.compareYearA !== 'ALL' && this.compareMonthA !== 'ALL' && this.compareDayA === 'ALL';
      const isMonthViewB = this.compareYearB !== 'ALL' && this.compareMonthB !== 'ALL' && this.compareDayB === 'ALL';
      const monthContextA = isMonthViewA ? { year: this.compareYearA as number, month: this.compareMonthA as number } : undefined;
      const monthContextB = isMonthViewB ? { year: this.compareYearB as number, month: this.compareMonthB as number } : undefined;
      this.compareChartOptionsA = seriesA.points.length
        ? this.buildChartOptions(seriesA, this.compareYearA !== 'ALL' && this.compareMonthA === 'ALL', isDayViewA, monthContextA)
        : null;
      this.compareChartOptionsB = seriesB.points.length
        ? this.buildChartOptions(seriesB, this.compareYearB !== 'ALL' && this.compareMonthB === 'ALL', isDayViewB, monthContextB)
        : null;
    } else {
      this.compareChartOptionsA = null;
      this.compareChartOptionsB = null;
    }
  }

  private buildChartOptions(
    series: AirrohrChartSeries,
    useMonthTicks: boolean,
    isDayView = false,
    monthContext?: { year: number; month: number }
  ): Record<string, unknown> {
    if (monthContext) {
      const { year, month } = monthContext;
      const daysInMonth = new Date(year, month, 0).getDate();
      const dailyData = this.aggregateByDay(series.points, year, month);
      const dayLabels = Array.from({ length: daysInMonth }, (_, i) => {
        const d = String(i + 1).padStart(2, '0');
        const m = String(month).padStart(2, '0');
        return `${d}.${m}`;
      });

      return {
        grid: { left: 56, right: 24, top: 24, bottom: 36, containLabel: false },
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'line' },
          formatter: (params: { axisValue: string; seriesName: string; value: number | null }[]) => {
            const day = params[0]?.axisValue ?? '';
            const lines = params
              .filter(p => p.value != null)
              .map(p => `${p.seriesName}: ${this.formatNumber(p.value as number)} ug/m3`);
            return lines.length ? `${day}<br/>${lines.join('<br/>')}` : '';
          }
        },
        xAxis: {
          type: 'category',
          data: dayLabels,
          axisLine: { lineStyle: { color: 'rgba(51, 65, 85, 0.7)' } },
          axisTick: { alignWithLabel: true, length: 6, lineStyle: { color: 'rgba(51, 65, 85, 0.75)' } },
          axisLabel: { color: '#94a3b8', fontSize: 11, fontWeight: 400 }
        },
        yAxis: {
          type: 'value',
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
          axisLabel: {
            color: '#94a3b8', fontSize: 11, fontWeight: 400,
            formatter: (value: number) => `${this.formatNumber(value)} ug/m3`
          }
        },
        series: [
          {
            name: 'Feinstaub PM10',
            type: 'line',
            data: dailyData.map(d => d.sdsP1),
            smooth: true, symbol: 'circle', symbolSize: 7, connectNulls: false,
            lineStyle: { width: 2.5, color: '#0ea5e9' },
            itemStyle: { color: '#0ea5e9' }
          },
          {
            name: 'Feinstaub PM2.5',
            type: 'line',
            data: dailyData.map(d => d.sdsP2),
            smooth: true, symbol: 'circle', symbolSize: 7, connectNulls: false,
            lineStyle: { width: 2.5, color: '#14b8a6' },
            itemStyle: { color: '#14b8a6' }
          }
        ]
      };
    }

    if (isDayView) {
      const hourlyData = this.aggregateByHour(series.points);
      const hourLabels = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));

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
          axisPointer: { type: 'line' },
          formatter: (params: { axisValue: string; seriesName: string; value: number | null }[]) => {
            const hour = params[0]?.axisValue ?? '';
            const lines = params
              .filter(p => p.value != null)
              .map(p => `${p.seriesName}: ${this.formatNumber(p.value as number)} ug/m3`);
            return lines.length ? `${hour}:00 Uhr<br/>${lines.join('<br/>')}` : '';
          }
        },
        xAxis: {
          type: 'category',
          data: hourLabels,
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
            fontWeight: 400
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
            data: hourlyData.map(h => h.sdsP1),
            smooth: true,
            symbol: 'circle',
            symbolSize: 7,
            connectNulls: false,
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
            data: hourlyData.map(h => h.sdsP2),
            smooth: true,
            symbol: 'circle',
            symbolSize: 7,
            connectNulls: false,
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

  private aggregateByDay(
    points: AirrohrChartPoint[],
    year: number,
    month: number
  ): { sdsP1: number | null; sdsP2: number | null }[] {
    const daysInMonth = new Date(year, month, 0).getDate();
    const days = Array.from({ length: daysInMonth }, () => ({ sdsP1: [] as number[], sdsP2: [] as number[] }));
    for (const point of points) {
      const d = point.date.getDate() - 1;
      if (point.sdsP1 != null) days[d].sdsP1.push(point.sdsP1);
      if (point.sdsP2 != null) days[d].sdsP2.push(point.sdsP2);
    }
    return days.map(d => ({
      sdsP1: d.sdsP1.length ? d.sdsP1.reduce((a, b) => a + b, 0) / d.sdsP1.length : null,
      sdsP2: d.sdsP2.length ? d.sdsP2.reduce((a, b) => a + b, 0) / d.sdsP2.length : null
    }));
  }

  private aggregateByHour(points: AirrohrChartPoint[]): { sdsP1: number | null; sdsP2: number | null }[] {
    const hours = Array.from({ length: 24 }, () => ({ sdsP1: [] as number[], sdsP2: [] as number[] }));
    for (const point of points) {
      const h = point.date.getHours();
      if (point.sdsP1 != null) hours[h].sdsP1.push(point.sdsP1);
      if (point.sdsP2 != null) hours[h].sdsP2.push(point.sdsP2);
    }
    return hours.map(h => ({
      sdsP1: h.sdsP1.length ? h.sdsP1.reduce((a, b) => a + b, 0) / h.sdsP1.length : null,
      sdsP2: h.sdsP2.length ? h.sdsP2.reduce((a, b) => a + b, 0) / h.sdsP2.length : null
    }));
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

  private getSeriesFor(year: number | 'ALL', month: number | 'ALL', day: number | 'ALL'): AirrohrChartSeries {
    const points = this.filterByYearMonthAndDay(this.rawPoints, year, month, day);
    return { points };
  }

  private filterByYearMonthAndDay(
    points: AirrohrChartPoint[],
    year: number | 'ALL',
    month: number | 'ALL',
    day: number | 'ALL'
  ): AirrohrChartPoint[] {
    if (year === 'ALL') return points;
    const yearFiltered = points.filter(point => point.date.getFullYear() === year);
    if (month === 'ALL') return yearFiltered;
    const monthFiltered = yearFiltered.filter(point => point.date.getMonth() + 1 === month);
    if (day === 'ALL') return monthFiltered;
    return monthFiltered.filter(point => point.date.getDate() === day);
  }
}
