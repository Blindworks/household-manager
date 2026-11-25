import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  CommunicatorHealth, CommunicatorLogSummary, CommunicatorRequestDetail, CommunicatorRequestPage
} from '../models/communicator-log.model';

export interface CommunicatorLogQuery {
  days: number;
  problemsOnly: boolean;
  q: string;
}

/** Admin-API des Communicator-Protokolls. */
@Injectable({ providedIn: 'root' })
export class CommunicatorLogService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/communicator';

  getSummary(days: number): Observable<CommunicatorLogSummary> {
    return this.http.get<CommunicatorLogSummary>(`${this.baseUrl}/summary`, { params: { days } });
  }

  getRequests(query: CommunicatorLogQuery, page: number): Observable<CommunicatorRequestPage> {
    let params = new HttpParams().set('days', query.days).set('problemsOnly', query.problemsOnly).set('page', page);
    if (query.q) {
      params = params.set('q', query.q);
    }
    return this.http.get<CommunicatorRequestPage>(`${this.baseUrl}/requests`, { params });
  }

  getRequest(id: number): Observable<CommunicatorRequestDetail> {
    return this.http.get<CommunicatorRequestDetail>(`${this.baseUrl}/requests/${id}`);
  }

  getHealth(): Observable<CommunicatorHealth> {
    return this.http.get<CommunicatorHealth>(`${this.baseUrl}/health`);
  }
}
