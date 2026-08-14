import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

/** REST-Service für System-Aktionen (Reboot-Button im Dashboard). */
@Injectable({ providedIn: 'root' })
export class SystemService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/system';

  /** Startet alle Container des Systems neu (Backend antwortet vor dem Neustart). */
  reboot(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/reboot`, {}).pipe(catchError(this.handleError));
  }

  /**
   * Leichter Erreichbarkeits-Check fürs Reload-Polling nach dem Reboot.
   * Fehler werden bewusst nicht übersetzt — der Aufrufer wertet nur Erfolg aus.
   */
  ping(): Observable<unknown> {
    return this.http.get('/api/v1/health');
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('System-API-Fehler:', error);
    const message =
      error.error?.message ?? 'Der Neustart konnte nicht ausgelöst werden.';
    return throwError(() => new Error(message));
  }
}
