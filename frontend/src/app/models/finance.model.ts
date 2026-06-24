export type CategoryKind = 'EXPENSE' | 'INCOME' | 'TRANSFER';
export type RuleMatchField = 'COUNTERPARTY_NAME' | 'COUNTERPARTY_IBAN' | 'PURPOSE';
export type RuleMatchType = 'CONTAINS' | 'EQUALS' | 'REGEX';
export type RecurrenceInterval = 'MONTHLY' | 'QUARTERLY' | 'YEARLY';
export type BudgetStatusLevel = 'GREEN' | 'YELLOW' | 'RED';

export interface BankAccount {
  id: number;
  name: string;
  iban?: string;
  currency: string;
}

export interface BankAccountRequest {
  name: string;
  iban?: string;
  currency: string;
}

export interface Category {
  id: number;
  name: string;
  kind: CategoryKind;
  color?: string;
  system: boolean;
  parentId?: number;
}

export interface CategoryRequest {
  name: string;
  kind: CategoryKind;
  color?: string;
  parentId?: number;
}

export interface TransactionDto {
  id: number;
  accountId: number;
  bookingDate: string;
  valueDate?: string;
  amount: number;
  currency: string;
  counterpartyName?: string;
  counterpartyIban?: string;
  purpose?: string;
  categoryId?: number;
  manuallyCategorized: boolean;
}

export interface RuleSuggestion {
  field: RuleMatchField;
  matchType: RuleMatchType;
  pattern: string;
  categoryId: number;
}

export interface CategorizeResponse {
  transaction: TransactionDto;
  ruleSuggestion?: RuleSuggestion;
}

export interface CategorizationRule {
  id: number;
  field: RuleMatchField;
  matchType: RuleMatchType;
  pattern: string;
  categoryId: number;
  priority: number;
  enabled: boolean;
  appliedToExistingCount: number;
}

export interface CategorizationRuleRequest {
  field: RuleMatchField;
  matchType: RuleMatchType;
  pattern: string;
  categoryId: number;
  priority?: number;
  enabled?: boolean;
  applyToExisting?: boolean;
}

export interface ImportSummary {
  batchId: number;
  importedCount: number;
  skippedDuplicates: number;
  failedCount: number;
  uncategorizedCount: number;
  dateFrom?: string;
  dateTo?: string;
}

export interface CategorySpendItem {
  categoryId?: number;
  categoryName: string;
  color?: string;
  amount: number;
}

export interface BudgetStatusItem {
  categoryId?: number;
  categoryName: string;
  limit: number;
  spent: number;
  percent: number;
  status: BudgetStatusLevel;
}

export interface BudgetStatusResponse {
  overall?: BudgetStatusItem;
  categories: BudgetStatusItem[];
}

export interface OverviewResponse {
  month: string;
  totalExpenses: number;
  totalIncome: number;
  balance: number;
  totalInvestments: number;
  savingsRate?: number | null;
  budget: BudgetStatusResponse;
  categories: CategorySpendItem[];
}

export interface TrendPoint {
  month: string;
  expenses: number;
  income: number;
}

export interface Budget {
  id: number;
  categoryId?: number;
  period: string;
  amount: number;
}

export interface BudgetRequest {
  categoryId?: number;
  amount: number;
}

export interface RecurringPayment {
  id: number;
  accountId: number;
  counterpartyPattern: string;
  categoryId?: number;
  expectedAmount: number;
  interval: RecurrenceInterval;
  nextDueDate?: string;
  confirmed: boolean;
}
