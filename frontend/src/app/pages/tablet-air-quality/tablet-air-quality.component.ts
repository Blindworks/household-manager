import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { AirQualitySeriesService } from '../../services/air-quality-series.service';
import {
  AIR_QUALITY_GROUPS,
  AirQualityMetricGroup,
  AirQualityMetricLine,
  AirQualitySensorSeries
} from '../../models/air-quality-series.model';
import { TimeRange } from '../../models/temperature.model';
import { IaqLevel, iaqLevel } from '../../models/alexa-air-quality.model';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface RangeOption {
  value: TimeRange;
  label: string;
}

/** Eine Sensorkachel des Rasters. */
interface ChartTile {
  sensorId: string;
  name: string;
  /** Rohserie, um die Optionen beim Gruppenwechsel ohne neuen Abruf zu bauen. */
  series: AirQualitySensorSeries;
  options: Record<string, unknown>;
  /** True, wenn der Sensor zur gewaehlten Gruppe keine Werte hat. */
  empty: boolean;
  /** Juengster Wert der Gruppe inkl. Einheit, z. B. "8 µg/m³"; null, wenn keiner da ist. */
  currentLabel: string | null;
  /** Nur beim IAQ gesetzt - nur dort gibt es eine allgemein gueltige Bewertung. */
  currentLevel: IaqLevel | null;
}

const AXIS_COLOR = '#94a3b8';

/**
 * Luftqualitaetsuebersicht fuer das Wandtablet: alle Sensoren gleichzeitig, ohne
 * Scrollen. Welche Messgroesse zu sehen ist, waehlt die Kopfzeile - immer genau
 * eine Gruppe, damit jede Kachel genau eine Y-Achse hat.
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

  readonly groups = AIR_QUALITY_GROUPS;

  activeRange: TimeRange = 'WEEK';
  /** Feinstaub ist die einzige Gruppe, die beide Quellen liefern. */
  activeGroup: AirQualityMetricGroup = AIR_QUALITY_GROUPS[0];
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

  /** Spaltenzahl des Rasters: bei vielen Sensoren lieber schmaler als scrollen. */
  get columns(): number {
    return this.charts.length >= 6 ? 3 : 2;
  }

  setGroup(key: string): void {
    const group = this.groups.find(candidate => candidate.key === key);
    if (!group || group.key === this.activeGroup.key) {
      return;
    }
    this.activeGroup = group;
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
    this.seriesService.getSeries(range).subscribe({
      next: series => {
        this.charts = series.map(s => this.toTile(s));
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

  private rebuildCharts(): void {
    this.charts = this.charts.map(tile => this.toTile(tile.series));
  }

  private toTile(series: AirQualitySensorSeries): ChartTile {
    const current = this.currentValue(series);
    return {
      sensorId: series.sensorId,
      name: series.name,
      series,
      options: this.chartOptionsFor(series),
      empty: this.activeLines(series).length === 0,
      currentLabel: current === null ? null : this.formatValue(current),
      currentLevel:
        this.activeGroup.key === 'iaq' && current !== null ? iaqLevel(current) : null
    };
  }

  /** Die Linien der aktiven Gruppe, zu denen dieser Sensor ueberhaupt Werte hat. */
  private activeLines(series: AirQualitySensorSeries): AirQualityMetricLine[] {
    return this.activeGroup.lines.filter(line => (series.metrics[line.key] ?? []).length > 0);
  }

  /**
   * Juengster Wert der Kachel: der der ersten vorhandenen Linie der Gruppe. Bei
   * Feinstaub ist das PM2.5 - die Groesse, auf die es gesundheitlich ankommt.
   */
  private currentValue(series: AirQualitySensorSeries): number | null {
    const lines = this.activeLines(series);
    if (lines.length === 0) {
      return null;
    }
    const points = series.metrics[lines[0].key] ?? [];
    return points[points.length - 1].value;
  }

  /** Deutsche Zahlformatierung, hoechstens eine Nachkommastelle, plus Einheit. */
  private formatValue(value: number): string {
    const formatted = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(value);
    return this.activeGroup.unit ? `${formatted} ${this.activeGroup.unit}` : formatted;
  }

  /**
   * Baut die Chart-Optionen einer Kachel: eine Y-Achse fuer die ganze Gruppe, je
   * vorhandener Messgroesse eine Linie darauf.
   */
  chartOptionsFor(series: AirQualitySensorSeries): Record<string, unknown> {
    const axisLabel = { color: AXIS_COLOR, fontSize: 13 };
    const lines = this.activeLines(series);

    return {
      grid: { left: 56, right: 16, top: 12, bottom: 28, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'time', axisLabel },
      yAxis: [{
        type: 'value',
        scale: true,
        axisLabel: {
          ...axisLabel,
          formatter: this.activeGroup.unit ? `{value} ${this.activeGroup.unit}` : '{value}'
        },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      }],
      series: lines.map(line => ({
        name: line.label,
        type: 'line',
        smooth: true,
        showSymbol: false,
        yAxisIndex: 0,
        data: (series.metrics[line.key] ?? []).map(point => [point.time, point.value]),
        lineStyle: { width: 3, color: line.color },
        itemStyle: { color: line.color }
      }))
    };
  }
}
