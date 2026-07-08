import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { EntityState, EntityDomain } from '../models/entity-state.model';

/**
 * REST-Service für die generische Entity-/State-Schicht.
 */
@Injectable({ providedIn: 'root' })
export class EntityStateService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/entities';

  getEntities(domain?: EntityDomain, source?: string): Observable<EntityState[]> {
    let params = new HttpParams();
    if (domain) { params = params.set('domain', domain); }
    if (source) { params = params.set('source', source); }
    return this.http.get<EntityState[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  deleteEntity(entityId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${entityId}`).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Entity-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Entitäten.'));
  }
}
