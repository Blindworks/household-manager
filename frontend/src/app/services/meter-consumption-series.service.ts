import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ConsumptionRange, MeterConsumptionSeries } from '../models/meter-consumption-series.model';

/** REST-Service fuer die aggregierten Verbrauchsreihen der Tablet-Ansicht. */
@Injectable({ providedIn: 'root' })
export class MeterConsumptionSeriesService {
  private readonly http = inject(HttpClient);
  private readonly url = '/api/v1/meter-readings/series';

  getSeries(range: ConsumptionRange): Observable<MeterConsumptionSeries[]> {
    const params = new HttpParams().set('range', range);
    return this.http.get<MeterConsumptionSeries[]>(this.url, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Verbrauchs-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Verbrauchsdaten.'));
  }
}
