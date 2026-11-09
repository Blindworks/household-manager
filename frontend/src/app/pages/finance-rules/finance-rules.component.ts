import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { canSaveConditions, describeConditions } from '../../shared/finance-rule.util';
import { FinanceService } from '../../services/finance.service';
import {
  Category, CategorizationRule, CategorizationRuleRequest, RuleMatchField, RuleMatchType
} from '../../models/finance.model';

@Component({
  selector: 'app-finance-rules',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './finance-rules.component.html',
  styleUrl: './finance-rules.component.scss'
})
export class FinanceRulesComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  rules: CategorizationRule[] = [];
  categories: Category[] = [];
  readonly fields: RuleMatchField[] = ['COUNTERPARTY_NAME', 'COUNTERPARTY_IBAN', 'PURPOSE'];
  readonly matchTypes: RuleMatchType[] = ['CONTAINS', 'EQUALS', 'REGEX'];

  form: CategorizationRuleRequest = {
    conditions: [{ field: 'COUNTERPARTY_NAME', matchType: 'CONTAINS', pattern: '' }],
    categoryId: 0, priority: 100, enabled: true, applyToExisting: true
  };
  errorMessage: string | null = null;
  infoMessage: string | null = null;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe({
      next: (c) => {
        this.categories = c;
        if (c.length > 0) {
          this.form.categoryId = c[0].id;
        }
      },
      error: (e: Error) => this.errorMessage = e.message
    });
    this.load();
  }

  load(): void {
    this.financeService.getRules().subscribe({
      next: (r) => this.rules = r,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  create(): void {
    if (!canSaveConditions(this.form.conditions) || !this.form.categoryId) {
      return;
    }
    this.financeService.createRule(this.form).subscribe({
      next: (rule) => {
        this.infoMessage = `Regel angelegt, ${rule.appliedToExistingCount} bestehende Buchungen zugeordnet.`;
        this.form.conditions[0].pattern = '';
        this.load();
      },
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  toggle(rule: CategorizationRule): void {
    this.financeService.updateRule(rule.id, {
      conditions: rule.conditions,
      categoryId: rule.categoryId, priority: rule.priority, enabled: !rule.enabled
    }).subscribe({ next: () => this.load(), error: (e: Error) => this.errorMessage = e.message });
  }

  remove(rule: CategorizationRule): void {
    this.financeService.deleteRule(rule.id, false).subscribe({
      next: () => this.load(), error: (e: Error) => this.errorMessage = e.message
    });
  }

  applyAll(): void {
    this.financeService.applyRules().subscribe({
      next: (r) => this.infoMessage = `${r.applied} Buchungen neu zugeordnet.`,
      error: (e: Error) => this.errorMessage = e.message
    });
  }

  describe(rule: CategorizationRule): string {
    return describeConditions(rule.conditions);
  }

  categoryName(id: number): string {
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }
}
