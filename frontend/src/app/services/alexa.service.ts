import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  AlexaAuthStatus, AlexaDevice, AlexaLoginResponse,
  AlexaTtsMode, ScheduledAnnouncement
} from '../models/alexa.model';

/** Service fuer die Alexa-TTS-Integration. */
@Injectable({ providedIn: 'root' })
export class AlexaService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/alexa';

  getAuthStatus(): Observable<AlexaAuthStatus> {
    return this.http.get<AlexaAuthStatus>(`${this.baseUrl}/auth/status`).pipe(catchError(this.handleError));
  }

  login(email: string, password: string, captcha?: string): Observable<AlexaLoginResponse> {
    return this.http.post<AlexaLoginResponse>(`${this.baseUrl}/auth/login`, { email, password, captcha })
      .pipe(catchError(this.handleError));
  }

  submitMfa(code: string): Observable<AlexaLoginResponse> {
    return this.http.post<AlexaLoginResponse>(`${this.baseUrl}/auth/mfa`, { code })
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

  getScheduled(): Observable<ScheduledAnnouncement[]> {
    return this.http.get<ScheduledAnnouncement[]>(`${this.baseUrl}/scheduled-announcements`)
      .pipe(catchError(this.handleError));
  }

  createScheduled(a: ScheduledAnnouncement): Observable<ScheduledAnnouncement> {
    return this.http.post<ScheduledAnnouncement>(`${this.baseUrl}/scheduled-announcements`, a)
      .pipe(catchError(this.handleError));
  }

  updateScheduled(id: number, a: ScheduledAnnouncement): Observable<ScheduledAnnouncement> {
    return this.http.put<ScheduledAnnouncement>(`${this.baseUrl}/scheduled-announcements/${id}`, a)
      .pipe(catchError(this.handleError));
  }

  deleteScheduled(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/scheduled-announcements/${id}`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Alexa-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Alexa-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
