import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  EntityState,
  EntityDomain,
  TileVisibility,
  CreateManualEntityRequest,
  RenameManualEntityRequest
} from '../models/entity-state.model';

/**
 * REST-Service für die generische Entity-/State-Schicht.
 */
@Injectable({ providedIn: 'root' })
export class EntityStateService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/entities';
  private readonly manualUrl = `${this.baseUrl}/manual`;

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

  /** Setzt oder löscht (null) den Kurznamen einer Entität. Gilt für alle Quellen. */
  setCustomName(entityId: string, customName: string | null): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.baseUrl}/${entityId}/custom-name`, { customName }).pipe(
      catchError(this.handleError)
    );
  }

  /** Setzt die Kachel-Sichtbarkeit einer Entität; 'AUTO' entfernt die Regel. */
  setTileVisibility(entityId: string, tileKey: string, visibility: TileVisibility): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.baseUrl}/${entityId}/tiles/${tileKey}`, { visibility }).pipe(
      catchError(this.handleError)
    );
  }

  /** Setzt die Bestätigungspflicht eines Schalters (reiner UI-Schutz). */
  setConfirmRequired(entityId: string, confirmRequired: boolean): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.baseUrl}/${entityId}/confirm-required`, { confirmRequired }).pipe(
      catchError(this.handleError)
    );
  }

  /** Legt eine vom Benutzer definierte Boolean-Entität an (z. B. "Nachtmodus"). */
  createManualEntity(request: CreateManualEntityRequest): Observable<EntityState> {
    return this.http.post<EntityState>(this.manualUrl, request).pipe(
      catchError(this.handleError)
    );
  }

  /** Setzt den Zustand ("on"/"off") einer manuellen Entität. */
  setManualState(entityId: string, state: string): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.manualUrl}/${entityId}/state`, { state }).pipe(
      catchError(this.handleError)
    );
  }

  /** Schaltet eine manuelle Entität um (on ↔ off). */
  toggleManualEntity(entityId: string): Observable<EntityState> {
    return this.http.post<EntityState>(`${this.manualUrl}/${entityId}/toggle`, {}).pipe(
      catchError(this.handleError)
    );
  }

  /** Benennt eine manuelle Entität um; die Entity-ID bleibt unverändert. */
  renameManualEntity(entityId: string, request: RenameManualEntityRequest): Observable<EntityState> {
    return this.http.put<EntityState>(`${this.manualUrl}/${entityId}`, request).pipe(
      catchError(this.handleError)
    );
  }

  /** Löscht eine manuelle Entität. */
  deleteManualEntity(entityId: string): Observable<void> {
    return this.http.delete<void>(`${this.manualUrl}/${entityId}`).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Entity-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Entitäten-Anfrage.'));
  }
}
