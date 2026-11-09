import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FinanceRulePreviewComponent } from './finance-rule-preview.component';
import { Category, RuleReevaluationPreview } from '../../models/finance.model';

describe('FinanceRulePreviewComponent', () => {
  let fixture: ComponentFixture<FinanceRulePreviewComponent>;
  const categories: Category[] = [{ id: 7, name: 'Lebensmittel', kind: 'EXPENSE', system: false } as Category];
  const row = { transactionId: 1, bookingDate: '2026-03-05', counterpartyName: 'REWE', purpose: 'Einkauf', amount: -12.5, oldCategoryId: null, newCategoryId: 7 };

  function setup(preview: RuleReevaluationPreview, extra: Record<string, unknown> = {}): void {
    fixture = TestBed.createComponent(FinanceRulePreviewComponent);
    fixture.componentRef.setInput('preview', preview);
    fixture.componentRef.setInput('categories', categories);
    fixture.componentRef.setInput('busy', false);
    Object.entries(extra).forEach(([k, v]) => fixture.componentRef.setInput(k, v));
    fixture.detectChanges();
  }
  const el = (): HTMLElement => fixture.nativeElement;
  const buttons = (): HTMLButtonElement[] => Array.from(el().querySelectorAll('button'));

  beforeEach(() => TestBed.configureTestingModule({ imports: [FinanceRulePreviewComponent] }));

  it('showsOldAndNewCategoryNames', () => {
    setup({ totalChanges: 1, rows: [row] });
    const text = el().textContent!;
    expect(text).toContain('Unkategorisiert');
    expect(text).toContain('Lebensmittel');
    expect(text).toContain('05.03.2026');
    expect(text).toContain('1 Buchung');
  });

  it('showsCapNoticeWhenTruncated', () => {
    setup({ totalChanges: 340, rows: [row] });
    expect(el().textContent).toContain('Es werden die neuesten 200 von 340 gezeigt.');
  });

  it('showsWarning', () => {
    setup({ totalChanges: 0, rows: [], warning: 'Achtung Test' });
    expect(el().textContent).toContain('Achtung Test');
    expect(el().textContent).toContain('Keine bestehende Buchung ändert sich.');
  });

  it('buttonsDisabledWhileBusy', () => {
    setup({ totalChanges: 0, rows: [] }, { busy: true });
    expect(buttons().length).toBe(3);
    expect(buttons().every(b => b.disabled)).toBeTrue();
  });

  it('hidesSaveOnlyWhenNotAllowed', () => {
    setup({ totalChanges: 0, rows: [] }, { allowSaveOnly: false });
    expect(buttons().map(b => b.textContent!.trim())).toEqual(['Speichern und anwenden', 'Abbrechen']);
  });
});
