import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ModeQuickAccess, ModeQuickAccessRequest } from '../models/mode-quick-access.model';
import { ModeEntity } from '../models/mode.model';

/**
 * REST-Service für die Zeitfenster der Schnellzugriffe. Die Pflege (list/create/update/remove)
 * ist ADMIN-only; `due()` ist KIOSK-lesbar und versorgt das Tablet-Dashboard.
 */
@Injectable({ providedIn: 'root' })
export class ModeQuickAccessService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/mode-quick-access';

  /** Die gerade fälligen Helfer (Modi und gewöhnliche Helfer) mit Name, Icon und Zustand. */
  due(): Observable<ModeEntity[]> {
    return this.http.get<ModeEntity[]>(`${this.baseUrl}/due`);
  }

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
