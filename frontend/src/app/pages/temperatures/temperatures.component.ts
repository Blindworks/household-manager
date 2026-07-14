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

/** Umschaltbare Messgröße einer Kachel. */
type Metric = 'temperature' | 'humidity';

interface MetricOption {
  value: Metric;
  label: string;
}

interface ChartTile {
  sensorId: string;
  name: string;
  /** Ob der Sensor Feuchtewerte liefert (steuert die Sichtbarkeit des Umschalters). */
  hasHumidity: boolean;
  /** Aktuell angezeigte Messgröße. */
  activeMetric: Metric;
  /** Rohserie, um die Chart-Optionen beim Umschalten neu zu bauen. */
  series: TemperatureSensorSeries;
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

  readonly metrics: MetricOption[] = [
    { value: 'temperature', label: 'Temperatur' },
    { value: 'humidity', label: 'Luftfeuchte' }
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

  setMetric(tile: ChartTile, metric: Metric): void {
    if (tile.activeMetric === metric) {
      return;
    }
    tile.activeMetric = metric;
    tile.options = this.chartOptionsFor(tile.series, metric);
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
          hasHumidity: s.humidity.length > 0,
          activeMetric: 'temperature' as Metric,
          series: s,
          options: this.chartOptionsFor(s, 'temperature')
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

  chartOptionsFor(series: TemperatureSensorSeries, metric: Metric): Record<string, unknown> {
    const isTemperature = metric === 'temperature';
    const points = isTemperature ? series.temperature : series.humidity;
    const name = isTemperature ? 'Temperatur' : 'Luftfeuchtigkeit';
    const unit = isTemperature ? '°C' : '%';
    const color = isTemperature ? '#e6484d' : '#3b82f6';

    return {
      grid: { left: 48, right: 16, top: 32, bottom: 32, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: { data: [name], top: 0, textStyle: { color: '#94a3b8' } },
      xAxis: {
        type: 'time',
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: '#94a3b8', formatter: `{value} ${unit}` },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      },
      series: [
        {
          name,
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: points.map(p => [p.time, p.value]),
          lineStyle: { width: 2.5, color },
          itemStyle: { color }
        }
      ]
    };
  }
}
