import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  DebugEntry, FlowDetail, FlowSummary, NodeType, ValidationResult
} from '../models/flow.model';

/** REST-Anbindung an die Flow-Engine (/api/v1/flows). */
@Injectable({ providedIn: 'root' })
export class FlowService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/flows';

  getFlows(): Observable<FlowSummary[]> {
    return this.http.get<FlowSummary[]>(this.baseUrl);
  }

  getFlow(id: number): Observable<FlowDetail> {
    return this.http.get<FlowDetail>(`${this.baseUrl}/${id}`);
  }

  getNodeTypes(): Observable<NodeType[]> {
    return this.http.get<NodeType[]>(`${this.baseUrl}/node-types`);
  }

  createFlow(name: string, description: string): Observable<FlowDetail> {
    return this.http.post<FlowDetail>(this.baseUrl, { name, description });
  }

  /** Importiert eine extern erzeugte Flow-Wrapper-Datei; legt einen deaktivierten Draft an. */
  importFlow(payload: unknown): Observable<FlowDetail> {
    return this.http.post<FlowDetail>(`${this.baseUrl}/import`, payload);
  }

  saveDraft(id: number, name: string, description: string, draftDefinition: string): Observable<FlowDetail> {
    return this.http.put<FlowDetail>(`${this.baseUrl}/${id}`, { name, description, draftDefinition });
  }

  /**
   * Deploy: 200 (valide) und 400 (invalide) liefern denselben ValidationResult-Body.
   * Bei echten Netzwerkfehlern (Timeout/DNS/Status 0) ist err.error kein ValidationResult
   * (z.B. null oder ProgressEvent) — dann liefern wir einen brauchbaren Fallback statt
   * den Aufrufer mit einem kaputten Objekt crashen zu lassen.
   */
  deploy(id: number): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(`${this.baseUrl}/${id}/deploy`, {}).pipe(
      catchError(err => of(
        (err?.error && Array.isArray(err.error.errors))
          ? err.error as ValidationResult
          : { errors: ['Netzwerkfehler beim Deploy.'], warnings: [] }
      ))
    );
  }

  setEnabled(id: number, enabled: boolean): Observable<FlowSummary> {
    return this.http.post<FlowSummary>(`${this.baseUrl}/${id}/${enabled ? 'enable' : 'disable'}`, {});
  }

  deleteFlow(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  inject(id: number, nodeId: string, payload: Record<string, unknown>): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/nodes/${nodeId}/inject`, { payload });
  }

  getDebug(id: number, nodeId: string): Observable<DebugEntry[]> {
    return this.http.get<DebugEntry[]>(`${this.baseUrl}/${id}/nodes/${nodeId}/debug`);
  }
}
