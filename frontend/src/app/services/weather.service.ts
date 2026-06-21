import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { WeatherHistoryReading, WeatherOverview } from '../models/weather.model';

/** Service für DWD-Wetterdaten. */
@Injectable({
  providedIn: 'root'
})
export class WeatherService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/weather';

  getOverview(): Observable<WeatherOverview> {
    return this.http.get<WeatherOverview>(`${this.baseUrl}/overview`).pipe(
      catchError(this.handleError)
    );
  }

  getHistory(): Observable<WeatherHistoryReading[]> {
    return this.http.get<WeatherHistoryReading[]>(`${this.baseUrl}/history`).pipe(
      map(readings => readings.map(reading => ({
        ...reading,
        readingTime: new Date(reading.readingTime)
      }))),
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Wetter-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Wetterdaten.'));
  }
}
