import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { FinanceRulesComponent } from './finance-rules.component';
import { FinanceService } from '../../services/finance.service';
import {
  Category, CategorizationRule, RuleChangeRequest, RuleReevaluationPreview
} from '../../models/finance.model';

describe('FinanceRulesComponent', () => {
  let fixture: ComponentFixture<FinanceRulesComponent>;
  let finance: jasmine.SpyObj<FinanceService>;

  const categories: Category[] = [
    { id: 7, name: 'Lebensmittel', kind: 'EXPENSE', system: false } as Category,
    { id: 9, name: 'Shopping', kind: 'EXPENSE', system: false } as Category
  ];
  const ruleA: CategorizationRule = {
    id: 1, conditions: [{ field: 'COUNTERPARTY_NAME', matchType: 'CONTAINS', pattern: 'rewe' }],
    categoryId: 7, priority: 100, enabled: true, appliedToExistingCount: 0
  };
  const ruleB: CategorizationRule = {
    id: 2, conditions: [
      { field: 'COUNTERPARTY_NAME', matchType: 'CONTAINS', pattern: 'paypal' },
      { field: 'PURPOSE', matchType: 'CONTAINS', pattern: 'zalando' }
    ],
    categoryId: 9, priority: 50, enabled: false, appliedToExistingCount: 0
  };
  const emptyPreview: RuleReevaluationPreview = { totalChanges: 0, rows: [] };

  beforeEach(() => {
    finance = jasmine.createSpyObj<FinanceService>('FinanceService', [
      'getCategories', 'getRules', 'previewRuleChange', 'createRule', 'updateRule', 'deleteRule', 'applyRules'
    ]);
    finance.getCategories.and.returnValue(of(categories));
    finance.getRules.and.returnValue(of([ruleA, ruleB]));
    finance.previewRuleChange.and.returnValue(of(emptyPreview));
    TestBed.configureTestingModule({
      imports: [FinanceRulesComponent],
      providers: [{ provide: FinanceService, useValue: finance }]
    });
    fixture = TestBed.createComponent(FinanceRulesComponent);
    fixture.detectChanges();
  });

  const el = (): HTMLElement => fixture.nativeElement;
  const rowOf = (index: number): HTMLElement => el().querySelectorAll('tbody tr')[index] as HTMLElement;
  function click(selector: string, root: HTMLElement = el()): void {
    const button = root.querySelector(selector) as HTMLButtonElement | null;
    expect(button).withContext(selector).not.toBeNull();
    button!.click();
    fixture.detectChanges();
  }
  function dialogButton(label: string): HTMLButtonElement | undefined {
    return Array.from(el().querySelectorAll<HTMLButtonElement>('app-finance-rule-preview button'))
      .find(b => b.textContent!.trim() === label);
  }
  function clickDialog(label: string): void {
    const button = dialogButton(label);
    expect(button).withContext(label).toBeDefined();
    button!.click();
    fixture.detectChanges();
  }
  const editorPatterns = (): string[] =>
    Array.from(el().querySelectorAll<HTMLInputElement>('app-finance-rule-editor .condition-row input'))
      .map(i => i.value);
  const lastPreviewRequest = (): RuleChangeRequest => finance.previewRuleChange.calls.mostRecent().args[0];

  it('deleteGoesThroughPreview', () => {
    finance.deleteRule.and.returnValue(of({ applied: 3 }));
    click('.delete-rule', rowOf(0));
    expect(lastPreviewRequest()).toEqual({ type: 'DELETE', ruleId: 1 });
    expect(finance.deleteRule).not.toHaveBeenCalled();

    clickDialog('Löschen und anwenden');
    expect(finance.deleteRule).toHaveBeenCalledOnceWith(1, true);
    expect(el().querySelector('app-finance-rule-preview')).toBeNull();
    expect(el().textContent).toContain('3 Buchungen neu zugeordnet');
  });

  it('deleteSaveOnlyDeletesWithoutApply', () => {
    finance.deleteRule.and.returnValue(of({ applied: 0 }));
    click('.delete-rule', rowOf(0));
    clickDialog('Nur speichern');
    expect(finance.deleteRule).toHaveBeenCalledOnceWith(1, false);
  });

  it('toggleSendsUpdateWithFlippedEnabled', () => {
    finance.updateRule.and.returnValue(of({ ...ruleB, enabled: true, appliedToExistingCount: 2 }));
    click('.toggle-rule', rowOf(1));
    const request = lastPreviewRequest();
    expect(request.type).toBe('UPDATE');
    expect(request.ruleId).toBe(2);
    expect(request.rule!.enabled).toBeTrue();
    expect(request.rule!.conditions).toEqual(ruleB.conditions);
    expect(request.rule!.categoryId).toBe(9);
    expect(request.rule!.priority).toBe(50);

    clickDialog('Speichern und anwenden');
    expect(finance.updateRule).toHaveBeenCalledOnceWith(2, jasmine.objectContaining({
      enabled: true, applyToExisting: true, conditions: ruleB.conditions
    }));
    expect(el().textContent).toContain('Regel gespeichert, 2 Buchungen neu zugeordnet.');
  });

  it('reapplyHasNoSaveOnly', () => {
    finance.applyRules.and.returnValue(of({ applied: 4 }));
    click('.reapply-rules');
    expect(lastPreviewRequest()).toEqual({ type: 'REAPPLY' });
    expect(dialogButton('Nur speichern')).toBeUndefined();
    clickDialog('Speichern und anwenden');
    expect(finance.applyRules).toHaveBeenCalledTimes(1);
    expect(el().textContent).toContain('4 Buchungen neu zugeordnet');
  });

  it('secondConfirmClickIsIgnoredWhileBusy', () => {
    const pending = new Subject<CategorizationRule>();
    finance.createRule.and.returnValue(pending);
    click('.new-rule');
    const input = el().querySelector('app-finance-rule-editor .condition-row input') as HTMLInputElement;
    input.value = 'aldi';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    click('app-finance-rule-editor .submit');
    expect(lastPreviewRequest().type).toBe('CREATE');
    expect(lastPreviewRequest().rule!.categoryId).toBe(7);

    fixture.componentInstance.confirm(true);
    fixture.componentInstance.confirm(true);
    expect(finance.createRule).toHaveBeenCalledTimes(1);
    expect(finance.createRule.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      applyToExisting: true, categoryId: 7
    }));
  });

  it('secondEditorSubmitIsIgnoredWhilePreviewLoads', () => {
    const pending = new Subject<RuleReevaluationPreview>();
    finance.previewRuleChange.and.returnValue(pending);
    click('.edit-rule', rowOf(0));
    click('app-finance-rule-editor .submit');
    click('app-finance-rule-editor .submit');
    expect(finance.previewRuleChange).toHaveBeenCalledTimes(1);
    expect(lastPreviewRequest()).toEqual(jasmine.objectContaining({ type: 'UPDATE', ruleId: 1 }));
  });

  it('editingAnotherRuleShowsItsConditions', () => {
    click('.edit-rule', rowOf(0));
    expect(editorPatterns()).toEqual(['rewe']);
    click('app-finance-rule-editor .cancel');
    expect(el().querySelector('app-finance-rule-editor')).toBeNull();
    click('.edit-rule', rowOf(1));
    expect(editorPatterns()).toEqual(['paypal', 'zalando']);
    // Direktwechsel ohne Abbrechen erzeugt ebenfalls einen frischen Editor.
    click('.edit-rule', rowOf(0));
    expect(editorPatterns()).toEqual(['rewe']);
  });

  it('failedPreviewKeepsEditorWithMessage', () => {
    finance.previewRuleChange.and.returnValue(throwError(() => new Error('Regex kaputt')));
    click('.edit-rule', rowOf(0));
    click('app-finance-rule-editor .submit');
    expect(el().textContent).toContain('Regex kaputt');
    expect(el().querySelector('app-finance-rule-editor')).not.toBeNull();
    expect(el().querySelector('app-finance-rule-preview')).toBeNull();

    finance.previewRuleChange.and.returnValue(of(emptyPreview));
    click('app-finance-rule-editor .submit');
    expect(finance.previewRuleChange).toHaveBeenCalledTimes(2);
  });

  it('failedSaveKeepsDialogAndResetsBusy', () => {
    finance.updateRule.and.returnValue(throwError(() => new Error('Serverfehler')));
    click('.toggle-rule', rowOf(0));
    clickDialog('Speichern und anwenden');
    expect(el().textContent).toContain('Serverfehler');
    expect(dialogButton('Speichern und anwenden')!.disabled).toBeFalse();
  });
});
