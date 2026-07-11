import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AirQualityOverview } from '../models/air-quality.model';

/** Service für den UBA-Luftqualitätsindex. */
@Injectable({
  providedIn: 'root'
})
export class AirQualityService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/air-quality';

  getOverview(): Observable<AirQualityOverview> {
    return this.http.get<AirQualityOverview>(`${this.baseUrl}/overview`).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Luftqualitäts-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Luftqualität.'));
  }
}
