import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CreatedServiceToken, ServiceTokenInfo, UserRole } from '../models/auth.model';

/** Admin-API der Service-Tokens (Maschinen-Clients). */
@Injectable({ providedIn: 'root' })
export class ServiceTokenAdminService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/service-tokens';

  getTokens(): Observable<ServiceTokenInfo[]> {
    return this.http.get<ServiceTokenInfo[]>(this.baseUrl).pipe(catchError(this.handleError));
  }

  createToken(name: string, role: UserRole): Observable<CreatedServiceToken> {
    return this.http.post<CreatedServiceToken>(this.baseUrl, { name, role })
      .pipe(catchError(this.handleError));
  }

  revokeToken(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Token-API-Fehler:', error);
    const message = error.error?.message ?? 'Fehler bei der Token-Verwaltung.';
    return throwError(() => new Error(message));
  }
}
