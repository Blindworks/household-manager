import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { TemperatureSensorSeries, TimeRange } from '../models/temperature.model';

/**
 * REST-Service für aggregierte Temperatur-/Feuchte-Zeitreihen.
 */
@Injectable({ providedIn: 'root' })
export class TemperatureService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/temperatures';

  getSeries(range: TimeRange): Observable<TemperatureSensorSeries[]> {
    const params = new HttpParams().set('range', range);
    return this.http.get<TemperatureSensorSeries[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Temperatur-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Temperaturdaten.'));
  }
}
