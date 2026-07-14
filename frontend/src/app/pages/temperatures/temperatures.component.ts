import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TemperatureService } from '../../services/temperature.service';
import { TemperatureSensorSeries, TimeRange } from '../../models/temperature.model';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface RangeOption {
  value: TimeRange;
  label: string;
}

interface ChartTile {
  sensorId: string;
  name: string;
  options: Record<string, unknown>;
}

@Component({
  selector: 'app-temperatures',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './temperatures.component.html',
  styleUrl: './temperatures.component.scss'
})
export class TemperaturesComponent implements OnInit {
  private readonly temperatureService = inject(TemperatureService);

  readonly ranges: RangeOption[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];

  activeRange: TimeRange = 'WEEK';
  charts: ChartTile[] = [];
  isLoading = true;
  isEmpty = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load(this.activeRange);
  }

  setRange(range: TimeRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.load(range);
  }

  private load(range: TimeRange): void {
    this.isLoading = true;
    this.isEmpty = false;
    this.errorMessage = null;
    this.temperatureService.getSeries(range).subscribe({
      next: series => {
        this.charts = series.map(s => ({
          sensorId: s.sensorId,
          name: s.name,
          options: this.chartOptionsFor(s)
        }));
        this.isEmpty = this.charts.length === 0;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Temperaturen:', error);
        this.errorMessage = 'Fehler beim Laden der Temperaturdaten. Bitte erneut versuchen.';
        this.isLoading = false;
      }
    });
  }

  chartOptionsFor(series: TemperatureSensorSeries): Record<string, unknown> {
    const hasHumidity = series.humidity.length > 0;
    const legend = ['Temperatur'];
    const yAxis: Record<string, unknown>[] = [
      {
        type: 'value',
        axisLabel: { color: '#94a3b8', formatter: '{value} °C' },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      }
    ];
    const chartSeries: Record<string, unknown>[] = [
      {
        name: 'Temperatur',
        type: 'line',
        yAxisIndex: 0,
        smooth: true,
        showSymbol: false,
        data: series.temperature.map(p => [p.time, p.value]),
        lineStyle: { width: 2.5, color: '#e6484d' },
        itemStyle: { color: '#e6484d' }
      }
    ];

    if (hasHumidity) {
      legend.push('Luftfeuchtigkeit');
      yAxis.push({
        type: 'value',
        position: 'right',
        axisLabel: { color: '#94a3b8', formatter: '{value} %' },
        splitLine: { show: false }
      });
      chartSeries.push({
        name: 'Luftfeuchtigkeit',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        showSymbol: false,
        data: series.humidity.map(p => [p.time, p.value]),
        lineStyle: { width: 2, color: '#3b82f6', type: 'dashed' },
        itemStyle: { color: '#3b82f6' }
      });
    }

    return {
      grid: { left: 48, right: hasHumidity ? 48 : 16, top: 32, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: { data: legend, top: 0, textStyle: { color: '#94a3b8' } },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis,
      series: chartSeries
    };
  }
}
