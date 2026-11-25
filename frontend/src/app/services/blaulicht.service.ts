import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BlaulichtBriefing, BlaulichtCategory, BlaulichtEvent } from '../models/blaulicht.model';

/** Lesender Zugriff auf die Blaulicht-Meldungen (auch für das Wandtablet). */
@Injectable({ providedIn: 'root' })
export class BlaulichtService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/blaulicht';

  getBriefing(): Observable<BlaulichtBriefing> {
    return this.http.get<BlaulichtBriefing>(`${this.baseUrl}/briefing`);
  }

  /** Meldungen seit `since` (lokale Zeit, Standard: 7 Tage), jüngste Veröffentlichung zuerst. */
  getEvents(since?: string, category?: BlaulichtCategory): Observable<BlaulichtEvent[]> {
    let params = new HttpParams();
    if (since) {
      params = params.set('since', since);
    }
    if (category) {
      params = params.set('category', category);
    }
    return this.http.get<BlaulichtEvent[]>(this.baseUrl, { params });
  }
}
