import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TractiveAuthStatus, TractivePet } from '../models/tractive.model';

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

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Tractive-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Tractive-Anfrage.'));
  }
}
