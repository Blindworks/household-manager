import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { SwitchEntity } from '../models/switch.model';

/**
 * REST-Service für die schaltbaren Entitäten (Schalter-Kachel und -Dialog).
 */
@Injectable({ providedIn: 'root' })
export class SwitchService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/switches';

  /** Schaltbare Entitäten, meistgenutzte zuerst. */
  getSwitches(limit?: number): Observable<SwitchEntity[]> {
    let params = new HttpParams();
    if (limit != null) {
      params = params.set('limit', limit);
    }
    return this.http.get<SwitchEntity[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  /** Schaltet eine Entität um und liefert ihren aktualisierten Zustand. */
  toggle(entityId: string): Observable<SwitchEntity> {
    return this.http.post<SwitchEntity>(`${this.baseUrl}/${entityId}/toggle`, {}).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Schalter-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Schalter-Anfrage.'));
  }
}
