import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, Subscription } from 'rxjs';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, DataZoomComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { RouterLink } from '@angular/router';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { AnkerSolixAutoControlStatus, AnkerSolixDeviceParams, AnkerSolixEnergyDay, AnkerSolixLive } from '../../models/ankersolix.model';
import { EnergyLiveService } from '../../services/energy-live.service';
import { EnergyLive } from '../../models/energy-live.model';
import { EnergyHistoryService } from '../../services/energy-history.service';
import { ENERGY_METRIC_DEFINITIONS, EnergyMetric } from '../../models/energy-history.model';

echarts.use([BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, CanvasRenderer]);

@Component({
  selector: 'app-energy',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective, RouterLink],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './energy.component.html',
  styleUrl: './energy.component.scss'
})
export class EnergyComponent implements OnInit, OnDestroy {
  private readonly ankerSolixService = inject(AnkerSolixService);
  private readonly energyLiveService = inject(EnergyLiveService);
  private readonly energyHistoryService = inject(EnergyHistoryService);

  liveData: AnkerSolixLive | null = null;
  energyLive: EnergyLive | null = null;
  deviceParams: AnkerSolixDeviceParams | null = null;
  energyData: AnkerSolixEnergyDay | null = null;
  autoControlStatus: AnkerSolixAutoControlStatus | null = null;
  chartOptions: Record<string, unknown> | null = null;
  weeklyChartOptions: Record<string, unknown> | null = null;

  selectedMetric: EnergyMetric | null = null;
  historyChartOptions: Record<string, unknown> | null = null;
  historyLoading = false;
  historyError = '';
  historyHours = 24;
  readonly metricDefinitions = ENERGY_METRIC_DEFINITIONS;

  outputWatts = 0;
  selectedDate = new Date().toISOString().substring(0, 10);

  connectionStatus$ = this.ankerSolixService.connectionStatus$;
  isSettingPower = false;
  powerSetSuccess = false;
  powerSetError = '';

