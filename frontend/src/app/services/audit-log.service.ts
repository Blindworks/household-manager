import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuditActionOption, AuditPage, AuditQuery } from '../models/auth.model';

/** Admin-API des Audit-Logs. */
@Injectable({ providedIn: 'root' })
export class AuditLogService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/audit-log';

  search(query: AuditQuery, page: number, size: number): Observable<AuditPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (query.from) { params = params.set('from', query.from); }
    query.actorKinds?.forEach(kind => (params = params.append('actorKind', kind)));
    if (query.action) { params = params.set('action', query.action); }
    if (query.q) { params = params.set('q', query.q); }
    return this.http.get<AuditPage>(this.baseUrl, { params }).pipe(catchError(this.handleError));
  }

  getActions(): Observable<AuditActionOption[]> {
    return this.http.get<AuditActionOption[]>(`${this.baseUrl}/actions`).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Audit-Log-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden des Audit-Logs.'));
  }
}
