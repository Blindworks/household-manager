import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CalendarCategory, CalendarCategoryRequest } from '../models/calendar-category.model';

/**
 * Fehler der Kategorie-API. Traegt den HTTP-Status mit, weil die Admin-Seite den
 * Loeschkonflikt (409 — die Kategorie haengt noch an Terminen) von einem echten Ausfall
 * unterscheiden muss: nur beim 409 darf sie das Deaktivieren als Ausweg anbieten. Wuerde
 * sie das bei jedem Fehler tun, verspraeche sie bei ausgefallenem Backend einen Ausweg,
 * der genauso fehlschlaegt.
 *
 * Bleibt eine gewoehnliche `Error`-Instanz mit unveraenderter `message` — bestehende
 * Aufrufer (z. B. CalendarMasterDataService) merken davon nichts.
 */
export class CalendarCategoryApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'CalendarCategoryApiError';
  }
}

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
    return throwError(() => new CalendarCategoryApiError(message, error.status));
  }
}
