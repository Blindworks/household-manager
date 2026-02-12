import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MerossCloudDevicesResponse, MerossCloudLoginResponse } from '../models/meross.model';

@Injectable({
  providedIn: 'root'
})
export class MerossService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/meross/cloud';

  loginWithConfig(): Observable<MerossCloudLoginResponse> {
    return this.http.post<MerossCloudLoginResponse>(`${this.baseUrl}/login/config`, {}).pipe(
      catchError(this.handleError)
    );
  }

  getDevices(): Observable<MerossCloudDevicesResponse> {
    return this.http.get<MerossCloudDevicesResponse>(`${this.baseUrl}/devices`).pipe(
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
