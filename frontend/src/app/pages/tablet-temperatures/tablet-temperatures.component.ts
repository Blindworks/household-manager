import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { TemperatureService } from '../../services/temperature.service';
import { TemperatureSensorSeries, TimeRange } from '../../models/temperature.model';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface RangeOption {
  value: TimeRange;
  label: string;
}

/** Umschaltbare Messgroesse der Ansicht. */
export type Metric = 'temperature' | 'humidity';

interface MetricOption {
  value: Metric;
  label: string;
}

/** Eine Sensorkachel des Rasters. */
interface ChartTile {
  sensorId: string;
  name: string;
  /** Rohserie, um die Optionen beim Umschalten ohne neuen Abruf zu bauen. */
  series: TemperatureSensorSeries;
  options: Record<string, unknown>;
  /** True, wenn der Sensor zu keiner gewaehlten Messgroesse Werte hat. */
  empty: boolean;
}

const TEMPERATURE_COLOR = '#e6484d';
const HUMIDITY_COLOR = '#3b82f6';
const AXIS_COLOR = '#94a3b8';

/**
 * Temperaturuebersicht fuer das Wandtablet: alle Sensoren gleichzeitig, ohne
 * Scrollen. Welche Messgroessen zu sehen sind, waehlt die Kopfzeile - ab Werk
 * nur die Temperatur, die Luftfeuchte laesst sich dazuschalten.
 */
@Component({
  selector: 'app-tablet-temperatures',
  standalone: true,
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-temperatures.component.html',
  styleUrl: './tablet-temperatures.component.scss'
})
export class TabletTemperaturesComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly temperatureService = inject(TemperatureService);
  private refreshTimer: number | null = null;

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
  /** Standardmaessig zeigt die Ansicht nur die Temperatur. */
  activeMetrics = new Set<Metric>(['temperature']);
  charts: ChartTile[] = [];
  isLoading = true;
  isEmpty = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load(this.activeRange);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletTemperaturesComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  /** Spaltenzahl des Rasters: bei vielen Sensoren lieber schmaler als scrollen. */
  get columns(): number {
    return this.charts.length >= 6 ? 3 : 2;
  }

  isMetricActive(metric: Metric): boolean {
    return this.activeMetrics.has(metric);
  }

  /**
   * Blendet eine Messgroesse ein oder aus. Die letzte aktive laesst sich nicht
   * abwaehlen - eine Ansicht ohne jede Linie sieht aus wie ein Fehler.
   */
  toggleMetric(metric: Metric): void {
    if (this.activeMetrics.has(metric)) {
      if (this.activeMetrics.size === 1) {
        return;
      }
      this.activeMetrics.delete(metric);
    } else {
      this.activeMetrics.add(metric);
    }
    this.rebuildCharts();
  }

  setRange(range: TimeRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.load(range);
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.load(this.activeRange, true);
  }

  private load(range: TimeRange, silent = false): void {
    if (!silent) {
      this.isLoading = true;
      this.errorMessage = null;
    }
    this.temperatureService.getSeries(range).subscribe({
      next: series => {
        this.charts = series.map(s => this.toTile(s));
        this.isEmpty = this.charts.length === 0;
        this.errorMessage = null;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Temperaturen:', error);
        this.isLoading = false;
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte
        // nicht durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer
        // Wandanzeige mehr wert als gar keine.
        if (!silent) {
          this.errorMessage = 'Temperaturdaten konnten nicht geladen werden.';
        }
      }
    });
  }

  private rebuildCharts(): void {
    this.charts = this.charts.map(tile => this.toTile(tile.series));
  }

  private toTile(series: TemperatureSensorSeries): ChartTile {
    return {
      sensorId: series.sensorId,
      name: series.name,
      series,
      options: this.chartOptionsFor(series),
      empty: this.pointCount(series) === 0
    };
  }

  /** Anzahl der Messpunkte, die bei der aktuellen Auswahl gezeichnet wuerden. */
  private pointCount(series: TemperatureSensorSeries): number {
    let count = 0;
    if (this.isMetricActive('temperature')) {
      count += series.temperature.length;
    }
    if (this.isMetricActive('humidity')) {
      count += series.humidity.length;
    }
    return count;
  }

  /**
   * Baut die Chart-Optionen einer Kachel. Jede gewaehlte Messgroesse mit Werten
   * bekommt eine eigene Y-Achse; fehlt eine, entfaellt sie samt Achse.
   */
  chartOptionsFor(series: TemperatureSensorSeries): Record<string, unknown> {
    const axisLabel = { color: AXIS_COLOR, fontSize: 13 };
    const showTemperature = this.isMetricActive('temperature') && series.temperature.length > 0;
    const showHumidity = this.isMetricActive('humidity') && series.humidity.length > 0;

    const yAxis: Record<string, unknown>[] = [];
    const chartSeries: Record<string, unknown>[] = [];

    if (showTemperature) {
      yAxis.push({
        type: 'value',
        scale: true,
        axisLabel: { ...axisLabel, formatter: '{value} °C' },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      });
      chartSeries.push({
        name: 'Temperatur',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        data: series.temperature.map(p => [p.time, p.value]),
        lineStyle: { width: 3, color: TEMPERATURE_COLOR },
        itemStyle: { color: TEMPERATURE_COLOR }
      });
    }

    if (showHumidity) {
      yAxis.push({
        type: 'value',
        scale: true,
        axisLabel: { ...axisLabel, formatter: '{value} %' },
        // Nur die erste Achse zieht Hilfslinien, sonst ueberlagern sich zwei Raster.
        splitLine: showTemperature
          ? { show: false }
          : { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      });
      chartSeries.push({
        name: 'Luftfeuchte',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: yAxis.length - 1,
        data: series.humidity.map(p => [p.time, p.value]),
        lineStyle: { width: 2, color: HUMIDITY_COLOR },
        itemStyle: { color: HUMIDITY_COLOR }
      });
    }

    return {
      grid: {
        left: 56,
        right: showTemperature && showHumidity ? 56 : 16,
        top: 12,
        bottom: 28,
        containLabel: false
      },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'time', axisLabel },
      yAxis,
      series: chartSeries
    };
  }
}
