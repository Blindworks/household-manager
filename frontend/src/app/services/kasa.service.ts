import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { KasaDiscoveryDevice, KasaStatus } from '../models/kasa.model';

@Injectable({
  providedIn: 'root'
})
export class KasaService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/kasa';

  discover(): Observable<KasaDiscoveryDevice[]> {
    return this.http.get<KasaDiscoveryDevice[]>(`${this.baseUrl}/discover`).pipe(
      catchError(this.handleError)
    );
  }

  getStatus(ip: string): Observable<KasaStatus> {
    return this.http.get<KasaStatus>(`${this.baseUrl}/${encodeURIComponent(ip)}/status`).pipe(
      catchError(this.handleError)
    );
  }

  turnOn(ip: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${encodeURIComponent(ip)}/on`, {}).pipe(
      catchError(this.handleError)
    );
  }

  turnOff(ip: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${encodeURIComponent(ip)}/off`, {}).pipe(
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
        case 400:
          errorMessage = 'Ungueltige Anfrage.';
          break;
        case 404:
          errorMessage = 'Kasa-Geraet oder Endpunkt nicht gefunden.';
          break;
        case 502:
          errorMessage = 'Keine Verbindung zum Kasa-Geraet moeglich.';
          break;
        case 500:
          errorMessage = 'Interner Serverfehler.';
          break;
        default:
          errorMessage = `Server-Fehler: ${error.status}`;
      }
    }

    console.error('Kasa API-Fehler:', error);
    return throwError(() => new Error(errorMessage));
  }
}
