import { Component, OnInit, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { describeConditions, newCondition } from '../../shared/finance-rule.util';
import { FinanceService } from '../../services/finance.service';
import { FinanceRuleEditorComponent } from '../../components/finance-rule-editor/finance-rule-editor.component';
import { FinanceRulePreviewComponent } from '../../components/finance-rule-preview/finance-rule-preview.component';
import {
  Category, CategorizationRule, CategorizationRuleRequest, RuleChangeRequest, RuleCondition,
  RuleReevaluationPreview
} from '../../models/finance.model';

interface EditorState {
  /** Neuer Schlüssel je Öffnen: der Editor übernimmt seine Eingaben nur beim ersten Setzen. */
  key: number;
  heading: string;
  ruleId: number | null;
  conditions: RuleCondition[];
  categoryId: number;
  priority: number | null;
  /** Nur für neue Regeln; eine bestehende nimmt beim Absenden ihren aktuellen Stand (Toggle bei offenem Editor). */
  enabled: boolean;
}

type PendingAction =
  | { kind: 'save'; ruleId: number | null; rule: CategorizationRuleRequest; fromEditor: boolean }
  | { kind: 'delete'; ruleId: number }
  | { kind: 'reapply' };

interface DialogState {
  title: string;
  action: PendingAction;
  preview: RuleReevaluationPreview;
  allowSaveOnly: boolean;
  applyLabel: string;
  saveOnlyLabel: string;
}

const APPLY_LABEL = 'Speichern und anwenden';
const SAVE_ONLY_LABEL = 'Nur speichern';
const DEFAULT_PRIORITY = 100;

@Component({
  selector: 'app-finance-rules',
  standalone: true,
  imports: [FinanceRuleEditorComponent, FinanceRulePreviewComponent],
  templateUrl: './finance-rules.component.html',
  styleUrl: './finance-rules.component.scss'
})
export class FinanceRulesComponent implements OnInit {
  private readonly financeService = inject(FinanceService);

  rules: CategorizationRule[] = [];
  categories: Category[] = [];
  editor: EditorState | null = null;
  dialog: DialogState | null = null;
  /** Vorschau- oder Speicher-Request läuft; weitere Klicks lösen nichts aus. */
  busy = false;
  errorMessage: string | null = null;
  infoMessage: string | null = null;

  private nextEditorKey = 1;

  ngOnInit(): void {
    this.financeService.getCategories().subscribe({
      next: (c) => this.categories = c,
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

  /** Höchstens ein Editor; als Liste, damit `@for … track key` ihn je Öffnen neu erzeugt. */
  get editors(): EditorState[] {
    return this.editor ? [this.editor] : [];
  }

  openNew(): void {
    this.openEditor('Neue Regel', null, [newCondition()], this.categories[0]?.id ?? 0, DEFAULT_PRIORITY, true);
  }

  openEdit(rule: CategorizationRule): void {
    this.openEditor('Regel bearbeiten', rule.id, rule.conditions, rule.categoryId, rule.priority, rule.enabled);
  }

  closeEditor(): void {
    this.editor = null;
  }

  onEditorSubmitted(request: CategorizationRuleRequest): void {
    const editor = this.editor;
    if (!editor || this.busy) {
      return;
    }
    const current = this.rules.find(r => r.id === editor.ruleId);
    const rule: CategorizationRuleRequest = { ...request, enabled: current?.enabled ?? editor.enabled };
    const isNew = editor.ruleId === null;
    this.requestPreview(
      isNew ? { type: 'CREATE', rule } : { type: 'UPDATE', ruleId: editor.ruleId!, rule },
      { kind: 'save', ruleId: editor.ruleId, rule, fromEditor: true },
      isNew ? 'Neue Regel – Vorschau' : 'Regel ändern – Vorschau'
    );
  }

  toggle(rule: CategorizationRule): void {
    const request: CategorizationRuleRequest = {
      conditions: rule.conditions, categoryId: rule.categoryId, priority: rule.priority, enabled: !rule.enabled
    };
    this.requestPreview(
      { type: 'UPDATE', ruleId: rule.id, rule: request },
      { kind: 'save', ruleId: rule.id, rule: request, fromEditor: false },
      rule.enabled ? 'Regel ausschalten – Vorschau' : 'Regel einschalten – Vorschau'
    );
  }

  remove(rule: CategorizationRule): void {
    this.requestPreview({ type: 'DELETE', ruleId: rule.id }, { kind: 'delete', ruleId: rule.id },
      'Regel löschen – Vorschau', true, 'Löschen und anwenden', 'Nur löschen');
  }

  reapplyAll(): void {
    this.requestPreview({ type: 'REAPPLY' }, { kind: 'reapply' }, 'Alle Regeln neu anwenden – Vorschau', false, 'Neu anwenden');
  }

  /** Führt die Aktion des offenen Dialogs aus; `apply` = bestehende Buchungen neu bewerten. */
  confirm(apply: boolean): void {
    const dialog = this.dialog;
    if (!dialog || this.busy) {
      return;
    }
    this.busy = true;
    this.errorMessage = null;
    this.execute(dialog.action, apply).subscribe({
      next: (message) => {
        this.busy = false;
        this.dialog = null;
        if (dialog.action.kind !== 'save' || dialog.action.fromEditor) {
          this.editor = null;
        }
        this.infoMessage = message;
        this.load();
      },
      error: (e: Error) => { this.errorMessage = e.message; this.busy = false; }
    });
  }

  cancelDialog(): void {
    if (!this.busy) {
      this.dialog = null;
    }
  }

  describe(rule: CategorizationRule): string {
    return describeConditions(rule.conditions);
  }

  categoryName(id: number): string {
    return this.categories.find(c => c.id === id)?.name ?? '?';
  }

  private openEditor(heading: string, ruleId: number | null, conditions: RuleCondition[],
                     categoryId: number, priority: number | null, enabled: boolean): void {
    this.errorMessage = null;
    this.editor = { key: this.nextEditorKey++, heading, ruleId, conditions, categoryId, priority, enabled };
  }

  private requestPreview(change: RuleChangeRequest, action: PendingAction, title: string,
                         allowSaveOnly = true, applyLabel = APPLY_LABEL, saveOnlyLabel = SAVE_ONLY_LABEL): void {
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.errorMessage = null;
    this.infoMessage = null;
    this.financeService.previewRuleChange(change).subscribe({
      next: (preview) => {
        this.busy = false;
        this.dialog = { title, action, preview, allowSaveOnly, applyLabel, saveOnlyLabel };
      },
      error: (e: Error) => { this.errorMessage = e.message; this.busy = false; }
    });
  }

  private execute(action: PendingAction, apply: boolean): Observable<string> {
    switch (action.kind) {
      case 'save': {
        const request: CategorizationRuleRequest = { ...action.rule, applyToExisting: apply };
        const saved = action.ruleId === null
          ? this.financeService.createRule(request)
          : this.financeService.updateRule(action.ruleId, request);
        return saved.pipe(map(r => `Regel gespeichert, ${r.appliedToExistingCount} Buchungen neu zugeordnet.`));
      }
      case 'delete':
        return this.financeService.deleteRule(action.ruleId, apply)
          .pipe(map(r => `Regel gelöscht, ${r.applied} Buchungen neu zugeordnet.`));
      case 'reapply':
        return this.financeService.applyRules().pipe(map(r => `${r.applied} Buchungen neu zugeordnet.`));
    }
  }
}
