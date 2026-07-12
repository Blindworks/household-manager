import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AlexaAirQualityReading } from '../models/alexa-air-quality.model';

/**
 * Service fuer die Amazon Smart Air Quality Monitore (via Alexa-Sidecar).
 */
@Injectable({
  providedIn: 'root'
})
export class AlexaAirQualityService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/alexa/air-quality';

  getLatest(): Observable<AlexaAirQualityReading[]> {
    return this.http.get<AlexaAirQualityReading[]>(`${this.baseUrl}/latest`).pipe(
      map(readings => readings.map(reading => this.convertDate(reading))),
      catchError(this.handleError)
    );
  }

  getReadings(): Observable<AlexaAirQualityReading[]> {
    return this.http.get<AlexaAirQualityReading[]>(`${this.baseUrl}/readings`).pipe(
      map(readings => readings.map(reading => this.convertDate(reading))),
      catchError(this.handleError)
    );
  }

  private convertDate(reading: AlexaAirQualityReading): AlexaAirQualityReading {
    return { ...reading, readingTime: new Date(reading.readingTime) };
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Alexa-Air-Quality-API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Amazon-Luftsensor-Daten.'));
  }
}
