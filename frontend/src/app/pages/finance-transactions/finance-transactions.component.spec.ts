import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { FinanceTransactionsComponent } from './finance-transactions.component';
import { FinanceService } from '../../services/finance.service';
import {
  Category, CategorizationRule, CategorizeResponse, RuleSuggestion, TransactionDto
} from '../../models/finance.model';

describe('FinanceTransactionsComponent', () => {
  let fixture: ComponentFixture<FinanceTransactionsComponent>;
  let finance: jasmine.SpyObj<FinanceService>;

  const categories: Category[] = [
    { id: 7, name: 'Lebensmittel', kind: 'EXPENSE', system: false } as Category,
    { id: 9, name: 'Shopping', kind: 'EXPENSE', system: false } as Category
  ];
  const tx: TransactionDto = {
    id: 5, bookingDate: '2026-11-02', amount: -30, counterpartyName: 'PayPal', purpose: 'Zalando',
    manuallyCategorized: false
  } as TransactionDto;
  const suggestion = (pattern: string): RuleSuggestion => ({
    conditions: [
      { field: 'COUNTERPARTY_NAME', matchType: 'CONTAINS', pattern: 'paypal' },
      { field: 'PURPOSE', matchType: 'CONTAINS', pattern }
    ],
    categoryId: 9,
    hint: 'PayPal erkannt'
  });
  function categorizeAnswer(s: RuleSuggestion): CategorizeResponse {
    return { transaction: { ...tx, categoryId: 9, manuallyCategorized: true }, ruleSuggestion: s };
  }

  beforeEach(() => {
    finance = jasmine.createSpyObj<FinanceService>('FinanceService', [
      'getCategories', 'getAccounts', 'getTransactions', 'categorize', 'previewRuleChange', 'createRule'
    ]);
    finance.getCategories.and.returnValue(of(categories));
    finance.getAccounts.and.returnValue(of([]));
    finance.getTransactions.and.returnValue(of([{ ...tx }]));
    finance.previewRuleChange.and.returnValue(of({ totalChanges: 0, rows: [] }));
    TestBed.configureTestingModule({
      imports: [FinanceTransactionsComponent],
      providers: [{ provide: FinanceService, useValue: finance }]
    });
    fixture = TestBed.createComponent(FinanceTransactionsComponent);
    fixture.detectChanges();
  });

  const el = (): HTMLElement => fixture.nativeElement;
  const editorPatterns = (): string[] =>
    Array.from(el().querySelectorAll<HTMLInputElement>('app-finance-rule-editor .condition-row input'))
      .map(i => i.value);
  function categorizeWith(s: RuleSuggestion): void {
    finance.categorize.and.returnValue(of(categorizeAnswer(s)));
    fixture.componentInstance.onCategoryChange(fixture.componentInstance.transactions[0], 9);
    fixture.detectChanges();
  }
  function click(selector: string): void {
    (el().querySelector(selector) as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  it('suggestionOpensEditorWithItsConditionsAndHint', () => {
    categorizeWith(suggestion('zalando'));
    expect(el().textContent).toContain('Regel für künftige Buchungen?');
    expect(el().textContent).toContain('PayPal erkannt');
    expect(editorPatterns()).toEqual(['paypal', 'zalando']);
    expect((el().querySelector('app-finance-rule-editor .priority-input') as HTMLInputElement).value).toBe('100');
  });

  it('newSuggestionCreatesFreshEditor', () => {
    categorizeWith(suggestion('zalando'));
    categorizeWith(suggestion('otto'));
    expect(editorPatterns()).toEqual(['paypal', 'otto']);
  });

  it('declineClosesEditor', () => {
    categorizeWith(suggestion('zalando'));
    expect(el().querySelector('app-finance-rule-editor .cancel')!.textContent!.trim()).toBe('Nein danke');
    click('app-finance-rule-editor .cancel');
    expect(el().querySelector('app-finance-rule-editor')).toBeNull();
  });

  it('acceptGoesThroughPreviewThenCreatesRule', () => {
    finance.createRule.and.returnValue(of({ id: 3, appliedToExistingCount: 4 } as CategorizationRule));
    categorizeWith(suggestion('zalando'));
    click('app-finance-rule-editor .submit');
    expect(finance.previewRuleChange).toHaveBeenCalledOnceWith(jasmine.objectContaining({ type: 'CREATE' }));
    expect(finance.createRule).not.toHaveBeenCalled();

    const apply = Array.from(el().querySelectorAll<HTMLButtonElement>('app-finance-rule-preview button'))
      .find(b => b.textContent!.trim() === 'Speichern und anwenden')!;
    apply.click();
    fixture.detectChanges();
    expect(finance.createRule).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      categoryId: 9, applyToExisting: true
    }));
    expect(el().querySelector('app-finance-rule-editor')).toBeNull();
    expect(el().textContent).toContain('Regel gespeichert, 4 Buchungen neu zugeordnet.');
    expect(finance.getTransactions).toHaveBeenCalledTimes(2);
  });

  it('clearsInfoMessageOnNextCategoryChange', () => {
    fixture.componentInstance.infoMessage = 'Regel gespeichert, 2 Buchungen neu zugeordnet.';
    finance.categorize.and.returnValue(of({ transaction: { ...tx, categoryId: 7, manuallyCategorized: true } }));
    fixture.componentInstance.onCategoryChange(fixture.componentInstance.transactions[0], 7);
    fixture.detectChanges();
    expect(fixture.componentInstance.infoMessage).toBeNull();
    expect(el().querySelector('.info')).toBeNull();
  });
});
