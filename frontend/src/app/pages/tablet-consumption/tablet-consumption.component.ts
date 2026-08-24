import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { MeterConsumptionSeriesService } from '../../services/meter-consumption-series.service';
import {
  ConsumptionRange,
  ConsumptionResolution,
  MeterConsumptionSeries
} from '../../models/meter-consumption-series.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';
import {
  RANGE_OPTIONS,
  RangeOption,
  compareToPrevious,
  defaultRangeFor,
  formatConsumption
} from '../../shared/consumption-view.util';

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface ResolutionOption {
  readonly value: ConsumptionResolution;
  readonly label: string;
}

/** Eine Zaehlerkachel des Rasters. */
interface ConsumptionTile {
  /** Zaehlertyp als Schluessel des @for-track. */
  readonly key: string;
  readonly name: string;
  /** Letzter Wert mit Einheit, z. B. "38,1 kWh". */
  readonly currentLabel: string;
  /** Veraenderung zur Vorperiode, null wenn nicht vergleichbar. */
  readonly comparison: string | null;
  /** True, wenn mindestens ein Balken ein Schaetzwert ist - steuert die Legende. */
  readonly hasEstimated: boolean;
  readonly options: Record<string, unknown>;
}

const AXIS_COLOR = '#94a3b8';
/** Deckkraft geschaetzter Balken - sichtbar blasser, aber noch klar erkennbar. */
const ESTIMATED_OPACITY = 0.45;

/**
 * Verbrauchsuebersicht fuer das Wandtablet: Strom, Gas und Wasser nebeneinander,
 * je Ablesewoche oder Kalendermonat, ohne Scrollen.
 */
@Component({
  selector: 'app-tablet-consumption',
  standalone: true,
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-consumption.component.html',
  styleUrl: './tablet-consumption.component.scss'
})
export class TabletConsumptionComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly seriesService = inject(MeterConsumptionSeriesService);
  private refreshTimer: number | null = null;

  readonly resolutions: ResolutionOption[] = [
    { value: 'WEEK', label: 'Woche' },
    { value: 'MONTH', label: 'Monat' }
  ];

  activeResolution: ConsumptionResolution = 'WEEK';
  activeRange: ConsumptionRange = defaultRangeFor('WEEK');
  tiles: ConsumptionTile[] = [];
  isLoading = true;
  isEmpty = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load(this.activeRange);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletConsumptionComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  /** Die Zeitraumknoepfe der aktiven Aufloesung. */
  get ranges(): readonly RangeOption[] {
    return RANGE_OPTIONS[this.activeResolution];
  }

  setRange(range: ConsumptionRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.load(range);
  }

  /**
   * Wechselt die Aufloesung und setzt dabei den Standardzeitraum der NEUEN
   * Aufloesung - nicht den gleichen Index. Sonst landete man von "8 Wochen" bei
   * "6 Monaten" und die Ansicht spraenge auf einen ganz anderen Massstab.
   */
  setResolution(resolution: ConsumptionResolution): void {
    if (resolution === this.activeResolution) {
      return;
    }
    this.activeResolution = resolution;
    this.activeRange = defaultRangeFor(resolution);
    this.load(this.activeRange);
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.load(this.activeRange, true);
  }

  private load(range: ConsumptionRange, silent = false): void {
    if (!silent) {
      this.isLoading = true;
      this.errorMessage = null;
    }
    const resolution = this.activeResolution;
    this.seriesService.getSeries(range).subscribe({
      next: series => {
        this.tiles = series.map(s => this.toTile(s, resolution));
        this.isEmpty = this.tiles.length === 0;
        this.errorMessage = null;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Verbrauchsdaten:', error);
        this.isLoading = false;
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte nicht
        // durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer Wandanzeige
        // mehr wert als gar keine.
        if (!silent) {
          this.errorMessage = 'Verbrauchsdaten konnten nicht geladen werden.';
        }
      }
    });
  }

  private toTile(
    series: MeterConsumptionSeries,
    resolution: ConsumptionResolution
  ): ConsumptionTile {
    const last = series.points.length > 0
      ? series.points[series.points.length - 1].consumption
      : null;
    return {
      key: series.meterType,
      name: MeterTypeUtils.getLabel(series.meterType),
      currentLabel: formatConsumption(last, series.unit),
      comparison: compareToPrevious(series.points, resolution),
      hasEstimated: series.points.some(p => p.estimated),
      options: this.chartOptionsFor(series)
    };
  }

  /**
   * Balkendiagramm einer Kachel. Geschaetzte Balken bekommen dieselbe Farbe mit
   * geringerer Deckkraft - sichtbar, dass diese Woche nicht wirklich abgelesen wurde,
   * ohne sie aus der Summe zu nehmen.
   */
  private chartOptionsFor(series: MeterConsumptionSeries): Record<string, unknown> {
    const color = MeterTypeUtils.getColor(series.meterType);
    const axisLabel = { color: AXIS_COLOR, fontSize: 12 };

    return {
      grid: { left: 52, right: 12, top: 12, bottom: 30, containLabel: false },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value: number) => formatConsumption(value, series.unit)
      },
      xAxis: {
        type: 'category',
        data: series.points.map(p => p.label),
        axisLabel: { ...axisLabel, hideOverlap: true }
      },
      yAxis: {
        type: 'value',
        axisLabel: { ...axisLabel, formatter: `{value} ${series.unit}` },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      },
      series: [
        {
          type: 'bar',
          data: series.points.map(p => ({
            value: [p.label, p.consumption],
            itemStyle: {
              color,
              opacity: p.estimated ? ESTIMATED_OPACITY : 1,
              borderColor: color,
              borderType: p.estimated ? 'dashed' : 'solid',
              borderWidth: p.estimated ? 1 : 0
            }
          }))
        }
      ]
    };
  }
}
