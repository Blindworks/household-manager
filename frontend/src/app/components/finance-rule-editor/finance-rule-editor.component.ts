import { Component, EventEmitter, Output, computed, input, signal } from '@angular/core';
import { Category, CategorizationRuleRequest, RuleCondition, RuleMatchField, RuleMatchType } from '../../models/finance.model';
import {
  FIELD_LABELS, MATCH_TYPE_LABELS, MAX_RULE_CONDITIONS, canSaveConditions, newCondition
} from '../../shared/finance-rule.util';

/** Bearbeitet 1 bis 5 UND-verknüpfte Bedingungen einer Kategorisierungsregel. */
@Component({
  selector: 'app-finance-rule-editor',
  standalone: true,
  templateUrl: './finance-rule-editor.component.html',
  styleUrl: './finance-rule-editor.component.scss'
})
export class FinanceRuleEditorComponent {
  readonly conditions = input<RuleCondition[]>([]);
  readonly categoryId = input<number>(0);
  readonly priority = input<number | null>(null);
  readonly categories = input<Category[]>([]);
  readonly hint = input<string | null>(null);
  readonly submitLabel = input<string>('Weiter zur Vorschau');

  @Output() submitted = new EventEmitter<CategorizationRuleRequest>();
  @Output() cancelled = new EventEmitter<void>();

  readonly fields = Object.keys(FIELD_LABELS) as RuleMatchField[];
  readonly matchTypes = Object.keys(MATCH_TYPE_LABELS) as RuleMatchType[];
  readonly fieldLabels = FIELD_LABELS;
  readonly matchTypeLabels = MATCH_TYPE_LABELS;

  // Arbeitskopien; null = noch nicht angefasst, dann gilt die (kopierte) Eingabe.
  private readonly editedConditions = signal<RuleCondition[] | null>(null);
  private readonly editedCategoryId = signal<number | null>(null);

  readonly rows = computed(() => this.editedConditions() ?? this.conditions().map(c => ({ ...c })));
  readonly selectedCategoryId = computed(() => this.editedCategoryId() ?? this.categoryId());
  readonly canAdd = computed(() => this.rows().length < MAX_RULE_CONDITIONS);
  readonly canSubmit = computed(() => canSaveConditions(this.rows()) && this.selectedCategoryId() > 0);

  addCondition(): void {
    if (this.canAdd()) {
      this.editedConditions.set([...this.rows(), newCondition()]);
    }
  }

  removeCondition(index: number): void {
    if (this.rows().length > 1) {
      this.editedConditions.set(this.rows().filter((_, i) => i !== index));
    }
  }

  setField(index: number, event: Event): void {
    this.update(index, { field: (event.target as HTMLSelectElement).value as RuleMatchField });
  }

  setMatchType(index: number, event: Event): void {
    this.update(index, { matchType: (event.target as HTMLSelectElement).value as RuleMatchType });
  }

  setPattern(index: number, event: Event): void {
    this.update(index, { pattern: (event.target as HTMLInputElement).value });
  }

  setCategory(event: Event): void {
    this.editedCategoryId.set(Number((event.target as HTMLSelectElement).value));
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    const request: CategorizationRuleRequest = {
      conditions: this.rows().map(c => ({ ...c, pattern: c.pattern.trim() })),
      categoryId: this.selectedCategoryId()
    };
    const priority = this.priority();
    if (priority !== null) {
      request.priority = priority;
    }
    this.submitted.emit(request);
  }

  private update(index: number, change: Partial<RuleCondition>): void {
    this.editedConditions.set(this.rows().map((c, i) => (i === index ? { ...c, ...change } : c)));
  }
}
