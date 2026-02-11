import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AirrohrHistoryReading, AirrohrReading } from '../models/airrohr.model';

/**
 * Service for Airrohr fine dust sensor.
 */
@Injectable({
  providedIn: 'root'
})
export class AirrohrService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/airrohr';
  private readonly readingsUrl = '/api/v1/airrohr-readings';

  getCurrentReading(): Observable<AirrohrReading> {
    return this.http.get<AirrohrReading>(`${this.baseUrl}/current`).pipe(
      catchError(this.handleError)
    );
  }

  getReadings(): Observable<AirrohrHistoryReading[]> {
    return this.http.get<AirrohrHistoryReading[]>(this.readingsUrl).pipe(
      map(readings => readings.map(reading => this.convertDate(reading))),
      catchError(this.handleError)
    );
  }

  private convertDate(reading: AirrohrHistoryReading): AirrohrHistoryReading {
    return {
      ...reading,
      readingTime: new Date(reading.readingTime),
      createdAt: reading.createdAt ? new Date(reading.createdAt) : undefined,
      updatedAt: reading.updatedAt ? new Date(reading.updatedAt) : undefined
    };
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Airrohr API-Fehler:', error);
    return throwError(() => new Error('Fehler beim Laden der Airrohr-Daten.'));
  }
}
