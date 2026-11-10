import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import type { EChartsCoreOption } from 'echarts/core';
import { Subscription } from 'rxjs';
import { FinanceService } from '../../services/finance.service';
import { BankAccount, CategoryTrendResponse, CategoryTrendRow } from '../../models/finance.model';
import {
  averagePerMonth, categoryKey, groupTopCategories, monthLabel, startMonth
} from './category-trend.util';

echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

export const RANGE_OPTIONS = [6, 12, 24] as const;
export type RangeMonths = typeof RANGE_OPTIONS[number];

@Component({
  selector: 'app-finance-category-trend',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './finance-category-trend.component.html',
  styleUrl: './finance-category-trend.component.scss'
})
export class FinanceCategoryTrendComponent implements OnInit {
  private readonly financeService = inject(FinanceService);
  private readonly destroyRef = inject(DestroyRef);
  private pendingRequest: Subscription | null = null;

  readonly rangeOptions = RANGE_OPTIONS;
  readonly currentMonth = FinanceCategoryTrendComponent.monthOf(new Date());

  accounts: BankAccount[] = [];
  selectedAccountId: number | null = null;
  rangeMonths: RangeMonths = 12;
  /** Schlüssel der im Diagramm einzeln gezeigten Kategorie; null = alle gestapelt. */
  selectedKey: string | null = null;

  data: CategoryTrendResponse | null = null;
  chartOption: EChartsCoreOption = {};
  loading = false;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.financeService.getAccounts().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: accounts => this.accounts = accounts,
      error: (e: Error) => this.errorMessage = e.message
    });
    this.load();
  }

  setRange(months: RangeMonths): void {
    this.rangeMonths = months;
    this.load();
  }

  load(): void {
    this.pendingRequest?.unsubscribe();
    this.loading = true;
    this.errorMessage = null;
    const from = startMonth(this.currentMonth, this.rangeMonths);
    this.pendingRequest = this.financeService
      .getCategoryTrend(from, this.currentMonth, this.selectedAccountId ?? undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: data => {
          this.data = data;
          if (this.selectedKey && !data.categories.some(r => categoryKey(r) === this.selectedKey)) {
            this.selectedKey = null;
          }
          this.rebuildChart();
          this.loading = false;
        },
        error: (e: Error) => { this.errorMessage = e.message; this.loading = false; }
      });
  }

  toggleCategory(row: CategoryTrendRow): void {
    const key = categoryKey(row);
    this.selectedKey = this.selectedKey === key ? null : key;
    this.rebuildChart();
  }

  showAll(): void {
    this.selectedKey = null;
    this.rebuildChart();
  }

  get selectedRow(): CategoryTrendRow | null {
    return this.data?.categories.find(r => categoryKey(r) === this.selectedKey) ?? null;
  }

  isSelected(row: CategoryTrendRow): boolean {
    return categoryKey(row) === this.selectedKey;
  }

  rowKey(row: CategoryTrendRow): string {
    return categoryKey(row);
  }

  average(row: CategoryTrendRow): number {
    return averagePerMonth(row, this.data?.months ?? [], this.currentMonth);
  }

  monthTotal(index: number): number {
    return (this.data?.categories ?? []).reduce((sum, r) => sum + r.amounts[index], 0);
  }

  get grandTotal(): number {
    return (this.data?.categories ?? []).reduce((sum, r) => sum + r.total, 0);
  }

  get averageTotal(): number {
    return (this.data?.categories ?? []).reduce((sum, r) => sum + this.average(r), 0);
  }

  label(month: string): string {
    return monthLabel(month);
  }

  private rebuildChart(): void {
    if (!this.data) {
      this.chartOption = {};
      return;
    }
    const selected = this.selectedRow;
    const rows = selected ? [selected] : groupTopCategories(this.data.categories);
    this.chartOption = {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        valueFormatter: (v: number) =>
          `${Number(v).toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} €`
      },
      legend: { type: 'scroll', bottom: 0 },
      grid: { left: 60, right: 20, top: 20, bottom: 50 },
      xAxis: { type: 'category', data: this.data.months.map(monthLabel) },
      yAxis: { type: 'value', axisLabel: { formatter: '{value} €' } },
      series: rows.map(r => ({
        name: r.categoryName,
        type: 'bar',
        stack: 'expenses',
        data: r.amounts,
        itemStyle: r.color ? { color: r.color } : undefined
      }))
    };
  }

  private static monthOf(date: Date): string {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
  }
}
