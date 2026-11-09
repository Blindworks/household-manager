import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FinanceRuleEditorComponent } from './finance-rule-editor.component';
import { Category, CategorizationRuleRequest, RuleCondition } from '../../models/finance.model';

describe('FinanceRuleEditorComponent', () => {
  let fixture: ComponentFixture<FinanceRuleEditorComponent>;
  const categories: Category[] = [{ id: 7, name: 'Lebensmittel', kind: 'EXPENSE', system: false } as Category];

  function setup(conditions: RuleCondition[], categoryId = 7): void {
    fixture = TestBed.createComponent(FinanceRuleEditorComponent);
    fixture.componentRef.setInput('conditions', conditions);
    fixture.componentRef.setInput('categoryId', categoryId);
    fixture.componentRef.setInput('priority', null);
    fixture.componentRef.setInput('categories', categories);
    fixture.componentRef.setInput('hint', null);
    fixture.detectChanges();
  }
  const el = (): HTMLElement => fixture.nativeElement;
  const rows = (): HTMLElement[] => Array.from(el().querySelectorAll('.condition-row'));
  const addBtn = (): HTMLButtonElement => el().querySelector('.add-condition') as HTMLButtonElement;
  const submitBtn = (): HTMLButtonElement => el().querySelector('.submit') as HTMLButtonElement;
  const cond = (pattern: string): RuleCondition => ({ field: 'PURPOSE', matchType: 'CONTAINS', pattern });

  beforeEach(() => TestBed.configureTestingModule({ imports: [FinanceRuleEditorComponent] }));

  it('addButtonDisabledAtFiveConditions', () => {
    setup([cond('a'), cond('b'), cond('c'), cond('d')]);
    expect(addBtn().disabled).toBeFalse();
    addBtn().click();
    fixture.detectChanges();
    expect(rows().length).toBe(5);
    expect(addBtn().disabled).toBeTrue();
  });

  it('submitDisabledWhilePatternEmpty', () => {
    setup([cond('amazon'), cond('')]);
    expect(submitBtn().disabled).toBeTrue();
    expect(rows()[1].querySelector('input')!.classList).toContain('is-empty');
  });

  it('submitEmitsRequestWithEditedPattern', () => {
    setup([cond('')]);
    let emitted: CategorizationRuleRequest | undefined;
    fixture.componentInstance.submitted.subscribe(r => emitted = r);
    const input = rows()[0].querySelector('input') as HTMLInputElement;
    input.value = '  rewe  ';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(submitBtn().disabled).toBeFalse();
    submitBtn().click();
    expect(emitted).toEqual({
      conditions: [{ field: 'PURPOSE', matchType: 'CONTAINS', pattern: 'rewe' }],
      categoryId: 7
    });
  });

  it('removingLastConditionIsNotPossible', () => {
    setup([cond('a'), cond('b')]);
    expect(el().querySelectorAll('.remove-condition').length).toBe(2);
    (el().querySelector('.remove-condition') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(rows().length).toBe(1);
    expect(el().querySelectorAll('.remove-condition').length).toBe(0);
  });

  it('inputConditionsAreNotMutated', () => {
    const original = [cond('a')];
    setup(original);
    const input = rows()[0].querySelector('input') as HTMLInputElement;
    input.value = 'changed';
    input.dispatchEvent(new Event('input'));
    addBtn().click();
    fixture.detectChanges();
    expect(original).toEqual([cond('a')]);
  });
});
