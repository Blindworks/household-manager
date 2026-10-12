import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ActionClass,
  ActionPolicy,
  AgentNote,
  AgentNoteRequest,
  AgentPolicy,
  AgentProposal,
  AgentRun,
  AgentRunsResponse,
  AgentSettings,
  AgentSummary,
  ProposalDecision
} from '../models/agent.model';

/** REST-Service des Hausverstands (Admin-Seite). */
@Injectable({ providedIn: 'root' })
export class AgentService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/agent';

  getSummary(): Observable<AgentSummary> {
    return this.http.get<AgentSummary>(`${this.baseUrl}/summary`);
  }

  getSettings(): Observable<AgentSettings> {
    return this.http.get<AgentSettings>(`${this.baseUrl}/settings`);
  }

  updateSettings(active: boolean): Observable<AgentSettings> {
    return this.http.put<AgentSettings>(`${this.baseUrl}/settings`, { active });
  }

  getPolicies(): Observable<AgentPolicy[]> {
    return this.http.get<AgentPolicy[]>(`${this.baseUrl}/policies`);
  }

  /** Liefert die komplette Liste aller Freigaben zurück. */
  updatePolicy(actionClass: ActionClass, policy: ActionPolicy): Observable<AgentPolicy[]> {
    return this.http.put<AgentPolicy[]>(`${this.baseUrl}/policies/${actionClass}`, { policy });
  }

  getNotes(): Observable<AgentNote[]> {
    return this.http.get<AgentNote[]>(`${this.baseUrl}/notes`);
  }

  createNote(request: AgentNoteRequest): Observable<AgentNote> {
    return this.http.post<AgentNote>(`${this.baseUrl}/notes`, request);
  }

  updateNote(id: number, request: AgentNoteRequest): Observable<AgentNote> {
    return this.http.put<AgentNote>(`${this.baseUrl}/notes/${id}`, request);
  }

  deleteNote(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/notes/${id}`);
  }

  getProposals(limit: number): Observable<AgentProposal[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<AgentProposal[]>(`${this.baseUrl}/proposals`, { params });
  }

  decide(id: number, decision: ProposalDecision): Observable<AgentProposal> {
    return this.http.post<AgentProposal>(`${this.baseUrl}/proposals/${id}/decide`, { decision });
  }

  getRuns(): Observable<AgentRunsResponse> {
    return this.http.get<AgentRunsResponse>(`${this.baseUrl}/runs`);
  }

  runNow(): Observable<AgentRun> {
    return this.http.post<AgentRun>(`${this.baseUrl}/runs`, {});
  }
}
