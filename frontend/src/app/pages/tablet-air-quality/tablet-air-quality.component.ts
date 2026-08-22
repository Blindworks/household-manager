import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { AirQualitySeriesService } from '../../services/air-quality-series.service';
import {
  AIR_QUALITY_METRICS,
  AirQualityMetric,
  AirQualitySensorSeries
} from '../../models/air-quality-series.model';
import { TimeValue, TimeRange } from '../../models/temperature.model';
import { IaqLevel, iaqLevel } from '../../models/alexa-air-quality.model';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface RangeOption {
  value: TimeRange;
  label: string;
}

/** Eine Kachel des Rasters: genau ein Sensor, genau eine Messgroesse. */
interface ChartTile {
  /** Eindeutig ueber Sensor UND Messgroesse - ein Sensor stellt mehrere Kacheln. */
  tileId: string;
  /** Name des Sensors, z. B. "Draußen". */
  name: string;
  /** Name der Messgroesse, z. B. "PM2.5". */
  metricLabel: string;
  metric: AirQualityMetric;
  points: TimeValue[];
  options: Record<string, unknown>;
  /** Juengster Wert inkl. Einheit, z. B. "8 µg/m³". */
  currentLabel: string;
  /** Nur beim IAQ gesetzt - nur dort gibt es eine allgemein gueltige Bewertung. */
  currentLevel: IaqLevel | null;
}

const AXIS_COLOR = '#94a3b8';

/**
 * Luftqualitaetsuebersicht fuer das Wandtablet: jede Messgroesse jedes Sensors
 * bekommt ihren eigenen Graphen, alle gleichzeitig und ohne Scrollen. Eine
 * gemeinsame Achse waere ohnehin nicht moeglich - die Groessen haben vier
 * verschiedene Einheiten.
 */
@Component({
  selector: 'app-tablet-air-quality',
  standalone: true,
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-air-quality.component.html',
  styleUrl: './tablet-air-quality.component.scss'
})
export class TabletAirQualityComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly seriesService = inject(AirQualitySeriesService);
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
      TabletAirQualityComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  /**
   * Spaltenzahl des Rasters. Mit einer Kachel je Sensor und Messgroesse kommen
   * schnell zweistellige Zahlen zusammen - lieber schmaler als scrollen.
   */
  get columns(): number {
    if (this.charts.length <= 2) {
      return 2;
    }
    return this.charts.length <= 6 ? 3 : 4;
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
    this.seriesService.getSeries(range).subscribe({
      next: series => {
        this.charts = series.flatMap(sensor => this.tilesFor(sensor));
        this.isEmpty = this.charts.length === 0;
        this.errorMessage = null;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Luftqualitätsdaten:', error);
        this.isLoading = false;
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte
        // nicht durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer
        // Wandanzeige mehr wert als gar keine.
        if (!silent) {
          this.errorMessage = 'Luftqualitätsdaten konnten nicht geladen werden.';
        }
      }
    });
  }

  /**
   * Eine Kachel je Messgroesse, die dieser Sensor tatsaechlich liefert. Eine
   * Kachel ohne Werte gaebe es damit nicht - ein leeres Diagramm an der Wand
   * sieht aus wie ein Fehler und kostet nur Platz.
   */
  private tilesFor(sensor: AirQualitySensorSeries): ChartTile[] {
    return AIR_QUALITY_METRICS
      .filter(metric => (sensor.metrics[metric.key] ?? []).length > 0)
      .map(metric => this.toTile(sensor, metric));
  }

  private toTile(sensor: AirQualitySensorSeries, metric: AirQualityMetric): ChartTile {
    const points = sensor.metrics[metric.key] ?? [];
    const current = points[points.length - 1].value;
    const tile: ChartTile = {
      tileId: `${sensor.sensorId}/${metric.key}`,
      name: sensor.name,
      metricLabel: metric.label,
      metric,
      points,
      options: {},
      currentLabel: this.formatValue(current, metric),
      currentLevel: metric.key === 'iaq' ? iaqLevel(current) : null
    };
    tile.options = this.chartOptionsFor(tile);
    return tile;
  }

  /** Deutsche Zahlformatierung, hoechstens eine Nachkommastelle, plus Einheit. */
  private formatValue(value: number, metric: AirQualityMetric): string {
    const formatted = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(value);
    return metric.unit ? `${formatted} ${metric.unit}` : formatted;
  }

  /** Baut die Chart-Optionen einer Kachel: eine Linie auf einer Achse. */
  chartOptionsFor(tile: ChartTile): Record<string, unknown> {
    const axisLabel = { color: AXIS_COLOR, fontSize: 13 };

    return {
      grid: { left: 56, right: 16, top: 12, bottom: 28, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'time', axisLabel },
      yAxis: [{
        type: 'value',
        scale: true,
        axisLabel: {
          ...axisLabel,
          formatter: tile.metric.unit ? `{value} ${tile.metric.unit}` : '{value}'
        },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      }],
      series: [{
        name: tile.metric.label,
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        data: tile.points.map(point => [point.time, point.value]),
        lineStyle: { width: 3, color: tile.metric.color },
        itemStyle: { color: tile.metric.color }
      }]
    };
  }
}
