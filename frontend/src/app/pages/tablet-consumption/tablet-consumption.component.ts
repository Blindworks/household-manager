import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, forkJoin } from 'rxjs';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { MeterConsumptionSeriesService } from '../../services/meter-consumption-series.service';
import {
  ConsumptionPoint,
  ConsumptionRange,
  ConsumptionResolution,
  MeterConsumptionSeries
} from '../../models/meter-consumption-series.model';
import { MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';
import {
  RANGE_OPTIONS,
  RangeOption,
  TotalsTable,
  buildTotalsTable,
  compareToPrevious,
  defaultRangeFor,
  formatConsumption,
  formatCost,
  isRunningPeriod
} from '../../shared/consumption-view.util';

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface ResolutionOption {
  readonly value: ConsumptionResolution;
  readonly label: string;
}

/** Was eine Kachel zeigt: Menge oder verbrauchsabhaengige Kosten. */
export type TileMode = 'consumption' | 'cost';

interface ModeOption {
  readonly value: TileMode;
  readonly label: string;
}

/** Ganze Seite: Kachelraster mit Diagrammen oder Summentabelle. */
export type ViewMode = 'chart' | 'table';

interface ViewModeOption {
  readonly value: ViewMode;
  readonly label: string;
}

/** Eine Zaehlerkachel des Rasters. */
interface ConsumptionTile {
  /** Zaehlertyp als Schluessel des @for-track. */
  readonly key: string;
  readonly meterType: MeterType;
  readonly name: string;
  readonly mode: TileMode;
  /** Letzter Wert mit Einheit, z. B. "38,1 kWh" bzw. "11,43 €". */
  readonly currentLabel: string;
  /** "bis heute", wenn der Kopfwert ein angebrochenes Jahr ist; sonst null. */
  readonly currentSuffix: string | null;
  /** Veraenderung zur Vorperiode, null wenn nicht vergleichbar. */
  readonly comparison: string | null;
  /** True, wenn mindestens ein Balken ein Schaetzwert ist - steuert die Legende. */
  readonly hasEstimated: boolean;
  /** Im Kostenmodus: Balken, die mangels hinterlegtem Preis entfallen sind. */
  readonly missingPriceCount: number;
  /** Im Kostenmodus: kein einziger Balken hat einen Preis - kein Diagramm zeigbar. */
  readonly hasNoCost: boolean;
  readonly options: Record<string, unknown>;
}

const AXIS_COLOR = '#94a3b8';
/** Deckkraft geschaetzter Balken - sichtbar blasser, aber noch klar erkennbar. */
const ESTIMATED_OPACITY = 0.45;

/**
 * Verbrauchsuebersicht fuer das Wandtablet: Strom, Gas und Wasser nebeneinander,
 * je Ablesewoche, Kalendermonat oder Kalenderjahr, ohne Scrollen - oder als
 * Summentabelle (Jahre, aufklappbar in Monate), die innerhalb ihres Rahmens scrollt.
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
  /** Laufender Abruf, damit ein neuer den alten ablösen kann. */
  private pendingRequest: Subscription | null = null;

  readonly resolutions: ResolutionOption[] = [
    { value: 'WEEK', label: 'Woche' },
    { value: 'MONTH', label: 'Monat' },
    { value: 'YEAR', label: 'Jahr' }
  ];

  readonly viewModes: ViewModeOption[] = [
    { value: 'chart', label: 'Diagramm' },
    { value: 'table', label: 'Tabelle' }
  ];

  readonly modes: ModeOption[] = [
    { value: 'consumption', label: 'Verbrauch' },
    { value: 'cost', label: 'Kosten' }
  ];

  activeResolution: ConsumptionResolution = 'WEEK';
  activeRange: ConsumptionRange = defaultRangeFor('WEEK');
  tiles: ConsumptionTile[] = [];
  isLoading = true;
  isEmpty = false;
  errorMessage: string | null = null;
  /** Nicht gespeichert: nach dem Neuladen steht die Seite wieder auf Diagramm. */
  viewMode: ViewMode = 'chart';
  /** Zuletzt geladene Summentabelle; null, bis der erste Tabellenabruf gelang. */
  totals: TotalsTable | null = null;
  /**
   * Aufgeklappte Jahre. Lebt ausserhalb der Tabelle, damit der 5-Minuten-Refresh
   * nicht zuklappt, was gerade jemand liest.
   */
  private readonly expandedYears = new Set<number>();

  /** Zuletzt geladene Serien - Grundlage fuer ein Umschalten ohne Nachladen. */
  private series: MeterConsumptionSeries[] = [];
  /**
   * Modus je Zaehlertyp. Lebt ausserhalb der Kacheln, weil Refresh und
   * Zeitraumwechsel die Kacheln neu bauen; nach einem Neuladen der Seite steht
   * bewusst alles wieder auf Verbrauch (wie Zeitraum und Aufloesung, die auch
   * nicht persistiert werden - kein localStorage).
   */
  private readonly modeByType = new Map<MeterType, TileMode>();

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
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = null;
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
    if (this.viewMode === 'table') {
      this.loadTotals(true);
    } else {
      this.load(this.activeRange, true);
    }
  }

  /** Wechselt zwischen Kachelraster und Summentabelle und laedt deren Daten. */
  setViewMode(mode: ViewMode): void {
    if (mode === this.viewMode) {
      return;
    }
    this.viewMode = mode;
    if (mode === 'table') {
      this.loadTotals();
    } else {
      this.load(this.activeRange);
    }
  }

  toggleYear(year: number): void {
    if (!this.expandedYears.delete(year)) {
      this.expandedYears.add(year);
    }
  }

  isYearExpanded(year: number): boolean {
    return this.expandedYears.has(year);
  }

  /** Schaltet eine Kachel um - rein lokal, kein Nachladen. */
  setMode(meterType: MeterType, mode: TileMode): void {
    if (this.modeOf(meterType) === mode) {
      return;
    }
    this.modeByType.set(meterType, mode);
    this.tiles = this.buildTiles(this.activeResolution);
  }

  private modeOf(meterType: MeterType): TileMode {
    return this.modeByType.get(meterType) ?? 'consumption';
  }

  private buildTiles(resolution: ConsumptionResolution): ConsumptionTile[] {
    return this.series.map(s => this.toTile(s, resolution, this.modeOf(s.meterType)));
  }

  private load(range: ConsumptionRange, silent = false): void {
    if (!silent) {
      this.isLoading = true;
      this.errorMessage = null;
    }
    const resolution = this.activeResolution;
    // Einen noch laufenden Abruf abbestellen, bevor der naechste startet. Ohne das
    // koennte eine aeltere, langsamere Antwort nach einer neueren eintreffen und die
    // bereits richtigen Kacheln mit Zahlen zum falschen Zeitraum ueberschreiben -
    // auf einer Wandanzeige faellt so etwas niemandem auf.
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = this.seriesService.getSeries(range).subscribe({
      next: series => {
        this.series = series;
        this.tiles = this.buildTiles(resolution);
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

  /**
   * Laedt Jahres- und Monatsreihe gemeinsam. Laeuft ueber dasselbe pendingRequest
   * wie das Diagramm: ein Moduswechsel bestellt den anderen Abruf ab, sonst koennte
   * eine spaete Antwort die gerade nicht sichtbare Ansicht mit Daten fuellen.
   */
  private loadTotals(silent = false): void {
    if (!silent) {
      this.isLoading = true;
      this.errorMessage = null;
    }
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = forkJoin({
      years: this.seriesService.getSeries('YEARS_ALL'),
      months: this.seriesService.getSeries('MONTHS_ALL')
    }).subscribe({
      next: ({ years, months }) => {
        const isFirstTable = this.totals === null;
        this.totals = buildTotalsTable(years, months);
        // Beim ersten Oeffnen das juengste Jahr aufklappen - das ist die Frage, mit
        // der man meistens kommt. Danach gilt, was der Nutzer auf- und zugeklappt hat.
        if (isFirstTable && this.totals.rows.length > 0) {
          this.expandedYears.add(this.totals.rows[0].year);
        }
        this.errorMessage = null;
        this.isLoading = false;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Verbrauchssummen:', error);
        this.isLoading = false;
        if (!silent) {
          this.errorMessage = 'Verbrauchsdaten konnten nicht geladen werden.';
        }
      }
    });
  }

  private toTile(
    series: MeterConsumptionSeries,
    resolution: ConsumptionResolution,
    mode: TileMode
  ): ConsumptionTile {
    const isCost = mode === 'cost';
    // Im Kostenmodus fliessen nur bepreiste Balken ins Diagramm - ein Balken ohne
    // Preis wuerde sonst als 0 EUR erscheinen, statt schlicht zu fehlen.
    const points = isCost ? series.points.filter(p => p.cost !== null) : series.points;
    const last = points.length > 0 ? points[points.length - 1] : null;
    return {
      key: series.meterType,
      meterType: series.meterType,
      name: MeterTypeUtils.getLabel(series.meterType),
      mode,
      currentLabel: isCost
        ? formatCost(last?.cost ?? null, series.currency)
        : formatConsumption(last?.consumption ?? null, series.unit),
      currentSuffix:
        resolution === 'YEAR' && last !== null && isRunningPeriod(last.periodStart, 'YEAR')
          ? 'bis heute'
          : null,
      comparison: isCost
        ? compareToPrevious(points, resolution, p => p.cost)
        : compareToPrevious(points, resolution),
      // Bewusst aus den GEFILTERTEN Punkten: die Legende "Blasse Balken sind
      // Schaetzwerte" soll zu dem passen, was tatsaechlich im Diagramm steht - ein
      // Schaetzwert ohne Preis ist im Kostenmodus gar nicht sichtbar.
      hasEstimated: points.some(p => p.estimated),
      missingPriceCount: isCost ? series.points.length - points.length : 0,
      hasNoCost: isCost && points.length === 0,
      options: this.chartOptionsFor(series, points, mode)
    };
  }

  /**
   * Balkendiagramm einer Kachel. Geschaetzte Balken bekommen dieselbe Farbe mit
   * geringerer Deckkraft - sichtbar, dass diese Woche nicht wirklich abgelesen wurde,
   * ohne sie aus der Summe zu nehmen.
   */
  private chartOptionsFor(
    series: MeterConsumptionSeries,
    points: readonly ConsumptionPoint[],
    mode: TileMode
  ): Record<string, unknown> {
    const color = MeterTypeUtils.getColor(series.meterType);
    const axisLabel = { color: AXIS_COLOR, fontSize: 12 };
    const isCost = mode === 'cost';
    const unitLabel = isCost ? '€' : series.unit;
    const valueOf = (p: ConsumptionPoint): number | null => (isCost ? p.cost : p.consumption);
    const format = (value: number): string =>
      isCost ? formatCost(value, series.currency) : formatConsumption(value, series.unit);

    return {
      grid: { left: 52, right: 12, top: 12, bottom: 30, containLabel: false },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value: number) => format(value)
      },
      xAxis: {
        type: 'category',
        data: points.map(p => p.label),
        axisLabel: { ...axisLabel, hideOverlap: true }
      },
      yAxis: {
        type: 'value',
        axisLabel: { ...axisLabel, formatter: `{value} ${unitLabel}` },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      },
      series: [
        {
          type: 'bar',
          data: points.map(p => ({
            value: [p.label, valueOf(p)],
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
