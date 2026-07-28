import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TractiveAuthStatus, TractivePet, TractiveWalk } from '../models/tractive.model';
import { TractiveHomeSettings } from '../models/tractive-home-settings.model';

/** REST-Service fuer die Tractive-Haustiertracker. */
@Injectable({ providedIn: 'root' })
export class TractiveService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/tractive';

  getStatus(): Observable<TractiveAuthStatus> {
    return this.http.get<TractiveAuthStatus>(`${this.baseUrl}/status`).pipe(
      catchError(this.handleError)
    );
  }

  login(email: string, password: string): Observable<TractiveAuthStatus> {
    return this.http.post<TractiveAuthStatus>(`${this.baseUrl}/login`, { email, password });
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, {}).pipe(
      catchError(this.handleError)
    );
  }

  getPets(): Observable<TractivePet[]> {
    return this.http.get<TractivePet[]>(`${this.baseUrl}/pets`).pipe(
      catchError(this.handleError)
    );
  }

  /**
   * Fehler werden bewusst NICHT auf eine Einheitsmeldung reduziert: der Dialog
   * zeigt die Server-Meldung an (z. B. "Kein Zuhause konfiguriert").
   */
  getWalks(trackerId: string, days = 7): Observable<TractiveWalk[]> {
    return this.http.get<TractiveWalk[]>(
      `${this.baseUrl}/pets/${trackerId}/walks`, { params: { days } });
  }

  getHomeSettings(): Observable<TractiveHomeSettings> {
    return this.http.get<TractiveHomeSettings>(`${this.baseUrl}/home-settings`).pipe(
      catchError(this.handleError)
    );
  }

  /** Fehler werden bewusst NICHT geschluckt: die Seite zeigt die 400-Meldung des Servers an. */
  saveHomeSettings(settings: TractiveHomeSettings): Observable<TractiveHomeSettings> {
    return this.http.put<TractiveHomeSettings>(`${this.baseUrl}/home-settings`, settings);
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Tractive-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Tractive-Anfrage.'));
  }
}
