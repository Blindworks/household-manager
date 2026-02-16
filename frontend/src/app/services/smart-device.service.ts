import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { SmartDevice, SmartDeviceScanRequest, SmartDeviceUpdateRequest } from '../models/smart-device.model';

@Injectable({
  providedIn: 'root'
})
export class SmartDeviceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/devices';

  getAllDevices(): Observable<SmartDevice[]> {
    return this.http.get<SmartDevice[]>(this.baseUrl).pipe(
      catchError(this.handleError)
    );
  }

  getDeviceById(id: number): Observable<SmartDevice> {
    return this.http.get<SmartDevice>(`${this.baseUrl}/${id}`).pipe(
      catchError(this.handleError)
    );
  }

  scanDevices(deviceType: string): Observable<SmartDevice[]> {
    const request: SmartDeviceScanRequest = { deviceType: deviceType as any };
    return this.http.post<SmartDevice[]>(`${this.baseUrl}/scan`, request).pipe(
      catchError(this.handleError)
    );
  }

  updateDevice(id: number, request: SmartDeviceUpdateRequest): Observable<SmartDevice> {
    return this.http.put<SmartDevice>(`${this.baseUrl}/${id}`, request).pipe(
      catchError(this.handleError)
    );
  }

  deleteDevice(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(
      catchError(this.handleError)
    );
  }

  refreshDeviceState(id: number): Observable<SmartDevice> {
    return this.http.post<SmartDevice>(`${this.baseUrl}/${id}/refresh`, {}).pipe(
      catchError(this.handleError)
    );
  }

  turnOn(id: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/on`, {}).pipe(
      catchError(this.handleError)
    );
  }

  turnOff(id: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/off`, {}).pipe(
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
          errorMessage = 'Geraet nicht gefunden.';
          break;
        case 502:
          errorMessage = 'Keine Verbindung zum Geraet moeglich.';
          break;
        case 500:
          errorMessage = 'Interner Serverfehler.';
          break;
        default:
          errorMessage = `Server-Fehler: ${error.status}`;
      }
    }

    console.error('Smart Device API-Fehler:', error);
    return throwError(() => new Error(errorMessage));
  }
}
