import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart, BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { WeatherService } from '../../services/weather.service';
import { WeatherHistoryReading, WeatherOverview } from '../../models/weather.model';
import { weatherSymbol, warnSeverity, WeatherSymbol, WarnSeverity } from '../../shared/weather-icon.util';

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

@Component({
  selector: 'app-weather',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './weather.component.html',
  styleUrl: './weather.component.scss'
})
export class WeatherComponent implements OnInit {
  private readonly weatherService = inject(WeatherService);

  overview: WeatherOverview | null = null;
  forecastChartOptions: Record<string, unknown> | null = null;
  historyChartOptions: Record<string, unknown> | null = null;

  isLoading = true;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.loadOverview();
    this.loadHistory();
  }

  symbolFor(icon: number | null | undefined): WeatherSymbol {
    return weatherSymbol(icon);
  }

  severityFor(level: number | null | undefined): WarnSeverity {
    return warnSeverity(level);
  }

  get nextRainText(): string {
    if (!this.overview?.nextRain) {
      return 'Kein Regen in den nächsten 24 Stunden';
    }
    const time = new Date(this.overview.nextRain).toLocaleTimeString('de-DE', {
      hour: '2-digit',
      minute: '2-digit'
    });
    return `Regen ab ${time} Uhr`;
  }

  private loadOverview(): void {
    this.isLoading = true;
    this.errorMessage = null;
    this.weatherService.getOverview().subscribe({
      next: overview => {
        this.overview = overview;
        this.forecastChartOptions = this.buildForecastChart(overview);
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden des Wetters:', error);
        this.errorMessage = 'Fehler beim Laden der Wetterdaten. Bitte erneut versuchen.';
        this.isLoading = false;
      }
    });
  }

  private loadHistory(): void {
    this.weatherService.getHistory().subscribe({
      next: readings => {
        this.historyChartOptions = readings.length ? this.buildHistoryChart(readings) : null;
      },
      error: (error: Error) => console.error('Fehler beim Laden des Wetterverlaufs:', error)
    });
  }

  private buildForecastChart(overview: WeatherOverview): Record<string, unknown> | null {
    if (!overview.hourlyForecast.length) {
      return null;
    }
    const labels = overview.hourlyForecast.map(h =>
      new Date(h.time).toLocaleTimeString('de-DE', { hour: '2-digit' }));
    return {
      grid: { left: 48, right: 48, top: 24, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: { data: ['Temperatur', 'Niederschlag'], top: 0 },
      xAxis: { type: 'category', data: labels, axisLabel: { color: '#94a3b8', fontSize: 11 } },
      yAxis: [
        { type: 'value', axisLabel: { color: '#94a3b8', formatter: '{value} °C' }, splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } } },
        { type: 'value', position: 'right', axisLabel: { color: '#94a3b8', formatter: '{value} mm' }, splitLine: { show: false } }
      ],
      series: [
        { name: 'Temperatur', type: 'line', yAxisIndex: 0, smooth: true, symbol: 'circle', symbolSize: 6,
          data: overview.hourlyForecast.map(h => h.temperature), lineStyle: { width: 2.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' } },
        { name: 'Niederschlag', type: 'bar', yAxisIndex: 1,
          data: overview.hourlyForecast.map(h => h.precipitation), itemStyle: { color: '#0ea5e9' } }
      ]
    };
  }

  private buildHistoryChart(readings: WeatherHistoryReading[]): Record<string, unknown> {
    const sorted = [...readings].sort((a, b) => a.readingTime.getTime() - b.readingTime.getTime());
    const labels = sorted.map(r => r.readingTime.toLocaleString('de-DE', { day: '2-digit', month: '2-digit', hour: '2-digit' }));
    return {
      grid: { left: 56, right: 24, top: 24, bottom: 36, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: labels, axisLabel: { color: '#94a3b8', fontSize: 11 } },
      yAxis: { type: 'value', axisLabel: { color: '#94a3b8', formatter: '{value} °C' }, splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } } },
      series: [
        { name: 'Temperatur', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, connectNulls: true,
          data: sorted.map(r => r.temperature), lineStyle: { width: 2.5, color: '#f59e0b' }, itemStyle: { color: '#f59e0b' } }
      ]
    };
  }
}
