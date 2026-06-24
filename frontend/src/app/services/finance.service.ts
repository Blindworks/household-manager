import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  BankAccount, BankAccountRequest, Budget, BudgetRequest, BudgetStatusResponse,
  Category, CategoryRequest, CategorizationRule, CategorizationRuleRequest,
  CategorizeResponse, ImportSummary, OverviewResponse, RecurringPayment,
  TransactionDto, TrendPoint
} from '../models/finance.model';

/**
 * Service für das Ausgaben-Tracking (Finance). Kapselt alle API-Calls.
 */
@Injectable({ providedIn: 'root' })
export class FinanceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/finance';

  // --- Accounts ---
  getAccounts(): Observable<BankAccount[]> {
    return this.http.get<BankAccount[]>(`${this.baseUrl}/accounts`).pipe(catchError(this.handleError));
  }
  createAccount(req: BankAccountRequest): Observable<BankAccount> {
    return this.http.post<BankAccount>(`${this.baseUrl}/accounts`, req).pipe(catchError(this.handleError));
  }
  updateAccount(id: number, req: BankAccountRequest): Observable<BankAccount> {
    return this.http.put<BankAccount>(`${this.baseUrl}/accounts/${id}`, req).pipe(catchError(this.handleError));
  }
  deleteAccount(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/accounts/${id}`).pipe(catchError(this.handleError));
  }

  // --- Import ---
  importStatement(accountId: number, file: File): Observable<ImportSummary> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    const params = new HttpParams().set('accountId', accountId);
    return this.http.post<ImportSummary>(`${this.baseUrl}/import`, formData, { params })
      .pipe(catchError(this.handleError));
  }

  // --- Categories ---
  getCategories(): Observable<Category[]> {
    return this.http.get<Category[]>(`${this.baseUrl}/categories`).pipe(catchError(this.handleError));
  }
  createCategory(req: CategoryRequest): Observable<Category> {
    return this.http.post<Category>(`${this.baseUrl}/categories`, req).pipe(catchError(this.handleError));
  }
  updateCategory(id: number, req: CategoryRequest): Observable<Category> {
    return this.http.put<Category>(`${this.baseUrl}/categories/${id}`, req).pipe(catchError(this.handleError));
  }
  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/categories/${id}`).pipe(catchError(this.handleError));
  }

  // --- Transactions ---
  getTransactions(from: string, to: string, accountId?: number): Observable<TransactionDto[]> {
    let params = new HttpParams().set('from', from).set('to', to);
    if (accountId != null) {
      params = params.set('accountId', accountId);
    }
    return this.http.get<TransactionDto[]>(`${this.baseUrl}/transactions`, { params })
      .pipe(catchError(this.handleError));
  }
  categorize(transactionId: number, categoryId: number): Observable<CategorizeResponse> {
    return this.http.patch<CategorizeResponse>(
      `${this.baseUrl}/transactions/${transactionId}/category`, { categoryId })
      .pipe(catchError(this.handleError));
  }

  // --- Rules ---
  getRules(): Observable<CategorizationRule[]> {
    return this.http.get<CategorizationRule[]>(`${this.baseUrl}/rules`).pipe(catchError(this.handleError));
  }
  createRule(req: CategorizationRuleRequest): Observable<CategorizationRule> {
    return this.http.post<CategorizationRule>(`${this.baseUrl}/rules`, req).pipe(catchError(this.handleError));
  }
  updateRule(id: number, req: CategorizationRuleRequest): Observable<CategorizationRule> {
    return this.http.put<CategorizationRule>(`${this.baseUrl}/rules/${id}`, req).pipe(catchError(this.handleError));
  }
  deleteRule(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/rules/${id}`).pipe(catchError(this.handleError));
  }
  applyRules(): Observable<{ applied: number }> {
    return this.http.post<{ applied: number }>(`${this.baseUrl}/rules/apply`, {}).pipe(catchError(this.handleError));
  }

  // --- Analytics ---
  getOverview(month: string, accountId?: number): Observable<OverviewResponse> {
    let params = new HttpParams().set('month', month);
    if (accountId != null) {
      params = params.set('accountId', accountId);
    }
    return this.http.get<OverviewResponse>(`${this.baseUrl}/analytics/overview`, { params })
      .pipe(catchError(this.handleError));
  }
  getTrend(from: string, to: string, accountId?: number): Observable<TrendPoint[]> {
    let params = new HttpParams().set('from', from).set('to', to);
    if (accountId != null) {
      params = params.set('accountId', accountId);
    }
    return this.http.get<TrendPoint[]>(`${this.baseUrl}/analytics/trend`, { params })
      .pipe(catchError(this.handleError));
  }

  // --- Budgets ---
  getBudgets(): Observable<Budget[]> {
    return this.http.get<Budget[]>(`${this.baseUrl}/budgets`).pipe(catchError(this.handleError));
  }
  saveBudget(req: BudgetRequest): Observable<Budget> {
    return this.http.post<Budget>(`${this.baseUrl}/budgets`, req).pipe(catchError(this.handleError));
  }
  deleteBudget(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/budgets/${id}`).pipe(catchError(this.handleError));
  }
  getBudgetStatus(month: string): Observable<BudgetStatusResponse> {
    const params = new HttpParams().set('month', month);
    return this.http.get<BudgetStatusResponse>(`${this.baseUrl}/budgets/status`, { params })
      .pipe(catchError(this.handleError));
  }

  // --- Recurring ---
  getRecurring(confirmed?: boolean): Observable<RecurringPayment[]> {
    let params = new HttpParams();
    if (confirmed != null) {
      params = params.set('confirmed', confirmed);
    }
    return this.http.get<RecurringPayment[]>(`${this.baseUrl}/recurring`, { params })
      .pipe(catchError(this.handleError));
  }
  detectRecurring(accountId: number): Observable<RecurringPayment[]> {
    const params = new HttpParams().set('accountId', accountId);
    return this.http.post<RecurringPayment[]>(`${this.baseUrl}/recurring/detect`, {}, { params })
      .pipe(catchError(this.handleError));
  }
  confirmRecurring(id: number): Observable<RecurringPayment> {
    return this.http.post<RecurringPayment>(`${this.baseUrl}/recurring/${id}/confirm`, {})
      .pipe(catchError(this.handleError));
  }
  deleteRecurring(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/recurring/${id}`).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let message = 'Ein unbekannter Fehler ist aufgetreten';
    if (error.error instanceof ErrorEvent) {
      message = `Fehler: ${error.error.message}`;
    } else if (typeof error.error === 'string' && error.error.length > 0) {
      message = error.error;
    } else {
      switch (error.status) {
        case 400: message = 'Ungültige Daten oder Datei.'; break;
        case 404: message = 'Nicht gefunden.'; break;
        case 500: message = 'Serverfehler. Bitte später erneut versuchen.'; break;
        default: message = `Server-Fehler: ${error.status}`;
      }
    }
    console.error('Finance API-Fehler:', error);
    return throwError(() => new Error(message));
  }
}
