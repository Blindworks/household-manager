import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import {
  GridComponent,
  TooltipComponent,
  LegendComponent
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { AnkerSolixService } from '../../services/ankersolix.service';
import { SolixAutoControlReading } from '../../models/ankersolix.model';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

type TimeRange = '24h' | '7d' | '30d';

@Component({
  selector: 'app-energy-history',
  standalone: true,
  imports: [CommonModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './energy-history.component.html',
  styleUrl: './energy-history.component.scss'
})
export class EnergyHistoryComponent implements OnInit {
  private readonly solixService = inject(AnkerSolixService);

  selectedRange: TimeRange = '24h';
  chartOptions: Record<string, unknown> | null = null;
  isLoading = true;
  errorMessage: string | null = null;
  readingCount = 0;

  readonly timeRanges: { value: TimeRange; label: string }[] = [
    { value: '24h', label: 'Letzte 24h' },
    { value: '7d', label: 'Letzte 7 Tage' },
    { value: '30d', label: 'Letzte 30 Tage' }
  ];

  ngOnInit(): void {
    this.loadReadings();
  }

  selectRange(range: TimeRange): void {
    this.selectedRange = range;
    this.loadReadings();
  }

  private loadReadings(): void {
    this.isLoading = true;
    this.errorMessage = null;

    const now = new Date();
    const from = new Date(now);

    switch (this.selectedRange) {
      case '24h':
        from.setHours(from.getHours() - 24);
        break;
      case '7d':
        from.setDate(from.getDate() - 7);
        break;
      case '30d':
        from.setDate(from.getDate() - 30);
        break;
    }

    this.solixService.getAutoControlReadings(
      from.toISOString(),
      now.toISOString()
    ).subscribe({
      next: readings => {
        this.readingCount = readings.length;
        this.chartOptions = readings.length > 0
          ? this.buildChartOptions(readings)
          : null;
        this.isLoading = false;
      },
      error: () => {
        this.errorMessage = 'Fehler beim Laden der Verlaufsdaten. Bitte erneut versuchen.';
        this.isLoading = false;
      }
    });
  }

  private buildChartOptions(readings: SolixAutoControlReading[]): Record<string, unknown> {
    const timestamps = readings.map(r => this.formatTimestamp(r.timestamp));

    return {
      grid: {
        left: 64,
        right: 24,
        top: 48,
        bottom: 36,
        containLabel: false
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        formatter: (params: { seriesName: string; value: number; color: string }[]) => {
          if (!Array.isArray(params) || params.length === 0) { return ''; }
          const idx = (params[0] as unknown as { dataIndex: number }).dataIndex;
          const reading = readings[idx];
          const time = this.formatTimestamp(reading.timestamp);
          let html = `<strong>${time}</strong><br/>`;
          for (const p of params) {
            html += `<span style="display:inline-block;width:10px;height:10px;border-radius:50%;background:${p.color};margin-right:6px;"></span>`;
            html += `${p.seriesName}: <strong>${this.formatNumber(p.value)} W</strong><br/>`;
          }
          return html;
        }
      },
      legend: {
        top: 0,
        textStyle: { color: '#64748b', fontSize: 12 }
      },
      xAxis: {
        type: 'category',
        data: timestamps,
        axisLine: { lineStyle: { color: 'rgba(51, 65, 85, 0.7)' } },
        axisTick: { alignWithLabel: true, length: 6, lineStyle: { color: 'rgba(51, 65, 85, 0.75)' } },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 11,
          fontWeight: 400
        }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
        axisLabel: {
          color: '#94a3b8',
          fontSize: 11,
          fontWeight: 400,
          formatter: (value: number) => `${this.formatNumber(value)} W`
        }
      },
      series: [
        {
          name: 'Netzleistung',
          type: 'line',
          data: readings.map(r => r.gridPowerW),
          smooth: true,
          symbol: 'none',
          lineStyle: { width: 2.5, color: '#ef4444' },
          itemStyle: { color: '#ef4444' },
          areaStyle: { color: '#ef4444', opacity: 0.08 }
        },
        {
          name: 'Akku-Ausgang',
          type: 'line',
          data: readings.map(r => r.currentOutputW),
          smooth: true,
          symbol: 'none',
          lineStyle: { width: 2.5, color: '#22c55e' },
          itemStyle: { color: '#22c55e' },
          areaStyle: { color: '#22c55e', opacity: 0.08 }
        },
        {
          name: 'Ziel-Ausgang',
          type: 'line',
          data: readings.map(r => r.targetOutputW),
          smooth: true,
          symbol: 'none',
          lineStyle: { width: 2, color: '#3b82f6', type: 'dashed' },
          itemStyle: { color: '#3b82f6' }
        },
        {
          name: 'Begrenzter Ausgang',
          type: 'line',
          data: readings.map(r => r.clampedOutputW),
          smooth: true,
          symbol: 'none',
          lineStyle: { width: 2, color: '#a855f7', type: 'dashed' },
          itemStyle: { color: '#a855f7' }
        }
      ]
    };
  }

  private formatTimestamp(iso: string): string {
    const date = new Date(iso);
    return date.toLocaleString('de-DE', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  formatNumber(value: number): string {
    return value.toLocaleString('de-DE', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 0
    });
  }
}
