import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TapoDiscoveryDevice } from '../models/tapo.model';

@Injectable({
  providedIn: 'root'
})
export class TapoService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/tapo';

  discover(): Observable<TapoDiscoveryDevice[]> {
    return this.http.get<TapoDiscoveryDevice[]>(`${this.baseUrl}/discover`).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'Ein unbekannter Fehler ist aufgetreten';

    if (error.error instanceof ErrorEvent) {
      errorMessage = `Fehler: ${error.error.message}`;
    } else if (typeof error.error?.message === 'string' && error.error.message.trim().length > 0) {
      errorMessage = error.error.message;
    } else {
      switch (error.status) {
        case 404:
          errorMessage = 'Tapo-Endpunkt nicht gefunden.';
          break;
        case 502:
          errorMessage = 'Keine lokale Tapo-Discovery moeglich.';
          break;
        case 500:
          errorMessage = 'Interner Serverfehler.';
          break;
        default:
          errorMessage = `Server-Fehler: ${error.status}`;
      }
    }

    console.error('Tapo API-Fehler:', error);
    return throwError(() => new Error(errorMessage));
  }
}
