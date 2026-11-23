import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { CurrentUser, LoginRequest } from '../models/auth.model';
import { ViewModeService } from './view-mode.service';

/** Session-Login und aktueller Nutzer (Cookie-basiert, Spring Security). */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly viewMode = inject(ViewModeService);
  private readonly baseUrl = '/api/v1/auth';

  /** undefined = noch nicht geladen, null = nicht angemeldet */
  private readonly user = signal<CurrentUser | null | undefined>(undefined);

  readonly currentUser = computed(() => this.user() ?? null);
  readonly isAdmin = computed(() => this.user()?.role === 'ADMIN');
  readonly isMember = computed(() => this.user()?.role === 'ADMIN' || this.user()?.role === 'MEMBER');
  /** Konto des Wandtablets (Merkmal am Benutzer, keine Rolle). */
  readonly isWallTablet = computed(() => this.user()?.wallTablet === true);

  /** Laedt den Nutzer genau einmal; 401 wird zu null (nicht angemeldet). */
  ensureLoaded(): Observable<CurrentUser | null> {
    const known = this.user();
    return known !== undefined ? of(known) : this.fetchCurrentUser();
  }

  /**
   * Fragt den Nutzer immer beim Server ab — auch wenn er schon bekannt ist.
   * Die Login-Seite braucht das: Sie laeuft als einzige Route ohne Guard,
   * ohne diesen GET setzt Spring nie das XSRF-TOKEN-Cookie, Angular sendet
   * keinen X-XSRF-TOKEN-Header und der Login-POST scheitert mit 403.
   */
  primeCsrfToken(): Observable<CurrentUser | null> {
    return this.fetchCurrentUser();
  }

  private fetchCurrentUser(): Observable<CurrentUser | null> {
    return this.http.get<CurrentUser>(`${this.baseUrl}/me`).pipe(
      tap(user => this.setUser(user)),
      map((user): CurrentUser | null => user),
      catchError(() => {
        this.setUser(null);
        return of(null);
      })
    );
  }

  login(request: LoginRequest): Observable<CurrentUser> {
    return this.http.post<CurrentUser>(`${this.baseUrl}/login`, request).pipe(
      tap(user => this.setUser(user)),
      catchError((error: HttpErrorResponse) => throwError(() => new Error(
        error.status === 401 ? 'Benutzername oder Passwort falsch.' : 'Anmeldung fehlgeschlagen.')))
    );
  }

  /** Selbst-Passwortwechsel; das Backend liefert den aktualisierten Nutzer (Flag geloescht). */
  changePassword(currentPassword: string, newPassword: string): Observable<CurrentUser> {
    return this.http.post<CurrentUser>(`${this.baseUrl}/password`, { currentPassword, newPassword }).pipe(
      tap(user => this.setUser(user)),
      catchError((error: HttpErrorResponse) => throwError(() => new Error(
        error.error?.message ?? 'Passwortwechsel fehlgeschlagen.')))
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/logout`, {}).pipe(
      tap(() => this.setUser(null))
    );
  }

  private setUser(user: CurrentUser | null): void {
    this.user.set(user);
    this.viewMode.setWallTabletAccount(user?.wallTablet === true);
  }

  /** Vom Interceptor nach einem 401 aufgerufen. */
  clearUser(): void {
    this.setUser(null);
  }
}
