import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AlexaAuthStatus, AlexaDevice, AlexaProxyStartResponse, AlexaTtsMode
} from '../models/alexa.model';

/** Service fuer die Alexa-TTS-Integration. */
@Injectable({ providedIn: 'root' })
export class AlexaService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/alexa';

  getAuthStatus(): Observable<AlexaAuthStatus> {
    return this.http.get<AlexaAuthStatus>(`${this.baseUrl}/auth/status`).pipe(catchError(this.handleError));
  }

  /** Startet den Browser-Login und liefert die URL, die der Nutzer oeffnen soll. */
  startProxyLogin(): Observable<AlexaProxyStartResponse> {
    return this.http.post<AlexaProxyStartResponse>(`${this.baseUrl}/auth/proxy/start`, {})
      .pipe(catchError(this.handleError));
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/logout`, {}).pipe(catchError(this.handleError));
  }

  getDevices(rescan = false): Observable<AlexaDevice[]> {
    return this.http.get<AlexaDevice[]>(`${this.baseUrl}/devices?rescan=${rescan}`)
      .pipe(catchError(this.handleError));
  }

  announce(payload: { text: string; serialNumbers: string[]; mode: AlexaTtsMode }): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/announce`, payload).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Alexa-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Alexa-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
