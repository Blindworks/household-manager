import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { CurrentUser, LoginRequest } from '../models/auth.model';

/** Session-Login und aktueller Nutzer (Cookie-basiert, Spring Security). */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/auth';

  /** undefined = noch nicht geladen, null = nicht angemeldet */
  private readonly user = signal<CurrentUser | null | undefined>(undefined);

  readonly currentUser = computed(() => this.user() ?? null);
  readonly isAdmin = computed(() => this.user()?.role === 'ADMIN');
  readonly isMember = computed(() => this.user()?.role === 'ADMIN' || this.user()?.role === 'MEMBER');

  /** Laedt den Nutzer genau einmal; 401 wird zu null (nicht angemeldet). */
  ensureLoaded(): Observable<CurrentUser | null> {
    const known = this.user();
    if (known !== undefined) {
      return of(known);
    }
    return this.http.get<CurrentUser>(`${this.baseUrl}/me`).pipe(
      tap(user => this.user.set(user)),
      map((user): CurrentUser | null => user),
      catchError(() => {
        this.user.set(null);
        return of(null);
      })
    );
  }

  login(request: LoginRequest): Observable<CurrentUser> {
    return this.http.post<CurrentUser>(`${this.baseUrl}/login`, request).pipe(
      tap(user => this.user.set(user)),
      catchError((error: HttpErrorResponse) => throwError(() => new Error(
        error.status === 401 ? 'Benutzername oder Passwort falsch.' : 'Anmeldung fehlgeschlagen.')))
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, {}).pipe(
      tap(() => this.user.set(null))
    );
  }

  /** Vom Interceptor nach einem 401 aufgerufen. */
  clearUser(): void {
    this.user.set(null);
  }
}
