import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FinanceService } from '../../services/finance.service';
import { FinanceRuleEditorComponent } from '../../components/finance-rule-editor/finance-rule-editor.component';
import { FinanceRulePreviewComponent } from '../../components/finance-rule-preview/finance-rule-preview.component';
import {
  BankAccount, Category, CategorizationRuleRequest, RuleReevaluationPreview, RuleSuggestion, TransactionDto
} from '../../models/finance.model';

interface SuggestionEditor {
  /** Neuer Schlüssel je Vorschlag: der Editor übernimmt seine Eingaben nur beim ersten Setzen. */
  key: number;
  suggestion: RuleSuggestion;
}

interface SuggestionDialog {
  rule: CategorizationRuleRequest;
  preview: RuleReevaluationPreview;
}

@Component({
  selector: 'app-finance-transactions',
  standalone: true,
  imports: [CommonModule, FormsModule, FinanceRuleEditorComponent, FinanceRulePreviewComponent],
  templateUrl: './finance-transactions.component.html',
  styleUrl: './finance-transactions.component.scss'
})
export class FinanceTransactionsComponent implements OnInit {
  private readonly financeService = inject(FinanceService);
  private readonly destroyRef = inject(DestroyRef);

  accounts: BankAccount[] = [];
  categories: Category[] = [];
  transactions: TransactionDto[] = [];

  selectedAccountId: number | null = null;
  month = this.currentMonth();
  search = '';
  onlyUncategorized = false;

  loading = false;
  errorMessage: string | null = null;
  infoMessage: string | null = null;

  suggestionEditor: SuggestionEditor | null = null;
  suggestionDialog: SuggestionDialog | null = null;
  /** Vorschau- oder Speicher-Request läuft; weitere Klicks lösen nichts aus. */
  ruleBusy = false;
  readonly defaultPriority = 100;

  private nextEditorKey = 1;

  ngOnInit(): void {
    this.financeService.getCategories().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (c) => this.categories = c,
      error: (e: Error) => this.errorMessage = e.message
    });
    this.financeService.getAccounts().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (a) => { this.accounts = a; this.load(); },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  load(): void {
    this.loading = true;
    this.errorMessage = null;
    const [from, to] = this.monthRange(this.month);
    this.financeService.getTransactions(from, to, this.selectedAccountId ?? undefined)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
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
    this.financeService.categorize(tx.id, categoryId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (response) => {
        tx.categoryId = response.transaction.categoryId;
        tx.manuallyCategorized = response.transaction.manuallyCategorized;
        this.showSuggestion(response.ruleSuggestion ?? null);
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  /** Höchstens ein Editor; als Liste, damit `@for … track key` ihn je Vorschlag neu erzeugt. */
  get suggestionEditors(): SuggestionEditor[] {
    return this.suggestionEditor ? [this.suggestionEditor] : [];
  }

  onSuggestionSubmitted(rule: CategorizationRuleRequest): void {
    if (!this.suggestionEditor || this.ruleBusy) {
      return;
    }
    this.ruleBusy = true;
    this.errorMessage = null;
    this.financeService.previewRuleChange({ type: 'CREATE', rule })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (preview) => { this.ruleBusy = false; this.suggestionDialog = { rule, preview }; },
        error: (e: Error) => { this.errorMessage = e.message; this.ruleBusy = false; }
      });
  }

  /** Legt die Regel aus dem Vorschlag an; `apply` = bestehende Buchungen neu bewerten. */
  confirmSuggestion(apply: boolean): void {
    const dialog = this.suggestionDialog;
    if (!dialog || this.ruleBusy) {
      return;
    }
    this.ruleBusy = true;
    this.errorMessage = null;
    this.financeService.createRule({ ...dialog.rule, applyToExisting: apply })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: (created) => {
          this.ruleBusy = false;
          this.showSuggestion(null);
          this.infoMessage = `Regel gespeichert, ${created.appliedToExistingCount} Buchungen neu zugeordnet.`;
          this.load();
        },
        error: (e: Error) => { this.errorMessage = e.message; this.ruleBusy = false; }
      });
  }

  cancelSuggestionDialog(): void {
    if (!this.ruleBusy) {
      this.suggestionDialog = null;
    }
  }

  dismissSuggestion(): void {
    this.showSuggestion(null);
  }

  private showSuggestion(suggestion: RuleSuggestion | null): void {
    this.suggestionDialog = null;
    this.suggestionEditor = suggestion ? { key: this.nextEditorKey++, suggestion } : null;
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
