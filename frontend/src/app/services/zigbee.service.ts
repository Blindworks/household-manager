import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  FlowReference,
  PurgeResult,
  ZigbeeBridgeEvent,
  ZigbeeBridgeStatus,
  ZigbeeDevice,
  ZigbeeHealth,
  ZigbeeMeasurement,
  ZigbeeMeasurementType
} from '../models/zigbee.model';

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

  getBridge(): Observable<ZigbeeBridgeStatus> {
    return this.http.get<ZigbeeBridgeStatus>(`${this.baseUrl}/bridge`).pipe(catchError(this.handleError));
  }

  getBridgeEvents(): Observable<ZigbeeBridgeEvent[]> {
    return this.http.get<ZigbeeBridgeEvent[]>(`${this.baseUrl}/bridge/events`).pipe(catchError(this.handleError));
  }

  getFlowReferences(friendlyName: string): Observable<FlowReference[]> {
    const params = new HttpParams().set('friendlyName', friendlyName);
    return this.http.get<FlowReference[]>(`${this.baseUrl}/flow-references`, { params }).pipe(catchError(this.handleError));
  }

  openPermitJoin(seconds: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/bridge/permit-join`, { seconds }).pipe(catchError(this.handleError));
  }

  closePermitJoin(): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/bridge/permit-join`).pipe(catchError(this.handleError));
  }

  renameDevice(ieeeAddress: string, friendlyName: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}/name`, { friendlyName })
      .pipe(catchError(this.handleError));
  }

  interviewDevice(ieeeAddress: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}/interview`, {})
      .pipe(catchError(this.handleError));
  }

  configureDevice(ieeeAddress: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}/configure`, {})
      .pipe(catchError(this.handleError));
  }

  removeDevice(ieeeAddress: string, force: boolean): Observable<void> {
    const params = new HttpParams().set('force', String(force));
    return this.http.delete<void>(`${this.baseUrl}/devices/${encodeURIComponent(ieeeAddress)}`, { params })
      .pipe(catchError(this.handleError));
  }

  purgeLocalData(deviceId: number): Observable<PurgeResult> {
    return this.http.delete<PurgeResult>(`${this.baseUrl}/devices/local/${deviceId}`).pipe(catchError(this.handleError));
  }

  /** Server-Meldung (z. B. z2m's Fehlertext) durchreichen, sonst generischer Text. */
  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Zigbee API-Fehler:', error);
    const message = typeof error.error?.message === 'string' && error.error.message.trim()
      ? error.error.message
      : 'Fehler bei der Zigbee-Anfrage.';
    return throwError(() => new Error(message));
  }
}
