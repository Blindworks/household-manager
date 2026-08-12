import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { VentilationAssessment } from '../models/ventilation.model';

/** Service fuer serverseitig berechnete Hub-Hinweise. */
@Injectable({ providedIn: 'root' })
export class InsightService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/insights';

  getVentilation(): Observable<VentilationAssessment> {
    return this.http.get<VentilationAssessment>(`${this.baseUrl}/ventilation`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Insight-API-Fehler:', error);
    return throwError(() => new Error(error.error?.message || 'Fehler beim Laden der Hub-Hinweise.'));
  }
}
