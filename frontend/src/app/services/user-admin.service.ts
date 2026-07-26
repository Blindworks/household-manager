import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AppUser, UserRole } from '../models/auth.model';

export interface CreateUserRequest {
  username: string;
  displayName: string;
  password: string;
  role: UserRole;
}

export interface UpdateUserRequest {
  displayName: string;
  role: UserRole;
  enabled: boolean;
}

/** Admin-API der Nutzerverwaltung. */
@Injectable({ providedIn: 'root' })
export class UserAdminService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/admin/users';

  getUsers(): Observable<AppUser[]> {
    return this.http.get<AppUser[]>(this.baseUrl).pipe(catchError(this.handleError));
  }

  createUser(request: CreateUserRequest): Observable<AppUser> {
    return this.http.post<AppUser>(this.baseUrl, request).pipe(catchError(this.handleError));
  }

  updateUser(id: number, request: UpdateUserRequest): Observable<AppUser> {
    return this.http.put<AppUser>(`${this.baseUrl}/${id}`, request).pipe(catchError(this.handleError));
  }

  setPassword(id: number, password: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/${id}/password`, { password })
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Nutzerverwaltungs-API-Fehler:', error);
    const message = error.error?.message ?? 'Fehler bei der Nutzerverwaltung.';
    return throwError(() => new Error(message));
  }
}
