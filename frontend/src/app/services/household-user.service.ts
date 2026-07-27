import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { HouseholdUser } from '../models/household-user.model';

/** Schlanke Nutzerliste fuer die Personenauswahl (nicht die Admin-Nutzerverwaltung). */
@Injectable({ providedIn: 'root' })
export class HouseholdUserService {
  private readonly http = inject(HttpClient);

  list(): Observable<HouseholdUser[]> {
    return this.http.get<HouseholdUser[]>('/api/v1/users')
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Haushaltsmitglieder-API-Fehler:', error);
    const message = error.error?.message || 'Die Haushaltsmitglieder konnten nicht geladen werden.';
    return throwError(() => new Error(message));
  }
}
