import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { NetworkService } from '../../services/network.service';
import {
  NetworkDeviceStatus,
  NetworkHistoryResponse,
  NetworkStatusResponse,
  TimeRange
} from '../../models/network.model';
import { formatLastSeen, insertGaps } from './network-view.util';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface RangeOption {
  value: TimeRange;
  label: string;
}

/**
 * Bucket-Laenge je Zeitraum in Minuten - gespiegelt aus `SeriesRange` im
 * Backend (Temperatur-/Luftqualitaets-Serien). Nur hier gebraucht: eine Luecke
 * im Latenzverlauf gilt ab dem Dreifachen der Bucket-Laenge des Zeitraums.
 */
const BUCKET_MINUTES: Record<TimeRange, number> = { DAY: 5, WEEK: 30, MONTH: 120 };

const AXIS_COLOR = '#94a3b8';
const ONLINE_COLOR = '#22c55e';
const OFFLINE_COLOR = '#ef4444';
const DOWNLOAD_COLOR = '#aac7ff';
const UPLOAD_COLOR = '#fbbf24';

/**
 * Netzwerkuebersicht fuer das Wandtablet: Status, Speed- und Latenzverlauf und
 * die pflegbaren Geraete in einem 2x2-Raster, alles gleichzeitig sichtbar.
 */
