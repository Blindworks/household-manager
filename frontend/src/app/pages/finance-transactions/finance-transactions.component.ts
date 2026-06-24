import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import {
  BankAccount, Category, RuleSuggestion, TransactionDto
} from '../../models/finance.model';

@Component({
  selector: 'app-finance-transactions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-transactions.component.html',
  styleUrl: './finance-transactions.component.scss'
})
export class FinanceTransactionsComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  accounts: BankAccount[] = [];
  categories: Category[] = [];
  transactions: TransactionDto[] = [];

  selectedAccountId: number | null = null;
  month = this.currentMonth();
  search = '';
  onlyUncategorized = false;

  loading = false;
  errorMessage: string | null = null;

  pendingSuggestion: RuleSuggestion | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe(c => this.categories = c);
    this.financeService.getAccounts().subscribe({
      next: (a) => { this.accounts = a; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  load(): void {
    this.loading = true;
    this.errorMessage = null;
    const [from, to] = this.monthRange(this.month);
    this.financeService.getTransactions(from, to, this.selectedAccountId ?? undefined).subscribe({
      next: (txs) => { this.transactions = txs; this.loading = false; },
      error: (e: Error) => { this.errorMessage = e.message; this.loading = false; }
    });
  }

  get visibleTransactions(): TransactionDto[] {
    const term = this.search.trim().toLowerCase();
    return this.transactions.filter(t => {
      if (this.onlyUncategorized && t.categoryId != null) {
        return false;
      }
      if (!term) {
        return true;
      }
      return (t.counterpartyName ?? '').toLowerCase().includes(term)
          || (t.purpose ?? '').toLowerCase().includes(term);
    });
  }

  categoryName(id?: number): string {
    if (id == null) {
      return 'Unkategorisiert';
    }
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }

  onCategoryChange(tx: TransactionDto, categoryId: number): void {
    this.financeService.categorize(tx.id, categoryId).subscribe({
      next: (response) => {
        tx.categoryId = response.transaction.categoryId;
        tx.manuallyCategorized = response.transaction.manuallyCategorized;
        this.pendingSuggestion = response.ruleSuggestion ?? null;
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  acceptSuggestion(): void {
    if (!this.pendingSuggestion) {
      return;
    }
    const s = this.pendingSuggestion;
    this.financeService.createRule({
      field: s.field, matchType: s.matchType, pattern: s.pattern,
      categoryId: s.categoryId, applyToExisting: true
    }).subscribe({
      next: () => { this.pendingSuggestion = null; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  dismissSuggestion(): void {
    this.pendingSuggestion = null;
  }

  suggestedCategoryName(): string {
    return this.categoryName(this.pendingSuggestion?.categoryId);
  }

  private currentMonth(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private monthRange(month: string): [string, string] {
    const [year, m] = month.split('-').map(Number);
    const from = `${year}-${String(m).padStart(2, '0')}-01`;
    const lastDay = new Date(year, m, 0).getDate();
    const to = `${year}-${String(m).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
    return [from, to];
  }
}
