import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ModeQuickAccess, ModeQuickAccessRequest } from '../models/mode-quick-access.model';

/** REST-Service für die Zeitfenster der Modus-Schnellzugriffe (ADMIN-only). */
@Injectable({ providedIn: 'root' })
export class ModeQuickAccessService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/mode-quick-access';

  list(): Observable<ModeQuickAccess[]> {
    return this.http.get<ModeQuickAccess[]>(this.baseUrl);
  }

  create(request: ModeQuickAccessRequest): Observable<ModeQuickAccess> {
    return this.http.post<ModeQuickAccess>(this.baseUrl, request);
  }

  update(id: number, request: ModeQuickAccessRequest): Observable<ModeQuickAccess> {
    return this.http.put<ModeQuickAccess>(`${this.baseUrl}/${id}`, request);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