@Component({
  selector: 'app-tablet-network',
  standalone: true,
  imports: [CommonModule, TabletShellComponent, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './tablet-network.component.html',
  styleUrl: './tablet-network.component.scss'
})
export class TabletNetworkComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 60_000;

  private readonly networkService = inject(NetworkService);
  private refreshTimer: number | null = null;
  /** Laufender Historien-Abruf, damit ein Zeitraumwechsel ihn abloesen kann. */
  private pendingHistoryRequest: Subscription | null = null;

  readonly ranges: RangeOption[] = [
    { value: 'DAY', label: '24 Stunden' },
    { value: 'WEEK', label: '7 Tage' },
    { value: 'MONTH', label: '30 Tage' }
  ];

  activeRange: TimeRange = 'WEEK';

  statusData: NetworkStatusResponse | null = null;
  statusError: string | null = null;

  historyData: NetworkHistoryResponse | null = null;
  historyError: string | null = null;
  /**
   * Einmalig aus historyData berechnete Chart-Optionen. Bewusst als Feld statt
   * als Methodenaufruf im Template: `[options]="speedChartOptions()"` liefert
   * bei JEDER Change-Detection eine neue Objektreferenz, und
   * NgxEchartsDirective.ngOnChanges reagiert darauf mit `setOption(...,
   * notMerge: true)` - ein kompletter Redraw bei jedem CD-Zyklus, sichtbares
   * Flackern auf dem dauerlaufenden Wandtablet. Die drei Schwesteransichten
   * (tablet-air-quality, tablet-consumption, tablet-toni) berechnen ihre
   * Optionen ebenfalls nur bei Datenaenderung.
   */
  speedChartOptions: Record<string, unknown> | null = null;
  latencyChartOptions: Record<string, unknown> | null = null;

  speedtestRunning = false;
  speedtestError: string | null = null;

  ngOnInit(): void {
    this.loadStatus(false);
    this.loadHistory(this.activeRange, false);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletNetworkComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
    this.pendingHistoryRequest?.unsubscribe();
    this.pendingHistoryRequest = null;
  }

  get devices(): NetworkDeviceStatus[] {
    return this.statusData?.devices ?? [];
  }

  setRange(range: TimeRange): void {
    if (range === this.activeRange) {
      return;
    }
    this.activeRange = range;
    this.loadHistory(range, false);
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.loadStatus(true);
    this.loadHistory(this.activeRange, true);
  }

  runSpeedtest(): void {
    this.speedtestRunning = true;
    this.speedtestError = null;
    this.networkService.runSpeedtest().subscribe({
      next: () => {
        this.speedtestRunning = false;
        // Ein manuell ausgeloester Speedtest darf eine Fehlermeldung zeigen, ist
        // also kein stiller Hintergrund-Refresh.
        this.loadStatus(false);
      },
      error: (error: HttpErrorResponse) => {
        console.error('Speedtest fehlgeschlagen:', error);
        this.speedtestRunning = false;
        this.speedtestError = error.error?.message ?? 'Speedtest fehlgeschlagen.';
      }
    });
  }

  deviceStatusLabel(device: NetworkDeviceStatus): string {
    return device.reachable ? 'erreichbar' : 'nicht erreichbar';
  }

  deviceLastSeenLabel(device: NetworkDeviceStatus): string {
    return formatLastSeen(device.lastSeenAt);
  }

  private loadStatus(silent: boolean): void {
    if (!silent) {
      this.statusError = null;
    }
    this.networkService.getStatus().subscribe({
      next: status => {
        this.statusData = status;
        this.statusError = null;
      },
      error: (error: HttpErrorResponse) => {
        console.error('Fehler beim Laden des Netzwerkstatus:', error);
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte
        // nicht durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer
        // Wandanzeige mehr wert als gar keine.
        if (!silent) {
          this.statusError = 'Netzwerkstatus konnte nicht geladen werden.';
        }
      }
    });
  }

  private loadHistory(range: TimeRange, silent: boolean): void {
    if (!silent) {
      this.historyError = null;
    }
    // Einen noch laufenden Abruf abbestellen, bevor der naechste startet - sonst
    // koennte eine aeltere, langsamere Antwort nach einer neueren eintreffen und
    // die schon richtigen Graphen mit Werten zum falschen Zeitraum ueberschreiben.
    this.pendingHistoryRequest?.unsubscribe();
    this.pendingHistoryRequest = this.networkService.getHistory(range).subscribe({
      next: history => {
        this.historyData = history;
        this.historyError = null;
        // Nur hier, bei tatsaechlicher Datenaenderung, neu berechnen - nicht bei
        // jeder Change-Detection (siehe Kommentar an den Feldern oben).
        this.speedChartOptions = this.buildSpeedChartOptions(history);
        this.latencyChartOptions = this.buildLatencyChartOptions(history, range);
      },
      error: (error: HttpErrorResponse) => {
        console.error('Fehler beim Laden der Netzwerkhistorie:', error);
        if (!silent) {
          this.historyError = 'Verlaufsdaten konnten nicht geladen werden.';
        }
      }
    });
  }

  get statusColor(): string {
    if (!this.statusData || this.statusData.lastCheckedAt === null) {
      return AXIS_COLOR;
    }
    return this.statusData.online ? ONLINE_COLOR : OFFLINE_COLOR;
  }

  get statusLabel(): string {
    if (!this.statusData || this.statusData.lastCheckedAt === null) {
      return 'Noch keine Messung';
    }
    return this.statusData.online ? 'Online' : 'Offline';
  }

  private buildSpeedChartOptions(history: NetworkHistoryResponse): Record<string, unknown> {
    const points = history.speedtests;
    const axisLabel = { color: AXIS_COLOR, fontSize: 13 };

    return {
      grid: { left: 56, right: 16, top: 36, bottom: 28, containLabel: false },
      tooltip: { trigger: 'axis' },
      legend: {
        top: 0,
        textStyle: { color: AXIS_COLOR },
        data: ['Download', 'Upload']
      },
      xAxis: { type: 'time', axisLabel },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { ...axisLabel, formatter: '{value} Mbit/s' },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      },
      series: [
        {
          name: 'Download',
          type: 'line',
          showSymbol: true,
          data: points.map(point => [point.time, point.downloadMbps]),
          lineStyle: { width: 2.5, color: DOWNLOAD_COLOR },
          itemStyle: { color: DOWNLOAD_COLOR }
        },
        {
          name: 'Upload',
          type: 'line',
          showSymbol: true,
          data: points.map(point => [point.time, point.uploadMbps]),
          lineStyle: { width: 2.5, color: UPLOAD_COLOR },
          itemStyle: { color: UPLOAD_COLOR }
        }
      ]
    };
  }

  private buildLatencyChartOptions(
    history: NetworkHistoryResponse,
    range: TimeRange
  ): Record<string, unknown> {
    const gapAware = insertGaps(history.latency, BUCKET_MINUTES[range]);
    const axisLabel = { color: AXIS_COLOR, fontSize: 13 };

    return {
      grid: { left: 56, right: 16, top: 12, bottom: 28, containLabel: false },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'time', axisLabel },
      yAxis: {
        type: 'value',
        scale: true,
        axisLabel: { ...axisLabel, formatter: '{value} ms' },
        splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.25)', type: 'dashed' } }
      },
      series: [{
        name: 'Latenz',
        type: 'line',
        smooth: true,
        showSymbol: false,
        connectNulls: false,
        data: gapAware.map(point => [point.time, point.value]),
        lineStyle: { width: 2.5, color: DOWNLOAD_COLOR }
      }]
    };
  }
}
