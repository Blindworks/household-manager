import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { BankAccount, Category, RecurringPayment } from '../../models/finance.model';

@Component({
  selector: 'app-finance-recurring',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-recurring.component.html',
  styleUrl: './finance-recurring.component.scss'
})
export class FinanceRecurringComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  accounts: BankAccount[] = [];
  categories: Category[] = [];
  items: RecurringPayment[] = [];
  selectedAccountId: number | null = null;
  detecting = false;
  errorMessage: string | null = null;
  infoMessage: string | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe({
      next: (c) => this.categories = c,
      error: (e: Error) => this.errorMessage = e.message
    });
    this.financeService.getAccounts().subscribe({
      next: (a) => {
        this.accounts = a;
        this.selectedAccountId = a.length > 0 ? a[0].id : null;
      },
      error: (e: Error) => this.errorMessage = e.message
    });
    this.load();
  }

  load(): void {
    this.financeService.getRecurring().subscribe({
      next: (r) => this.items = r,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  detect(): void {
    if (this.selectedAccountId == null) {
      return;
    }
    this.detecting = true;
    this.infoMessage = null;
    this.financeService.detectRecurring(this.selectedAccountId).subscribe({
      next: (found) => {
        this.detecting = false;
        this.infoMessage = `${found.length} neue Kandidaten gefunden.`;
        this.load();
      },
      error: (e: Error) => { this.detecting = false; this.errorMessage = e.message; }
    });
  }

  confirm(item: RecurringPayment): void {
    this.financeService.confirmRecurring(item.id).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(item: RecurringPayment): void {
    this.financeService.deleteRecurring(item.id).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  categoryName(id?: number): string {
    if (id == null) {
      return '—';
    }
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }
}
