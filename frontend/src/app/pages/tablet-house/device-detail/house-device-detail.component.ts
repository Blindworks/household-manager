import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, OnChanges, OnDestroy, Output,
  SimpleChanges, inject
} from '@angular/core';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsCoreOption } from 'echarts/core';
import { Subscription } from 'rxjs';
import { DetailControl, DeviceDetail } from '../../../shared/house/device-detail.util';
import { HistoryRange, HistorySeries, HouseDeviceControlService } from '../house-device-control.service';

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

type HistoryState = 'none' | 'loading' | 'ready' | 'empty';
type TargetControl = Extract<DetailControl, { type: 'target' }>;
type SwitchControl = Extract<DetailControl, { type: 'switch' }>;

/** Sammelpause fuer −/+: erst nach so langer Ruhe geht EIN Sollwert raus. */
export const TARGET_DEBOUNCE_MS = 600;
/**
 * So lange zeigt das Feld nach einem erfolgreichen Schreiben weiter den lokalen
 * Sollwert, obwohl das Thermostat noch den alten meldet (der Hub meldet verzoegert).
 * Danach gilt wieder der Serverwert - die Anzeige klebt nie dauerhaft an einem Wert,
 * den das Geraet nicht hat.
 */
export const TARGET_GRACE_MS = 60_000;
export const UNREACHABLE = 'Gerät nicht erreichbar';
export const LAMP_OFF = 'Lampe ist aus';

const SERIES_COLORS = ['#8fd3ff', '#ffb35b'];

/**
 * Geraete-Detail der Hausansicht: aktueller Wert, Verlauf, Bedienung.
 *
 * `detail` wird von der Seite bei jedem Refresh neu gesetzt. Der Verlauf wird
 * nur neu geladen, wenn sich Geraet oder Verlaufsquelle aendern; eine offene
 * −/+-Eingabe ueberlebt den Refresh. Der Schalter zeigt immer den Zustand aus
 * `detail` - der neue Stand kommt mit dem naechsten Refresh der Seite.
 */
