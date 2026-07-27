import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CalendarCategory, CalendarCategoryRequest } from '../models/calendar-category.model';

/** Service fuer die Kalender-Kategorien (Stammdaten). */
@Injectable({ providedIn: 'root' })
export class CalendarCategoryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/calendar/categories';

  /** Alle Kategorien inkl. deaktivierter — Bestandstermine brauchen ihre Farbe weiter. */
  list(): Observable<CalendarCategory[]> {
    return this.http.get<CalendarCategory[]>(this.baseUrl)
      .pipe(catchError(this.handleError));
  }

  create(request: CalendarCategoryRequest): Observable<CalendarCategory> {
    return this.http.post<CalendarCategory>(this.baseUrl, request)
      .pipe(catchError(this.handleError));
  }

  update(id: number, request: CalendarCategoryRequest): Observable<CalendarCategory> {
    return this.http.put<CalendarCategory>(`${this.baseUrl}/${id}`, request)
      .pipe(catchError(this.handleError));
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Kalender-Kategorie-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Kalender-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
