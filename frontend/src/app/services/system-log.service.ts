import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  SystemLogEntry, SystemLogFlowRef, SystemLogJob, SystemLogPage, SystemLogQuery
} from '../models/system-log.model';

/** Admin-API des Systemprotokolls. */
@Injectable({ providedIn: 'root' })
export class SystemLogService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/system-log';

  getEntries(query: SystemLogQuery): Observable<SystemLogPage> {
    let params = new HttpParams();
    if (query.from) { params = params.set('from', query.from); }
    if (query.to) { params = params.set('to', query.to); }
    (query.kinds ?? []).forEach(kind => (params = params.append('kind', kind)));
    if (query.status) { params = params.set('status', query.status); }
    if (query.flowId !== undefined) { params = params.set('flowId', query.flowId); }
    if (query.q) { params = params.set('q', query.q); }
    if (query.problemsOnly) { params = params.set('problemsOnly', true); }
    if (query.page !== undefined) { params = params.set('page', query.page); }
    if (query.size !== undefined) { params = params.set('size', query.size); }
    return this.http.get<SystemLogPage>(`${this.baseUrl}/entries`, { params });
  }

  getRun(runId: string): Observable<SystemLogEntry[]> {
    return this.http.get<SystemLogEntry[]>(`${this.baseUrl}/runs/${encodeURIComponent(runId)}`);
  }

  getJobs(): Observable<SystemLogJob[]> {
    return this.http.get<SystemLogJob[]>(`${this.baseUrl}/jobs`);
  }

  getFlows(from?: string, to?: string): Observable<SystemLogFlowRef[]> {
    let params = new HttpParams();
    if (from) { params = params.set('from', from); }
    if (to) { params = params.set('to', to); }
    return this.http.get<SystemLogFlowRef[]>(`${this.baseUrl}/flows`, { params });
  }
}
