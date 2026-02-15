import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MerossCloudDevicesResponse, MerossCloudLoginResponse, MerossPlugResponse } from '../models/meross.model';

@Injectable({
  providedIn: 'root'
})
export class MerossService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/meross';

  discoverPlugs(): Observable<MerossPlugResponse[]> {
    return this.http.get<MerossPlugResponse[]>(`${this.baseUrl}/devices`).pipe(
      catchError(this.handleError)
    );
  }

  getStatus(deviceId: string): Observable<MerossPlugResponse> {
    return this.http.get<MerossPlugResponse>(`${this.baseUrl}/devices/${encodeURIComponent(deviceId)}/status`).pipe(
      catchError(this.handleError)
    );
  }

  turnOn(deviceId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(deviceId)}/on`, {}).pipe(
      catchError(this.handleError)
    );
  }

  turnOff(deviceId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(deviceId)}/off`, {}).pipe(
      catchError(this.handleError)
    );
  }

  loginWithConfig(): Observable<MerossCloudLoginResponse> {
    return this.http.post<MerossCloudLoginResponse>(`${this.baseUrl}/cloud/login/config`, {}).pipe(
      catchError(this.handleError)
    );
  }

  getCloudDevices(): Observable<MerossCloudDevicesResponse> {
    return this.http.get<MerossCloudDevicesResponse>(`${this.baseUrl}/cloud/devices`).pipe(
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
          errorMessage = 'Meross ist nicht konfiguriert.';
          break;
        case 401:
          errorMessage = 'Meross Login fehlgeschlagen. Zugangsdaten pruefen.';
          break;
        case 404:
          errorMessage = 'Meross-Geraet oder Endpunkt nicht gefunden.';
          break;
        case 502:
          errorMessage = 'Meross Cloud nicht erreichbar.';
          break;
        case 500:
          errorMessage = 'Interner Serverfehler.';
          break;
        default:
          errorMessage = `Server-Fehler: ${error.status}`;
      }
    }

    console.error('Meross API-Fehler:', error);
    return throwError(() => new Error(errorMessage));
  }
}
