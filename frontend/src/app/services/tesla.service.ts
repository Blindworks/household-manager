import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  TeslaCommand,
  TeslaSettings,
  TeslaSettingsUpdate,
  TeslaStatus,
  TessieVehicle
} from '../models/tesla.model';

/** REST-Service für das Tesla-Fahrzeug (Tessie) und seine Admin-Einstellungen. */
@Injectable({ providedIn: 'root' })
export class TeslaService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/tesla';

  getStatus(): Observable<TeslaStatus> {
    return this.http.get<TeslaStatus>(`${this.baseUrl}/vehicle`).pipe(catchError(toError));
  }

  sendCommand(command: TeslaCommand): Observable<TeslaStatus> {
    return this.http.post<TeslaStatus>(`${this.baseUrl}/vehicle/commands`, { command })
      .pipe(catchError(toError));
  }

  getSettings(): Observable<TeslaSettings> {
    return this.http.get<TeslaSettings>(`${this.baseUrl}/settings`).pipe(catchError(toError));
  }

  saveSettings(update: TeslaSettingsUpdate): Observable<TeslaSettings> {
    return this.http.put<TeslaSettings>(`${this.baseUrl}/settings`, update).pipe(catchError(toError));
  }

  listVehicles(apiToken?: string): Observable<TessieVehicle[]> {
    return this.http.post<TessieVehicle[]>(`${this.baseUrl}/settings/vehicles`,
      apiToken ? { apiToken } : {}).pipe(catchError(toError));
  }

  refresh(): Observable<TeslaStatus> {
    return this.http.post<TeslaStatus>(`${this.baseUrl}/settings/refresh`, {}).pipe(catchError(toError));
  }
}

/** Fehlertext des Backends (400/502) weiterreichen, sonst ein allgemeiner. */
function toError(error: HttpErrorResponse): Observable<never> {
  const message = typeof error.error?.message === 'string' && error.error.message
    ? error.error.message
    : error.status === 403 ? 'Dafür fehlt die Berechtigung.' : 'Tesla-Anfrage fehlgeschlagen.';
  return throwError(() => new Error(message));
}
