import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { Budget, BudgetStatusResponse, Category } from '../../models/finance.model';

@Component({
  selector: 'app-finance-budgets',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-budgets.component.html',
  styleUrl: './finance-budgets.component.scss'
})
export class FinanceBudgetsComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  budgets: Budget[] = [];
  categories: Category[] = [];
  status: BudgetStatusResponse | null = null;
  month = this.currentMonth();

  overallAmount: number | null = null;
  categoryId: number | null = null;
  categoryAmount: number | null = null;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe(c => this.categories = c.filter(x => x.kind === 'EXPENSE'));
    this.load();
  }

  load(): void {
    this.financeService.getBudgets().subscribe({
      next: (b) => {
        this.budgets = b;
        this.overallAmount = b.find(x => x.categoryId == null)?.amount ?? null;
      },
      error: (e: Error) => this.errorMessage = e.message
    });
    this.financeService.getBudgetStatus(this.month).subscribe({
      next: (s) => this.status = s,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  saveOverall(): void {
    if (this.overallAmount == null) {
      return;
    }
    this.financeService.saveBudget({ amount: this.overallAmount }).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  saveCategory(): void {
    if (this.categoryId == null || this.categoryAmount == null) {
      return;
    }
    this.financeService.saveBudget({ categoryId: this.categoryId, amount: this.categoryAmount }).subscribe({
      next: () => { this.categoryAmount = null; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(budget: Budget): void {
    this.financeService.deleteBudget(budget.id).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  categoryName(id?: number): string {
    if (id == null) {
      return 'Gesamt';
    }
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }

  statusColor(level?: string): string {
    switch (level) {
      case 'RED': return '#c62828';
      case 'YELLOW': return '#f9a825';
      default: return '#2e7d32';
    }
  }

  private currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }
}
