import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { NukiLock, NukiLockActionType } from '../models/nuki.model';

/** REST-Service für die Nuki-Schlösser (Türschloss-Kachel). */
@Injectable({ providedIn: 'root' })
export class NukiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/nuki';

  getLocks(): Observable<NukiLock[]> {
    return this.http.get<NukiLock[]>(`${this.baseUrl}/locks`).pipe(
      catchError(this.handleError)
    );
  }

  sendAction(smartlockId: number, action: NukiLockActionType): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/locks/${smartlockId}/actions`, { action }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Nuki-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Nuki-Anfrage.'));
  }
}
