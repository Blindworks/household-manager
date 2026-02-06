import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import {
  MeterReading,
  MeterReadingRequest,
  MeterType,
  ConsumptionResponse
} from '../models/meter-reading.model';

/**
 * Service für Zählerablesungen
 * Verwaltet alle API-Calls für Meter Readings
 */
@Injectable({
  providedIn: 'root'
})
export class MeterReadingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = 'http://localhost:8080/api/v1/meter-readings';

  /**
   * Lädt alle Ablesungen
   */
  getAllReadings(): Observable<MeterReading[]> {
    return this.http.get<MeterReading[]>(this.baseUrl).pipe(
      map(readings => this.convertDates(readings)),
      catchError(this.handleError)
    );
  }

  /**
   * Lädt Ablesungen für einen bestimmten Zählertyp
   */
  getReadingsByType(type: MeterType): Observable<MeterReading[]> {
    return this.http.get<MeterReading[]>(`${this.baseUrl}/${type}`).pipe(
      map(readings => this.convertDates(readings)),
      catchError(this.handleError)
    );
  }

  /**
   * Lädt die neueste Ablesung für einen Zählertyp
   */
  getLatestReading(type: MeterType): Observable<MeterReading | null> {
    return this.http.get<MeterReading>(`${this.baseUrl}/${type}/latest`).pipe(
      map(reading => reading ? this.convertDate(reading) : null),
      catchError(error => {
        // 404 ist OK - bedeutet keine Ablesung vorhanden
        if (error.status === 404) {
          return [null];
        }
        return this.handleError(error);
      })
    );
  }

  /**
   * Lädt Verbrauchsstatistiken für einen Zählertyp
   */
  getConsumptionStats(type: MeterType): Observable<ConsumptionResponse | null> {
    return this.http.get<ConsumptionResponse>(`${this.baseUrl}/${type}/consumption`).pipe(
      catchError(error => {
        // 404 ist OK - bedeutet nicht genug Ablesungen vorhanden
        if (error.status === 404) {
          return [null];
        }
        return this.handleError(error);
      })
    );
  }

  /**
   * Importiert ZÃ¤hlerstÃ¤nde aus einer CSV-Datei
   */
  importCsv(file: File): Observable<{ createdCount: number }> {
    const formData = new FormData();
    formData.append('file', file, file.name);

    return this.http.post<{ createdCount: number }>(`${this.baseUrl}/import`, formData).pipe(
      catchError(this.handleError)
    );
  }

  /**
   * Erstellt eine neue Ablesung
   */
  createReading(request: MeterReadingRequest): Observable<MeterReading> {
    return this.http.post<MeterReading>(this.baseUrl, request).pipe(
      map(reading => this.convertDate(reading)),
      catchError(this.handleError)
    );
  }

  /**
   * Konvertiert ISO-Date-Strings zu Date-Objekten für ein Array
   */
  private convertDates(readings: MeterReading[]): MeterReading[] {
    return readings.map(reading => this.convertDate(reading));
  }

  /**
   * Konvertiert ISO-Date-Strings zu Date-Objekten für ein einzelnes Reading
   */
  private convertDate(reading: MeterReading): MeterReading {
    return {
      ...reading,
      readingDate: new Date(reading.readingDate),
      createdAt: reading.createdAt ? new Date(reading.createdAt) : undefined,
      updatedAt: reading.updatedAt ? new Date(reading.updatedAt) : undefined
    };
  }

  /**
   * Zentrale Fehlerbehandlung
   */
  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'Ein unbekannter Fehler ist aufgetreten';

    if (error.error instanceof ErrorEvent) {
      // Client-seitiger Fehler
      errorMessage = `Fehler: ${error.error.message}`;
    } else {
      // Server-seitiger Fehler
      switch (error.status) {
        case 400:
          errorMessage = 'Ungültige Daten. Bitte überprüfen Sie Ihre Eingaben.';
          break;
        case 404:
          errorMessage = 'Die angeforderten Daten wurden nicht gefunden.';
          break;
        case 500:
          errorMessage = 'Ein Serverfehler ist aufgetreten. Bitte versuchen Sie es später erneut.';
          break;
        default:
          errorMessage = `Server-Fehler: ${error.status}`;
      }
    }

    console.error('API-Fehler:', error);
    return throwError(() => new Error(errorMessage));
  }
}
