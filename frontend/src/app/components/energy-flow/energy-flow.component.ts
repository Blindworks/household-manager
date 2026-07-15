import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, DataZoomComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { EnergyLive } from '../../models/energy-live.model';
import { AnkerSolixLive } from '../../models/ankersolix.model';
import { EnergyHistoryService } from '../../services/energy-history.service';
import { ENERGY_METRIC_DEFINITIONS, EnergyMetric } from '../../models/energy-history.model';

echarts.use([LineChart, GridComponent, TooltipComponent, DataZoomComponent, CanvasRenderer]);

/**
 * Wiederverwendbares Live-Energiefluss-Diagramm: Balkonkraftwerke, PV, Hausverbrauch,
 * Stromnetz sowie Anker-Speicher als animiertes Kartendiagramm.
 *
 * Die Live-Werte ({@link energyLive}, {@link liveData}) werden bewusst als Inputs
 * durchgereicht: Die aufrufende Seite besitzt die SSE-Abonnements, sodass pro Seite
 * nur genau ein Abonnent je Live-Service existiert. Den Metrik-Verlauf (Klick auf eine
 * Karte) laedt die Komponente selbst per HTTP ueber den {@link EnergyHistoryService}.
 */
@Component({
  selector: 'app-energy-flow',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './energy-flow.component.html',
  styleUrl: './energy-flow.component.scss'
})
export class EnergyFlowComponent {
  private readonly energyHistoryService = inject(EnergyHistoryService);

  /** Live-Werte fuer BKW, PV, Hausverbrauch und Netz (von der Elternseite). */
  @Input() energyLive: EnergyLive | null = null;
  /** Live-Werte des Anker-Speichers (PV-Eingang, Akku-Stand). */
  @Input() liveData: AnkerSolixLive | null = null;

  selectedMetric: EnergyMetric | null = null;
  historyChartOptions: Record<string, unknown> | null = null;
  historyLoading = false;
  historyError = '';
  historyHours = 24;
  readonly metricDefinitions = ENERGY_METRIC_DEFINITIONS;

  get batteryIsCharging(): boolean {
    return (this.liveData?.batteryPowerW ?? 0) > 0;
  }

  get batteryIsIdle(): boolean {
    return (this.liveData?.batteryPowerW ?? 0) === 0;
  }

  get pvFlowSpeed(): number {
    return this.flowSpeed(this.energyLive?.pvTotalW);
  }

  get bkwAltFlowSpeed(): number {
    return this.flowSpeed(this.energyLive?.bkwAltW);
  }

  get bkwNeuFlowSpeed(): number {
    return this.flowSpeed(this.energyLive?.bkwNeuW);
  }

  get gridFlowSpeed(): number {
    return this.flowSpeed(Math.abs(this.energyLive?.gridW ?? 0));
  }

  get gridAbsW(): number {
    return Math.abs(this.energyLive?.gridW ?? 0);
  }

  /** Animationsgeschwindigkeit einer Flusslinie relativ zur Leistung (0 = keine Animation). */
  private flowSpeed(watt: number | null | undefined): number {
    const w = watt ?? 0;
    if (w <= 0) return 0;
    return Math.max(0.6, Math.min(3, w / 400));
  }

  selectMetric(metric: EnergyMetric): void {
    if (this.selectedMetric === metric) {
      this.selectedMetric = null;
      this.historyChartOptions = null;
      return;
    }
    this.selectedMetric = metric;
    this.loadHistory();
  }

  changeHistoryHours(hours: number): void {
    this.historyHours = hours;
    if (this.selectedMetric) {
      this.loadHistory();
    }
  }

  loadHistory(): void {
    if (!this.selectedMetric) return;
    const metric = this.selectedMetric;
    this.historyLoading = true;
    this.historyError = '';

    this.energyHistoryService.getHistory(metric, this.historyHours).subscribe({
      next: (points) => {
        this.historyLoading = false;
        this.historyChartOptions = this.buildHistoryChart(metric, points);
      },
      error: (err) => {
        this.historyLoading = false;
        this.historyError = 'Verlauf konnte nicht geladen werden';
        console.error('Energy history load failed:', err);
      }
    });
  }

  get selectedMetricDefinition() {
    return this.selectedMetric ? this.metricDefinitions[this.selectedMetric] : null;
  }

  private buildHistoryChart(metric: EnergyMetric, points: { timestamp: string; value: number | null }[]): Record<string, unknown> {
    const def = this.metricDefinitions[metric];
    const xData = points.map(p => this.formatTimestamp(p.timestamp));
    const yData = points.map(p => p.value);

    return {
      grid: { left: 56, right: 24, top: 32, bottom: 56, containLabel: false },
      tooltip: {
        trigger: 'axis',
        formatter: (params: { axisValue: string; data: number }[]) => {
          if (!params.length) return '';
          const p = params[0];
          const val = p.data == null ? '–' : `${Number(p.data).toFixed(def.unit === '%' ? 1 : 0)} ${def.unit}`;
          return `${p.axisValue}<br/><strong>${def.label}:</strong> ${val}`;
        }
      },
      xAxis: {
        type: 'category',
        data: xData,
        axisLine: { lineStyle: { color: 'rgba(51,65,85,0.4)' } },
        axisLabel: { color: '#94a3b8', fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
        axisLabel: { color: '#94a3b8', fontSize: 11, formatter: (v: number) => `${v} ${def.unit}` }
      },
      dataZoom: [
        { type: 'inside', start: 0, end: 100 },
        { type: 'slider', start: 0, end: 100, height: 22, bottom: 8, borderColor: 'transparent' }
      ],
      series: [{
        name: def.label,
        type: 'line',
        data: yData,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 2.5, color: def.color },
        areaStyle: { color: def.color, opacity: 0.15 },
        connectNulls: true
      }]
    };
  }

  private formatTimestamp(iso: string): string {
    const d = new Date(iso);
    const hh = d.getHours().toString().padStart(2, '0');
    const mm = d.getMinutes().toString().padStart(2, '0');
    const day = d.getDate().toString().padStart(2, '0');
    const month = (d.getMonth() + 1).toString().padStart(2, '0');
    return `${day}.${month} ${hh}:${mm}`;
  }
}
