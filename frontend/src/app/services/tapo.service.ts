import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TapoDeviceInfo, TapoDiscoveryDevice, TapoEnergyUsage } from '../models/tapo.model';

@Injectable({
  providedIn: 'root'
})
export class TapoService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/tapo';

  discover(): Observable<TapoDiscoveryDevice[]> {
    return this.http.get<TapoDiscoveryDevice[]>(`${this.baseUrl}/devices`).pipe(
      catchError(this.handleError)
    );
  }

  getDeviceInfo(ip: string): Observable<TapoDeviceInfo> {
    return this.http.get<TapoDeviceInfo>(`${this.baseUrl}/devices/${encodeURIComponent(ip)}/info`).pipe(
      catchError(this.handleError)
    );
  }

  getEnergyUsage(ip: string): Observable<TapoEnergyUsage> {
    return this.http.get<TapoEnergyUsage>(`${this.baseUrl}/devices/${encodeURIComponent(ip)}/energy`).pipe(
      catchError(this.handleError)
    );
  }

  turnOn(ip: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(ip)}/on`, null).pipe(
      catchError(this.handleError)
    );
  }

  turnOff(ip: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(ip)}/off`, null).pipe(
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
