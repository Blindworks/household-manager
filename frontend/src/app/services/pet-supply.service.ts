import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PetSupply, PetSupplyTransaction } from '../models/pet-supply.model';

/** Service fuer die Vorrats-API (Futter, VomiSan-Tabletten). */
@Injectable({ providedIn: 'root' })
export class PetSupplyService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/pet-supplies';

  getSupplies(): Observable<PetSupply[]> {
    return this.http.get<PetSupply[]>(this.baseUrl)
      .pipe(catchError(this.handleError));
  }

  getTransactions(key: string, limit = 50): Observable<PetSupplyTransaction[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<PetSupplyTransaction[]>(`${this.baseUrl}/${key}/transactions`, { params })
      .pipe(catchError(this.handleError));
  }

  recordPurchase(key: string, amount: number, note?: string): Observable<PetSupply> {
    return this.http.post<PetSupply>(`${this.baseUrl}/${key}/purchases`,
      { amount, note: note || null })
      .pipe(catchError(this.handleError));
  }

  correctStock(key: string, amountRemaining: number, note?: string): Observable<PetSupply> {
    return this.http.post<PetSupply>(`${this.baseUrl}/${key}/corrections`,
      { amountRemaining, note: note || null })
      .pipe(catchError(this.handleError));
  }

  updateTarget(key: string, targetAmount: number): Observable<PetSupply> {
    return this.http.put<PetSupply>(`${this.baseUrl}/${key}/target`, { targetAmount })
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Vorrats-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Vorrats-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
