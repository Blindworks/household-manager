import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { AnkerSolixDeviceParams, AnkerSolixEnergyDay, AnkerSolixLive } from '../../models/ankersolix.model';
import { TasmotaLiveService } from '../../services/tasmota-live.service';
import { TasmotaLiveReading } from '../../models/tasmota-live.model';

echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

/**
 * Energy page component for Anker Solix solar station monitoring.
 * Displays live power data, battery state, device output control and daily energy history.
 */
@Component({
  selector: 'app-energy',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './energy.component.html',
  styleUrl: './energy.component.scss'
})
export class EnergyComponent implements OnInit, OnDestroy {
  private readonly ankerSolixService = inject(AnkerSolixService);
  private readonly tasmotaLiveService = inject(TasmotaLiveService);

  liveData: AnkerSolixLive | null = null;
  tasmotaReading: TasmotaLiveReading | null = null;
  deviceParams: AnkerSolixDeviceParams | null = null;
  energyData: AnkerSolixEnergyDay | null = null;
  chartOptions: Record<string, unknown> | null = null;

  outputWatts = 0;
  selectedDate = new Date().toISOString().substring(0, 10);

  connectionStatus$ = this.ankerSolixService.connectionStatus$;
  isSettingPower = false;
  powerSetSuccess = false;
  powerSetError = '';

  private liveSubscription: Subscription | null = null;
  private tasmotaSubscription: Subscription | null = null;

  ngOnInit(): void {
    this.startLiveStream();
    this.startTasmotaStream();
    this.loadDeviceParams();
    this.loadEnergy();
  }

  ngOnDestroy(): void {
    this.liveSubscription?.unsubscribe();
    this.tasmotaSubscription?.unsubscribe();
    this.ankerSolixService.disconnectLive();
    this.tasmotaLiveService.disconnect();
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

  get hausverbrauchW(): number | null {
    if (this.liveData === null || this.tasmotaReading === null) {
      return null;
    }
    const batteryDischarge = Math.max(0, -(this.liveData.batteryPowerW));
    const gridConsumption = Math.max(0, this.tasmotaReading.momentaneWirkleistung);
    return batteryDischarge + gridConsumption;
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

  private loadDeviceParams(): void {
    this.ankerSolixService.getDeviceParams().subscribe({
      next: (params) => {
        this.deviceParams = params;
        this.outputWatts = params.currentOutputW;
      },
      error: (err) => console.error('Fehler beim Laden der Geräteparameter:', err)
    });
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
