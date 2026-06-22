import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { Subscription } from 'rxjs';
import { ZigbeeService } from '../../services/zigbee.service';
import { ZigbeeLiveService } from '../../services/zigbee-live.service';
import {
  ZigbeeDevice,
  ZigbeeLiveEvent,
  ZigbeeMeasurementType
} from '../../models/zigbee.model';

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);

interface LiveValue {
  value: number;
  unit: string;
  measuredAt: string;
}

/**
 * Übersicht der Zigbee-Sensoren: Live-Kacheln je Gerät + Verlaufschart.
 */
@Component({
  selector: 'app-zigbee',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './zigbee.component.html',
  styleUrl: './zigbee.component.scss'
})
export class ZigbeeComponent implements OnInit, OnDestroy {
  private readonly zigbeeService = inject(ZigbeeService);
  private readonly liveService = inject(ZigbeeLiveService);

  devices: ZigbeeDevice[] = [];
  /** friendlyName -> (measurementType -> aktueller Wert) */
  liveValues: Record<string, Record<string, LiveValue>> = {};

  selectedDevice?: string;
  selectedType: ZigbeeMeasurementType = 'TEMPERATURE';
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  chartOptions: any = null;

  private liveSub?: Subscription;

  ngOnInit(): void {
    this.loadDevices();
    this.liveSub = this.liveService.getLiveStream().subscribe({
      next: (event) => this.applyLiveEvent(event),
      error: () => { /* SSE reconnects via browser */ }
    });
  }

  ngOnDestroy(): void {
    this.liveSub?.unsubscribe();
    this.liveService.disconnect();
  }

  loadDevices(): void {
    this.zigbeeService.getDevices().subscribe((devices) => {
      this.devices = devices;
      if (!this.selectedDevice && devices.length > 0) {
        this.selectedDevice = devices[0].friendlyName;
        this.loadHistory();
      }
    });
  }

  loadHistory(): void {
    if (!this.selectedDevice) { return; }
    this.zigbeeService.getMeasurements(this.selectedDevice, this.selectedType).subscribe((measurements) => {
      this.chartOptions = {
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'time' },
        yAxis: { type: 'value' },
        series: [{
          type: 'line',
          showSymbol: false,
          data: measurements.map(m => [m.measuredAt, m.value])
        }]
      };
    });
  }

  private applyLiveEvent(event: ZigbeeLiveEvent): void {
    const byType = this.liveValues[event.friendlyName] ?? {};
    byType[event.measurementType] = {
      value: event.value,
      unit: event.unit,
      measuredAt: event.measuredAt
    };
    this.liveValues[event.friendlyName] = byType;

    if (!this.devices.some(d => d.friendlyName === event.friendlyName)) {
      this.loadDevices();
    }
  }

  liveTypesFor(friendlyName: string): string[] {
    return Object.keys(this.liveValues[friendlyName] ?? {});
  }
}
