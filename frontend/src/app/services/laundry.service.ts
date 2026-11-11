import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { LaundryState } from '../models/laundry.model';

/** REST-Service fuer die Waeschetrocknung (Hub-Karte "Waesche trocknet"). */
@Injectable({ providedIn: 'root' })
export class LaundryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/laundry';

  getState(): Observable<LaundryState> {
    return this.http.get<LaundryState>(`${this.baseUrl}/state`);
  }

  answerHung(yes: boolean): Observable<LaundryState> {
    return this.http.post<LaundryState>(`${this.baseUrl}/answers/hung`, { yes });
  }

  answerDry(yes: boolean): Observable<LaundryState> {
    return this.http.post<LaundryState>(`${this.baseUrl}/answers/dry`, { yes });
  }

  markRemoved(): Observable<LaundryState> {
    return this.http.post<LaundryState>(`${this.baseUrl}/removed`, null);
  }
}
