import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  WasteCollectionEvent, WasteCollectionPollingStatus, WasteCollectionSettings
} from '../models/waste-collection.model';

/** Service fuer die Muellabfuhr-API. */
@Injectable({ providedIn: 'root' })
export class WasteCollectionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/waste-collection';
  private readonly adminUrl = '/api/v1/admin/waste-polling';

  /** Ohne days gilt das konfigurierte Vorschau-Fenster. */
  getUpcoming(days?: number): Observable<WasteCollectionEvent[]> {
    const options = days === undefined
      ? {}
      : { params: new HttpParams().set('days', days) };
    return this.http.get<WasteCollectionEvent[]>(`${this.baseUrl}/upcoming`, options)
      .pipe(catchError(this.handleError));
  }

  getSettings(): Observable<WasteCollectionSettings> {
    return this.http.get<WasteCollectionSettings>(`${this.baseUrl}/settings`)
      .pipe(catchError(this.handleError));
  }

  updateSettings(settings: WasteCollectionSettings): Observable<WasteCollectionSettings> {
    return this.http.put<WasteCollectionSettings>(`${this.baseUrl}/settings`, settings)
      .pipe(catchError(this.handleError));
  }

  getPollingStatus(): Observable<WasteCollectionPollingStatus> {
    return this.http.get<WasteCollectionPollingStatus>(this.adminUrl)
      .pipe(catchError(this.handleError));
  }

  triggerPoll(): Observable<void> {
    return this.http.post<void>(`${this.adminUrl}/trigger`, {})
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Muellabfuhr-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Muellabfuhr-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
