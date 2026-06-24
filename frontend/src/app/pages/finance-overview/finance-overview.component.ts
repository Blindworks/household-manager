import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { PieChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { FinanceService } from '../../services/finance.service';
import { StatementImportComponent } from '../../components/statement-import/statement-import.component';
import {
  BankAccount, BudgetStatusItem, OverviewResponse, TrendPoint
} from '../../models/finance.model';
import type { EChartsCoreOption } from 'echarts/core';

echarts.use([PieChart, LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

type Layout = 'A' | 'B';

@Component({
  selector: 'app-finance-overview',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective, StatementImportComponent],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './finance-overview.component.html',
  styleUrl: './finance-overview.component.scss'
})
export class FinanceOverviewComponent implements OnInit {
  private readonly financeService = inject(FinanceService);
  private readonly destroyRef = inject(DestroyRef);
  private static readonly LAYOUT_KEY = 'finance.overviewLayout';

  accounts: BankAccount[] = [];
  selectedAccountId: number | null = null;
  month = this.currentMonth();
  layout: Layout = 'A';

  overview: OverviewResponse | null = null;
  trend: TrendPoint[] = [];
  donutOption: EChartsCoreOption = {};
  trendOption: EChartsCoreOption = {};
  loading = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    const stored = localStorage.getItem(FinanceOverviewComponent.LAYOUT_KEY);
    this.layout = stored === 'B' ? 'B' : 'A';
    this.financeService.getAccounts().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (accounts) => {
        this.accounts = accounts;
        this.load();
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  setLayout(layout: Layout): void {
    this.layout = layout;
    localStorage.setItem(FinanceOverviewComponent.LAYOUT_KEY, layout);
  }

  onFilterChange(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.errorMessage = null;
    const accountId = this.selectedAccountId ?? undefined;

    this.financeService.getOverview(this.month, accountId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (overview) => {
        this.overview = overview;
        this.donutOption = this.buildDonut(overview.categories);
        this.loading = false;
      },
      error: (e: Error) => { this.errorMessage = e.message; this.loading = false; }
    });

    const from = this.monthsAgo(this.month, 5);
    this.financeService.getTrend(from, this.month, accountId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (trend) => {
        this.trend = trend;
        this.trendOption = this.buildTrend(trend);
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  get categoryBudgets(): BudgetStatusItem[] {
    return this.overview?.budget?.categories ?? [];
  }

  statusColor(level: string): string {
    switch (level) {
      case 'RED': return '#c62828';
      case 'YELLOW': return '#f9a825';
      default: return '#2e7d32';
    }
  }

  private buildDonut(items: { categoryName: string; amount: number; color?: string }[]): EChartsCoreOption {
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} € ({d}%)' },
      legend: { type: 'scroll', orient: 'vertical', right: 0, top: 'center' },
      series: [{
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['40%', '50%'],
        data: items.map(i => ({
          name: i.categoryName, value: i.amount,
          itemStyle: i.color ? { color: i.color } : undefined
        }))
      }]
    };
  }

  private buildTrend(points: TrendPoint[]): EChartsCoreOption {
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: ['Ausgaben', 'Einnahmen'] },
      grid: { left: 50, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: points.map(p => p.month) },
      yAxis: { type: 'value' },
      series: [
        { name: 'Ausgaben', type: 'line', data: points.map(p => p.expenses), itemStyle: { color: '#ef5350' } },
        { name: 'Einnahmen', type: 'line', data: points.map(p => p.income), itemStyle: { color: '#66bb6a' } }
      ]
    };
  }

  private currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private monthsAgo(month: string, count: number): string {
    const [year, m] = month.split('-').map(Number);
    const date = new Date(year, m - 1 - count, 1);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
  }
}
