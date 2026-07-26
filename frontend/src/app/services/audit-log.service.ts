import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuditEntry } from '../models/auth.model';

/** Admin-API des Audit-Logs. */
@Injectable({ providedIn: 'root' })
export class AuditLogService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/audit-log';

  getEntries(limit: number, actor?: string): Observable<AuditEntry[]> {
    let params = new HttpParams().set('limit', limit);
    if (actor) {
      params = params.set('actor', actor);
    }
    return this.http.get<AuditEntry[]>(this.baseUrl, { params }).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Audit-Log-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden des Audit-Logs.'));
  }
}
