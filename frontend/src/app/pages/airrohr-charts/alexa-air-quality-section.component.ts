import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { AlexaAirQualityService } from '../../services/alexa-air-quality.service';
import {
  ALEXA_AIR_QUALITY_METRICS,
  AlexaAirQualityMetric,
  AlexaAirQualityMetricKey,
  AlexaAirQualityReading,
  IaqLevel,
  iaqLevel
} from '../../models/alexa-air-quality.model';

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

@Component({
  selector: 'app-alexa-air-quality-section',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './alexa-air-quality-section.component.html',
  styleUrl: './alexa-air-quality-section.component.scss'
})
export class AlexaAirQualitySectionComponent implements OnInit {
  private readonly alexaAirQualityService = inject(AlexaAirQualityService);

  readonly metrics = ALEXA_AIR_QUALITY_METRICS;

  latest: AlexaAirQualityReading[] = [];
  selectedMetric: AlexaAirQualityMetricKey = 'iaq';
  selectedYear: number | 'ALL' = 'ALL';
  selectedMonth: number | 'ALL' = 'ALL';
  selectedDay: number | 'ALL' = 'ALL';
  availableYears: number[] = [];
  availableMonths: number[] = [];
  availableDays: number[] = [];
  chartOptions: Record<string, unknown> | null = null;
  isLoading = true;
  errorMessage: string | null = null;

  private readings: AlexaAirQualityReading[] = [];

  ngOnInit(): void {
    this.alexaAirQualityService.getLatest().subscribe({
      next: latest => (this.latest = latest),
      error: () => (this.latest = [])
    });
    this.alexaAirQualityService.getReadings().subscribe({
      next: readings => {
        this.readings = readings;
        this.availableYears = [...new Set(readings.map(r => r.readingTime.getFullYear()))].sort();
        this.isLoading = false;
        this.refreshChart();
      },
      error: err => {
        this.errorMessage = err.message;
        this.isLoading = false;
      }
    });
  }

  get hasData(): boolean {
    return this.readings.length > 0 || this.latest.length > 0;
  }

  metricValue(reading: AlexaAirQualityReading, key: AlexaAirQualityMetricKey): number | null {
    return reading[key];
  }

  iaqLevelFor(reading: AlexaAirQualityReading): IaqLevel {
    return iaqLevel(reading.iaq);
  }

  setMetric(key: string): void {
    this.selectedMetric = key as AlexaAirQualityMetricKey;
    this.refreshChart();
  }

  setYear(value: string): void {
    this.selectedYear = value === 'ALL' ? 'ALL' : Number(value);
    this.selectedMonth = 'ALL';
    this.selectedDay = 'ALL';
    this.availableMonths = this.selectedYear === 'ALL' ? [] : this.monthsFor(this.selectedYear);
    this.availableDays = [];
    this.refreshChart();
  }

  setMonth(value: string): void {
    this.selectedMonth = value === 'ALL' ? 'ALL' : Number(value);
    this.selectedDay = 'ALL';
    this.availableDays =
      this.selectedYear === 'ALL' || this.selectedMonth === 'ALL'
        ? []
        : this.daysFor(this.selectedYear, this.selectedMonth);
    this.refreshChart();
  }

  setDay(value: string): void {
    this.selectedDay = value === 'ALL' ? 'ALL' : Number(value);
    this.refreshChart();
  }

  private monthsFor(year: number): number[] {
    return [...new Set(
      this.readings
        .filter(r => r.readingTime.getFullYear() === year)
        .map(r => r.readingTime.getMonth() + 1)
    )].sort((a, b) => a - b);
  }

  private daysFor(year: number, month: number): number[] {
    return [...new Set(
      this.readings
        .filter(r => r.readingTime.getFullYear() === year && r.readingTime.getMonth() + 1 === month)
        .map(r => r.readingTime.getDate())
    )].sort((a, b) => a - b);
  }

  private filteredReadings(): AlexaAirQualityReading[] {
    return this.readings.filter(r => {
      const time = r.readingTime;
      if (this.selectedYear !== 'ALL' && time.getFullYear() !== this.selectedYear) return false;
      if (this.selectedMonth !== 'ALL' && time.getMonth() + 1 !== this.selectedMonth) return false;
      if (this.selectedDay !== 'ALL' && time.getDate() !== this.selectedDay) return false;
      return true;
    });
  }

  private refreshChart(): void {
    const filtered = this.filteredReadings();
    if (!filtered.length) {
      this.chartOptions = null;
      return;
    }
    const metric = this.metrics.find(m => m.key === this.selectedMetric) as AlexaAirQualityMetric;
    const deviceNames = [...new Set(filtered.map(r => r.deviceName))];
    const series = deviceNames.map(name => ({
      name,
      type: 'line',
      showSymbol: false,
      connectNulls: true,
      data: filtered
        .filter(r => r.deviceName === name)
        .map(r => [r.readingTime.getTime(), this.metricValue(r, this.selectedMetric)])
    }));
    this.chartOptions = {
      tooltip: { trigger: 'axis' },
      legend: { data: deviceNames },
      grid: { left: 48, right: 16, top: 40, bottom: 32 },
      xAxis: { type: 'time' },
      yAxis: { type: 'value', name: metric.unit },
      series
    };
  }
}
