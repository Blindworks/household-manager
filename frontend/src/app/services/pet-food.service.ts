import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PetFoodStatus, PetFoodTransaction } from '../models/pet-food.model';

/** Service fuer die Futtervorrats-API. */
@Injectable({ providedIn: 'root' })
export class PetFoodService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/pet-food';

  getStatus(): Observable<PetFoodStatus> {
    return this.http.get<PetFoodStatus>(this.baseUrl)
      .pipe(catchError(this.handleError));
  }

  getTransactions(limit = 50): Observable<PetFoodTransaction[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<PetFoodTransaction[]>(`${this.baseUrl}/transactions`, { params })
      .pipe(catchError(this.handleError));
  }

  recordPurchase(cans: number, note?: string): Observable<PetFoodStatus> {
    return this.http.post<PetFoodStatus>(`${this.baseUrl}/purchases`, { cans, note: note || null })
      .pipe(catchError(this.handleError));
  }

  correctStock(cansRemaining: number, note?: string): Observable<PetFoodStatus> {
    return this.http.post<PetFoodStatus>(`${this.baseUrl}/corrections`,
      { cansRemaining, note: note || null })
      .pipe(catchError(this.handleError));
  }

  updateTarget(targetCans: number): Observable<PetFoodStatus> {
    return this.http.put<PetFoodStatus>(`${this.baseUrl}/target`, { targetCans })
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Futtervorrat-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Futtervorrat-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
