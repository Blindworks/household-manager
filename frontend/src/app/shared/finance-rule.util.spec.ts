import { RuleCondition } from '../models/finance.model';
import { canSaveConditions, describeConditions, MAX_RULE_CONDITIONS, newCondition } from './finance-rule.util';

const valid = (pattern = 'X'): RuleCondition => ({ field: 'PURPOSE', matchType: 'CONTAINS', pattern });

describe('finance-rule.util', () => {
  it('beschreibt mehrere Bedingungen mit „und“', () => {
    const text = describeConditions([
      { field: 'COUNTERPARTY_NAME', matchType: 'CONTAINS', pattern: 'PAYPAL' },
      { field: 'PURPOSE', matchType: 'CONTAINS', pattern: 'SHELL DEUTSCHLAND' }
    ]);
    expect(text).toBe('Empfänger enthält „PAYPAL“ und Verwendungszweck enthält „SHELL DEUTSCHLAND“');
  });

  it('beschreibt IBAN, ist gleich und Regex', () => {
    expect(describeConditions([{ field: 'COUNTERPARTY_IBAN', matchType: 'EQUALS', pattern: 'DE1' }]))
      .toBe('IBAN ist gleich „DE1“');
    expect(describeConditions([{ field: 'PURPOSE', matchType: 'REGEX', pattern: 'a.*' }]))
      .toBe('Verwendungszweck Regex „a.*“');
  });

  it('lässt keine leere Liste zu', () => {
    expect(canSaveConditions([])).toBeFalse();
  });

  it('lässt keine leeren oder nur aus Leerzeichen bestehenden Muster zu', () => {
    expect(canSaveConditions([valid('  ')])).toBeFalse();
    expect(canSaveConditions([valid(), valid('')])).toBeFalse();
  });

  it('erlaubt 1 bis 5 gültige Bedingungen, nicht 6', () => {
    expect(MAX_RULE_CONDITIONS).toBe(5);
    expect(canSaveConditions([valid()])).toBeTrue();
    expect(canSaveConditions(Array.from({ length: 5 }, () => valid()))).toBeTrue();
    expect(canSaveConditions(Array.from({ length: 6 }, () => valid()))).toBeFalse();
  });

  it('erzeugt eine neue Bedingung mit Standardwerten', () => {
    expect(newCondition()).toEqual({ field: 'PURPOSE', matchType: 'CONTAINS', pattern: '' });
  });
});
