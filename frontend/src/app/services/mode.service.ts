import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ModeEntity } from '../models/mode.model';

/** REST-Service für die Haus-Modi (Modus-Leiste im Dashboard). */
@Injectable({ providedIn: 'root' })
export class ModeService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/modes';

  /** Haus-Modi in Anzeige-Reihenfolge. */
  getModes(): Observable<ModeEntity[]> {
    return this.http.get<ModeEntity[]>(this.baseUrl).pipe(catchError(this.handleError));
  }

  /** Schaltet einen Modus um und liefert seinen aktualisierten Zustand. */
  toggle(entityId: string): Observable<ModeEntity> {
    return this.http.post<ModeEntity>(`${this.baseUrl}/${entityId}/toggle`, {}).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Modus-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Modus-Anfrage.'));
  }
}
