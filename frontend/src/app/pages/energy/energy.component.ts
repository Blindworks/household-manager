import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, Subscription } from 'rxjs';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { RouterLink } from '@angular/router';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { AnkerSolixDeviceParams, AnkerSolixEnergyDay, AnkerSolixLive } from '../../models/ankersolix.model';
import { TasmotaLiveService } from '../../services/tasmota-live.service';
import { TasmotaLiveReading } from '../../models/tasmota-live.model';
import { ShellyService } from '../../services/shelly.service';
import { ShellyLiveService } from '../../services/shelly-live.service';
import { ShellyStatus } from '../../models/shelly.model';

echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

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
  private readonly tasmotaLiveService = inject(TasmotaLiveService);
  private readonly shellyService = inject(ShellyService);
  private readonly shellyLiveService = inject(ShellyLiveService);

  liveData: AnkerSolixLive | null = null;
  tasmotaReading: TasmotaLiveReading | null = null;
  deviceParams: AnkerSolixDeviceParams | null = null;
  energyData: AnkerSolixEnergyDay | null = null;
  chartOptions: Record<string, unknown> | null = null;
  weeklyChartOptions: Record<string, unknown> | null = null;

  shellyDevices: ShellyStatus[] = [];

  outputWatts = 0;
  selectedDate = new Date().toISOString().substring(0, 10);

  connectionStatus$ = this.ankerSolixService.connectionStatus$;
  isSettingPower = false;
  powerSetSuccess = false;
  powerSetError = '';

  private liveSubscription: Subscription | null = null;
  private tasmotaSubscription: Subscription | null = null;
  private shellySubscription: Subscription | null = null;

  ngOnInit(): void {
    this.startLiveStream();
    this.startTasmotaStream();
    this.startShellyStream();
    this.loadDeviceParams();
    this.loadEnergy();
    this.loadWeekData();
  }

  ngOnDestroy(): void {
    this.liveSubscription?.unsubscribe();
    this.tasmotaSubscription?.unsubscribe();
    this.shellySubscription?.unsubscribe();
    this.ankerSolixService.disconnectLive();
    this.tasmotaLiveService.disconnect();
    this.shellyLiveService.disconnect();
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

  get gridIsImporting(): boolean {
    return (this.tasmotaReading?.momentaneWirkleistung ?? 0) >= 0;
  }

  get shellyTotalPower(): number {
    return this.shellyDevices
      .filter(d => d.reachable)
      .reduce((sum, d) => sum + (d.power ?? 0), 0);
  }

  get bkwAltPower(): number {
    return this.shellyDevices.find(d => d.deviceName === 'Balkonkraftwerk-Alt')?.power ?? 0;
  }

  get bkwNeuPower(): number {
    return this.shellyDevices.find(d => d.deviceName === 'Balkonkraftwerk-Neu')?.power ?? 0;
  }

  toggleShelly(device: ShellyStatus): void {
    const action = device.output
      ? this.shellyService.turnOff(device.deviceName)
      : this.shellyService.turnOn(device.deviceName);

    action.subscribe({
      error: (err) => console.error('Fehler beim Schalten von', device.deviceName, err)
    });
  }

  get hausverbrauchW(): number | null {
    if (this.tasmotaReading === null || this.shellyDevices.length === 0) {
      return null;
    }
    return this.bkwAltPower + this.bkwNeuPower + this.tasmotaReading.momentaneWirkleistung;
  }

  formatConnectionStatus(status: string): string {
    switch (status) {
      case 'connected': return 'Verbunden';
      case 'connecting': return 'Verbinde...';
      case 'error': return 'Verbindungsfehler';
      default: return 'Getrennt';
    }
  }

  private startLiveStream(): void {
    this.liveSubscription = this.ankerSolixService.getLiveStream().subscribe({
      next: (data) => { this.liveData = data; },
      error: (err) => { console.error('SSE-Fehler:', err); }
    });
  }

  private startTasmotaStream(): void {
    this.tasmotaSubscription = this.tasmotaLiveService.getLiveStream().subscribe({
      next: (reading) => { this.tasmotaReading = reading; },
      error: (err) => { console.error('Tasmota SSE-Fehler:', err); }
    });
  }

  private startShellyStream(): void {
    this.shellySubscription = this.shellyLiveService.getLiveStream().subscribe({
      next: (devices) => { this.shellyDevices = devices; },
      error: (err) => { console.error('Shelly SSE-Fehler:', err); }
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
