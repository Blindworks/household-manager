import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AirQualitySensorSeries } from '../models/air-quality-series.model';
import { TimeRange } from '../models/temperature.model';

/** REST-Service fuer die aggregierten Luftqualitaets-Zeitreihen. */
@Injectable({ providedIn: 'root' })
export class AirQualitySeriesService {
  private readonly http = inject(HttpClient);
  private readonly url = '/api/v1/air-quality/series';

  getSeries(range: TimeRange): Observable<AirQualitySensorSeries[]> {
    const params = new HttpParams().set('range', range);
    return this.http.get<AirQualitySensorSeries[]>(this.url, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Luftqualitaets-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Luftqualitätsdaten.'));
  }
}
