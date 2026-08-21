import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
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

/** Eine Sensorkachel des Rasters. */
interface ChartTile {
  sensorId: string;
  name: string;
  options: Record<string, unknown>;
}

const TEMPERATURE_COLOR = '#e6484d';
const HUMIDITY_COLOR = '#3b82f6';
const AXIS_COLOR = '#94a3b8';

/**
 * Temperaturuebersicht fuer das Wandtablet: alle Sensoren gleichzeitig, ohne
 * Scrollen und ohne Umschalter - Temperatur und Luftfeuchte stehen zusammen in
 * einem Chart. Anders als die Website-Seite `/temperatures` ist diese Ansicht
 * zum Ansehen gebaut, nicht zum Bedienen.
 */
@Component({
  selector: 'app-tablet-temperatures',
  standalone: true,
  imports: [CommonModule, RouterLink, NgxEchartsDirective],
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

  activeRange: TimeRange = 'WEEK';
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
        this.charts = series.map(s => ({
          sensorId: s.sensorId,
          name: s.name,
          options: this.chartOptionsFor(s)
        }));
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

  /** Baut die Chart-Optionen einer Kachel; ohne Feuchtewerte entfaellt die zweite Achse. */
  chartOptionsFor(series: TemperatureSensorSeries): Record<string, unknown> {
    const hasHumidity = series.humidity.length > 0;
    const axisLabel = { color: AXIS_COLOR, fontSize: 13 };

    const yAxis: Record<string, unknown>[] = [
      {
        type: 'value',
        scale: true,
        axisLabel: { ...axisLabel, formatter: '{value} °C' },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      }
    ];
    const chartSeries: Record<string, unknown>[] = [
      {
        name: 'Temperatur',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: series.temperature.map(p => [p.time, p.value]),
        lineStyle: { width: 3, color: TEMPERATURE_COLOR },
        itemStyle: { color: TEMPERATURE_COLOR }
      }
    ];

    if (hasHumidity) {
      yAxis.push({
        type: 'value',
        scale: true,
        axisLabel: { ...axisLabel, formatter: '{value} %' },
        splitLine: { show: false }
      });
      chartSeries.push({
        name: 'Luftfeuchte',
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 1,
        data: series.humidity.map(p => [p.time, p.value]),
        lineStyle: { width: 2, color: HUMIDITY_COLOR },
        itemStyle: { color: HUMIDITY_COLOR }
      });
    }

    return {
      grid: { left: 56, right: hasHumidity ? 56 : 16, top: 12, bottom: 28, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'time', axisLabel },
      yAxis,
      series: chartSeries
    };
  }
}