@Component({
  selector: 'app-house-device-detail',
  standalone: true,
  imports: [NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './house-device-detail.component.html',
  styleUrl: './house-device-detail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class HouseDeviceDetailComponent implements OnChanges, OnDestroy {
  private readonly control = inject(HouseDeviceControlService);
  private readonly cdr = inject(ChangeDetectorRef);

  @Input({ required: true }) detail!: DeviceDetail;
  /** Aktuelle Helligkeit (0–100) einer dimmbaren Lampe; die Seite fuellt sie ueber `lightDevice`. */
  @Input() brightness: number | null = null;
  @Output() readonly closed = new EventEmitter<void>();

  readonly ranges: { range: HistoryRange; label: string }[] = [
    { range: 'DAY', label: '24 h' },
    { range: 'WEEK', label: '7 Tage' }
  ];

  range: HistoryRange = 'DAY';
  historyState: HistoryState = 'none';
  chartOptions: EChartsCoreOption | null = null;

  /**
   * Lokal gewaehlter Sollwert (nur Anzeige). Gilt waehrend der Eingabe, danach bis
   * `detail` ihn meldet, sich vom Wert vor dem Schreiben wegbewegt oder
   * {@link TARGET_GRACE_MS} vergangen sind.
   */
  localTarget: number | null = null;
  switchPending = false;
  confirmingOff = false;
  error: string | null = null;

  private historyKey: string | null = null;
  private historySub?: Subscription;
  private targetTimer: ReturnType<typeof setTimeout> | null = null;
  /** Thermostat-Id wird beim Tippen gemerkt, nie beim Senden gelesen: `detail` kann inzwischen ein anderes Geraet sein. */
  private pendingTarget: { thermostatId: number; value: number } | null = null;
  /** Serverwert zum Zeitpunkt des Schreibens; undefined = kein abgeschlossenes Schreiben offen. */
  private targetBeforeWrite: number | null | undefined = undefined;
  private graceTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['detail']) {
      return;
    }
    const previous = changes['detail'].previousValue as DeviceDetail | undefined;
    if (previous?.deviceId !== this.detail.deviceId) {
      this.resetForNewDevice();
    } else if (this.targetTimer === null && this.targetBeforeWrite !== undefined) {
      const reported = this.targetControl?.target ?? null;
      if (reported === this.localTarget || reported !== this.targetBeforeWrite) {
        this.dropLocalTarget();
      }
    }
    const key = this.detail.history ? `${this.detail.deviceId}|${JSON.stringify(this.detail.history)}` : null;
    if (key !== this.historyKey) {
      this.historyKey = key;
      this.loadHistory();
    }
  }

  ngOnDestroy(): void {
    this.historySub?.unsubscribe();
    // Bewusst: wer das Feld innerhalb der Sammelpause schliesst, bekommt seinen letzten Sollwert trotzdem gesendet.
    this.flushTarget();
    this.clearGrace();
  }

  get targetControl(): TargetControl | null {
    return this.detail.control?.type === 'target' ? this.detail.control : null;
  }

  get switchControl(): SwitchControl | null {
    return this.detail.control?.type === 'switch' ? this.detail.control : null;
  }

  get shownTarget(): number | null {
    return this.localTarget ?? this.targetControl?.target ?? null;
  }

  formatTarget(value: number | null): string {
    return value === null ? '–' : value.toFixed(1).replace('.', ',') + '°';
  }

  selectRange(range: HistoryRange): void {
    if (range !== this.range) {
      this.range = range;
      this.loadHistory();
    }
  }

  stepTarget(direction: 1 | -1): void {
    const control = this.targetControl;
    const base = this.shownTarget;
    if (!control || base === null) {
      return;
    }
    const next = Math.round((base + direction * control.step) / control.step) * control.step;
    this.localTarget = Math.min(control.max, Math.max(control.min, next));
    this.pendingTarget = { thermostatId: control.thermostatId, value: this.localTarget };
    this.targetBeforeWrite = undefined;
    this.clearGrace();
    this.error = null;
    if (this.targetTimer !== null) {
      clearTimeout(this.targetTimer);
    }
    this.targetTimer = setTimeout(() => this.flushTarget(), TARGET_DEBOUNCE_MS);
  }

  onSwitch(): void {
    const control = this.switchControl;
    if (!control || control.on === null || this.switchPending) {
      return;
    }
    if (control.on && control.confirmOff) {
      this.confirmingOff = true;
      return;
    }
    this.sendSwitch(control.entityId, !control.on);
  }

  confirmOff(): void {
    const control = this.switchControl;
    this.confirmingOff = false;
    if (control) {
      // Der Dienst liest den Zustand frisch und schaltet nur, wenn er noch `on` ist.
      this.sendSwitch(control.entityId, false);
    }
  }

  cancelOff(): void {
    this.confirmingOff = false;
  }

  onBrightnessChange(event: Event): void {
    const control = this.switchControl;
    const slider = event.target as HTMLInputElement;
    const value = Number(slider.value);
    if (!control || !Number.isFinite(value)) {
      return;
    }
    this.error = null;
    // Angular setzt den DOM-Wert nicht zurueck, solange sich `brightness` nicht aendert - deshalb von Hand.
    const snapBack = (message: string) => {
      slider.value = String(this.brightness ?? 100);
      this.fail(message);
    };
    this.control.setBrightness(control.entityId, value).subscribe({
      next: outcome => {
        if (outcome === 'lamp-off') {
          snapBack(LAMP_OFF);
        }
      },
      error: () => snapBack(UNREACHABLE)
    });
  }

  close(): void {
    this.closed.emit();
  }

  private sendSwitch(entityId: string, on: boolean): void {
    this.switchPending = true;
    this.error = null;
    this.control.setSwitch(entityId, on).subscribe({
      next: outcome => {
        this.switchPending = false;
        this.error = outcome === 'unavailable' ? UNREACHABLE : null;
        this.cdr.markForCheck();
      },
      error: () => {
        this.switchPending = false;
        this.fail();
      }
    });
  }

  private flushTarget(): void {
    if (this.targetTimer !== null) {
      clearTimeout(this.targetTimer);
      this.targetTimer = null;
    }
    const pending = this.pendingTarget;
    if (!pending) {
      return;
    }
    this.pendingTarget = null;
    const sameDevice = () => this.targetControl?.thermostatId === pending.thermostatId;
    if (sameDevice()) {
      this.targetBeforeWrite = this.targetControl?.target ?? null;
    }
    this.control.setTarget(pending.thermostatId, pending.value).subscribe({
      next: () => {
        if (sameDevice() && this.localTarget === pending.value) {
          this.startGrace();
        }
      },
      error: () => {
        if (sameDevice()) {
          this.dropLocalTarget();
          this.fail();
        }
      }
    });
  }

  private startGrace(): void {
    this.clearGrace();
    this.graceTimer = setTimeout(() => {
      this.graceTimer = null;
      this.dropLocalTarget();
      this.cdr.markForCheck();
    }, TARGET_GRACE_MS);
  }

  private clearGrace(): void {
    if (this.graceTimer !== null) {
      clearTimeout(this.graceTimer);
      this.graceTimer = null;
    }
  }

  private dropLocalTarget(): void {
    this.localTarget = null;
    this.targetBeforeWrite = undefined;
    this.clearGrace();
  }

  private resetForNewDevice(): void {
    this.flushTarget();
    this.dropLocalTarget();
    this.confirmingOff = false;
    this.switchPending = false;
    this.error = null;
    this.range = 'DAY';
  }

  private loadHistory(): void {
    this.historySub?.unsubscribe();
    this.chartOptions = null;
    const source = this.detail.history;
    if (!source) {
      this.historyState = 'none';
      return;
    }
    this.historyState = 'loading';
    this.historySub = this.control.loadHistory(source, this.range).subscribe({
      next: series => {
        const usable = series?.filter(s => s.points.length > 0) ?? [];
        this.chartOptions = usable.length > 0 ? this.buildChart(usable) : null;
        this.historyState = this.chartOptions ? 'ready' : 'empty';
        this.cdr.markForCheck();
      },
      error: () => {
        this.historyState = 'empty';
        this.cdr.markForCheck();
      }
    });
  }

  private fail(message = UNREACHABLE): void {
    this.error = message;
    this.cdr.markForCheck();
  }

  private buildChart(series: HistorySeries[]): EChartsCoreOption {
    return {
      backgroundColor: 'transparent',
      grid: { left: 8, right: 12, top: series.length > 1 ? 28 : 12, bottom: 8, containLabel: true },
      tooltip: { trigger: 'axis' },
      legend: series.length > 1
        ? { top: 0, textStyle: { color: '#e8eefc' }, data: series.map(s => s.name) }
        : { show: false },
      xAxis: {
        type: 'time',
        axisLabel: { color: 'rgba(232, 238, 252, 0.7)' },
        axisLine: { lineStyle: { color: 'rgba(91, 140, 255, 0.4)' } }
      },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { color: 'rgba(232, 238, 252, 0.7)' },
        splitLine: { lineStyle: { color: 'rgba(91, 140, 255, 0.15)' } }
      },
      series: series.map((s, index) => ({
        name: s.name,
        type: 'line',
        showSymbol: false,
        connectNulls: false,
        data: s.points.map(p => [p.at, p.value]),
        lineStyle: { width: 2, type: s.dashed ? 'dashed' : 'solid', color: SERIES_COLORS[index % SERIES_COLORS.length] },
        itemStyle: { color: SERIES_COLORS[index % SERIES_COLORS.length] }
      }))
    };
  }
}