  private liveSubscription: Subscription | null = null;
  private energyLiveSubscription: Subscription | null = null;
  private autoControlInterval: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.startLiveStream();
    this.startEnergyLiveStream();
    this.loadDeviceParams();
    this.loadEnergy();
    this.loadWeekData();
    this.loadAutoControlStatus();
    this.autoControlInterval = setInterval(() => this.loadAutoControlStatus(), 30000);
  }

  ngOnDestroy(): void {
    this.liveSubscription?.unsubscribe();
    this.energyLiveSubscription?.unsubscribe();
    if (this.autoControlInterval) {
      clearInterval(this.autoControlInterval);
    }
    this.ankerSolixService.disconnectLive();
    this.energyLiveService.disconnect();
  }

  loadEnergy(): void {
    this.ankerSolixService.getEnergyDay(this.selectedDate).subscribe({
      next: (data) => {
        this.energyData = data;
        this.updateChart(data);
      },
      error: (err) => console.error('Fehler beim Laden der Energiedaten:', err)
    });
  }

  loadWeekData(): void {
    const today = new Date();
    const dayOfWeek = today.getDay();
    const daysFromMonday = (dayOfWeek + 6) % 7;
    const monday = new Date(today);
    monday.setDate(today.getDate() - daysFromMonday);

    const dates = Array.from({ length: 7 }, (_, i) => {
      const d = new Date(monday);
      d.setDate(monday.getDate() + i);
      return d.toISOString().substring(0, 10);
    });

    forkJoin(dates.map(d => this.ankerSolixService.getEnergyDay(d))).subscribe({
      next: (results) => this.updateWeeklyChart(results, dates),
      error: (err) => console.error('Fehler beim Laden der Wochendaten:', err)
    });
  }

  setOutputPower(): void {
    this.isSettingPower = true;
    this.powerSetSuccess = false;
    this.powerSetError = '';

    this.ankerSolixService.setOutputPower(this.outputWatts).subscribe({
      next: () => {
        this.isSettingPower = false;
        this.powerSetSuccess = true;
        setTimeout(() => { this.powerSetSuccess = false; }, 3000);
      },
      error: (err) => {
        this.isSettingPower = false;
        this.powerSetError = 'Fehler beim Setzen der Ausgangsleistung';
        console.error('Fehler beim Setzen der Ausgangsleistung:', err);
      }
    });
  }

  get batteryIsCharging(): boolean {
    return (this.liveData?.batteryPowerW ?? 0) > 0;
  }

  get batteryIsIdle(): boolean {
    return (this.liveData?.batteryPowerW ?? 0) === 0;
  }

  get pvFlowSpeed(): number {
    const w = this.energyLive?.pvTotalW ?? 0;
    if (w <= 0) return 0;
    return Math.max(0.6, Math.min(3, w / 400));
  }

  get bkwAltFlowSpeed(): number {
    const w = this.energyLive?.bkwAltW ?? 0;
    if (w <= 0) return 0;
    return Math.max(0.6, Math.min(3, w / 400));
  }

  get bkwNeuFlowSpeed(): number {
    const w = this.energyLive?.bkwNeuW ?? 0;
    if (w <= 0) return 0;
    return Math.max(0.6, Math.min(3, w / 400));
  }

  get gridFlowSpeed(): number {
    const w = Math.abs(this.energyLive?.gridW ?? 0);
    if (w <= 0) return 0;
    return Math.max(0.6, Math.min(3, w / 400));
  }

  get gridAbsW(): number {
    return Math.abs(this.energyLive?.gridW ?? 0);
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

  formatConnectionStatus(status: string): string {
    switch (status) {
      case 'connected': return 'Verbunden';
      case 'connecting': return 'Verbinde...';
      case 'error': return 'Verbindungsfehler';
      default: return 'Getrennt';
    }
  }

  private loadAutoControlStatus(): void {
    this.ankerSolixService.getAutoControlStatus().subscribe({
      next: (status) => { this.autoControlStatus = status; },
      error: () => { this.autoControlStatus = null; }
    });
  }

  private startLiveStream(): void {
    this.liveSubscription = this.ankerSolixService.getLiveStream().subscribe({
      next: (data) => { this.liveData = data; },
      error: (err) => { console.error('SSE-Fehler:', err); }
    });
  }

  private startEnergyLiveStream(): void {
    this.energyLiveSubscription = this.energyLiveService.getLiveStream().subscribe({
      next: (data) => { this.energyLive = data; },
      error: (err) => { console.error('Energy SSE-Fehler:', err); }
    });
  }

  private loadDeviceParams(): void {
    this.ankerSolixService.getDeviceParams().subscribe({
      next: (params) => {
        this.deviceParams = params;
        this.outputWatts = params.currentOutputW;
      },
      error: (err) => console.error('Fehler beim Laden der Geräteparameter:', err)
    });
  }

  private updateWeeklyChart(data: AnkerSolixEnergyDay[], dates: string[]): void {
    const dayLabels = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
    const todayStr = new Date().toISOString().substring(0, 10);

    this.weeklyChartOptions = {
      grid: { left: 64, right: 16, top: 24, bottom: 36, containLabel: false },
      tooltip: {
        trigger: 'item',
        formatter: (params: { name: string; value: number }) => `${params.name}: ${params.value} kWh`
      },
      xAxis: {
        type: 'category',
        data: dayLabels,
        axisLine: { lineStyle: { color: 'rgba(51,65,85,0.4)' } },
        axisLabel: { color: '#94a3b8', fontSize: 12 }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
        axisLabel: { color: '#94a3b8', fontSize: 11, formatter: (v: number) => `${v}` }
      },
      series: [{
        name: 'PV kWh',
        type: 'bar',
        data: data.map((d, i) => ({
          value: d.pvEnergyKwh,
          itemStyle: {
            color: dates[i] === todayStr ? '#0d631b' : '#10b981',
            borderRadius: [4, 4, 0, 0]
          }
        })),
        barMaxWidth: 48
      }]
    };
  }

  private updateChart(data: AnkerSolixEnergyDay): void {
    this.chartOptions = {
      grid: { left: 72, right: 24, top: 40, bottom: 36, containLabel: false },
      tooltip: {
        trigger: 'item',
        formatter: (params: { name: string; value: number }) => `${params.name}: ${params.value} kWh`
      },
      legend: {
        top: 8,
        textStyle: { color: '#94a3b8', fontSize: 12 }
      },
      xAxis: {
        type: 'category',
        data: ['PV-Erzeugung', 'Akku Laden', 'Akku Entladen'],
        axisLine: { lineStyle: { color: 'rgba(51, 65, 85, 0.7)' } },
        axisTick: { alignWithLabel: true, lineStyle: { color: 'rgba(51, 65, 85, 0.75)' } },
        axisLabel: { color: '#94a3b8', fontSize: 12 }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 11,
          formatter: (v: number) => `${v} kWh`
        }
      },
      series: [{
        name: 'Energie',
        type: 'bar',
        data: [
          { value: data.pvEnergyKwh, itemStyle: { color: '#f59e0b', borderRadius: [4, 4, 0, 0] } },
          { value: data.batteryChargeKwh, itemStyle: { color: '#10b981', borderRadius: [4, 4, 0, 0] } },
          { value: data.batteryDischargeKwh, itemStyle: { color: '#3b82f6', borderRadius: [4, 4, 0, 0] } }
        ],
        barMaxWidth: 80
      }]
    };
  }
}
