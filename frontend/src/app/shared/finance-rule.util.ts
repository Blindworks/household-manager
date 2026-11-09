import { RuleCondition, RuleMatchField, RuleMatchType } from '../models/finance.model';

export const MAX_RULE_CONDITIONS = 5;

export const FIELD_LABELS: Record<RuleMatchField, string> = {
  COUNTERPARTY_NAME: 'Empfänger',
  COUNTERPARTY_IBAN: 'IBAN',
  PURPOSE: 'Verwendungszweck'
};

export const MATCH_TYPE_LABELS: Record<RuleMatchType, string> = {
  CONTAINS: 'enthält',
  EQUALS: 'ist gleich',
  REGEX: 'Regex'
};

export function describeConditions(conditions: RuleCondition[]): string {
  return conditions
    .map(c => `${FIELD_LABELS[c.field]} ${MATCH_TYPE_LABELS[c.matchType]} „${c.pattern}“`)
    .join(' und ');
}

export function canSaveConditions(conditions: RuleCondition[]): boolean {
  return conditions.length >= 1
    && conditions.length <= MAX_RULE_CONDITIONS
    && conditions.every(c => c.pattern.trim().length > 0);
}

export function newCondition(): RuleCondition {
  return { field: 'PURPOSE', matchType: 'CONTAINS', pattern: '' };
}
