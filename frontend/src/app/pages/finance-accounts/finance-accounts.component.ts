import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { BankAccount, BankAccountRequest } from '../../models/finance.model';

@Component({
  selector: 'app-finance-accounts',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-accounts.component.html',
  styleUrl: './finance-accounts.component.scss'
})
export class FinanceAccountsComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  accounts: BankAccount[] = [];
  form: BankAccountRequest = { name: '', iban: '', currency: 'EUR' };
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.financeService.getAccounts().subscribe({
      next: (a) => this.accounts = a,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  create(): void {
    if (!this.form.name || !this.form.currency) {
      return;
    }
    this.financeService.createAccount(this.form).subscribe({
      next: () => { this.form = { name: '', iban: '', currency: 'EUR' }; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  remove(account: BankAccount): void {
    this.financeService.deleteAccount(account.id).subscribe({
      next: () => this.load(),
      error: (e: Error) => this.errorMessage = e.message
    });
  }
}
