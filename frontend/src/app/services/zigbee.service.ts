import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ZigbeeDevice, ZigbeeHealth, ZigbeeMeasurement, ZigbeeMeasurementType } from '../models/zigbee.model';

/**
 * REST-Service für Zigbee-Sensoren.
 */
@Injectable({ providedIn: 'root' })
export class ZigbeeService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/zigbee';

  getDevices(): Observable<ZigbeeDevice[]> {
    return this.http.get<ZigbeeDevice[]>(`${this.baseUrl}/devices`).pipe(
      catchError(this.handleError)
    );
  }

  getMeasurements(
    friendlyName: string,
    type: ZigbeeMeasurementType,
    from?: string,
    to?: string
  ): Observable<ZigbeeMeasurement[]> {
    let params = new HttpParams().set('type', type);
    if (from) { params = params.set('from', from); }
    if (to) { params = params.set('to', to); }
    return this.http
      .get<ZigbeeMeasurement[]>(
        `${this.baseUrl}/devices/${encodeURIComponent(friendlyName)}/measurements`,
        { params }
      )
      .pipe(catchError(this.handleError));
  }

  getHealth(): Observable<ZigbeeHealth> {
    return this.http.get<ZigbeeHealth>(`${this.baseUrl}/health`).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Zigbee API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Zigbee-Daten.'));
  }
}
