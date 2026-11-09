import { Component, EventEmitter, Output, input } from '@angular/core';
import { Category, RuleReevaluationPreview } from '../../models/finance.model';

const MAX_PREVIEW_ROWS = 200;
const EURO = new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' });

/** Zeigt, welche bestehenden Buchungen durch eine Regeländerung ihre Kategorie wechseln. */
@Component({
  selector: 'app-finance-rule-preview',
  standalone: true,
  templateUrl: './finance-rule-preview.component.html',
  styleUrl: './finance-rule-preview.component.scss'
})
export class FinanceRulePreviewComponent {
  readonly preview = input.required<RuleReevaluationPreview>();
  readonly categories = input<Category[]>([]);
  readonly busy = input<boolean>(false);
  readonly allowSaveOnly = input<boolean>(true);
  readonly applyLabel = input<string>('Speichern und anwenden');
  readonly saveOnlyLabel = input<string>('Nur speichern');

  @Output() applyAndSave = new EventEmitter<void>();
  @Output() saveOnly = new EventEmitter<void>();
  @Output() cancelled = new EventEmitter<void>();

  readonly maxRows = MAX_PREVIEW_ROWS;

  categoryName(id: number | null | undefined): string {
    if (id === null || id === undefined) {
      return 'Unkategorisiert';
    }
    return this.categories().find(c => c.id === id)?.name ?? '?';
  }

  formatDate(iso: string): string {
    const [year, month, day] = iso.split('-');
    return `${day}.${month}.${year}`;
  }

  formatAmount(amount: number): string {
    return EURO.format(amount);
  }
}
