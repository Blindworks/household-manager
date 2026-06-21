import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { WeatherPollingStatus } from '../models/weather-polling.model';

/**
 * Service for controlling the DWD weather polling.
 */
@Injectable({
  providedIn: 'root'
})
export class WeatherPollingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/weather-polling';

  getStatus(): Observable<WeatherPollingStatus> {
    return this.http.get<WeatherPollingStatus>(this.baseUrl).pipe(
      catchError(this.handleError)
    );
  }

  triggerOnce(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/trigger`, {}).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'Ein unbekannter Fehler ist aufgetreten';

    if (error.error instanceof ErrorEvent) {
      errorMessage = `Fehler: ${error.error.message}`;
    } else {
      switch (error.status) {
        case 400:
          errorMessage = 'Ungültige Daten. Bitte prüfen Sie Ihre Eingaben.';
          break;
        case 404:
          errorMessage = 'Die angeforderten Daten wurden nicht gefunden.';
          break;
        case 500:
          errorMessage = 'Ein Serverfehler ist aufgetreten. Bitte versuchen Sie es später erneut.';
          break;
        default:
          errorMessage = `Server-Fehler: ${error.status}`;
      }
    }

    console.error('Wetter-Polling-API-Fehler:', error);
    return throwError(() => new Error(errorMessage));
  }
}
