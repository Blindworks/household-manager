import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { UtilityPrice, UtilityPriceRequest } from '../models/utility-price.model';
import { MeterType } from '../models/meter-reading.model';

/**
 * Service für Versorgerpreise
 * Verwaltet alle API-Calls für Utility Prices
 */
@Injectable({
  providedIn: 'root'
})
export class UtilityPriceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = 'http://localhost:8080/api/v1/utility-prices';

  /**
   * Lädt alle Preise
   */
  getAllPrices(): Observable<UtilityPrice[]> {
    return this.http.get<UtilityPrice[]>(this.baseUrl).pipe(
      map(prices => this.convertDates(prices)),
      catchError(this.handleError)
    );
  }

  /**
   * Lädt Preise für einen bestimmten Zählertyp
   */
  getPricesByMeterType(meterType: MeterType): Observable<UtilityPrice[]> {
    return this.http.get<UtilityPrice[]>(`${this.baseUrl}/meter-type/${meterType}`).pipe(
      map(prices => this.convertDates(prices)),
      catchError(this.handleError)
    );
  }

  /**
   * Lädt den aktuell gültigen Preis für einen Zählertyp
   */
  getCurrentPrice(meterType: MeterType): Observable<UtilityPrice | null> {
    return this.http.get<UtilityPrice>(`${this.baseUrl}/meter-type/${meterType}/current`).pipe(
      map(price => price ? this.convertDate(price) : null),
      catchError(error => {
        // 404 ist OK - bedeutet kein aktueller Preis vorhanden
        if (error.status === 404) {
          return [null];
        }
        return this.handleError(error);
      })
    );
  }

  /**
   * Erstellt einen neuen Preis
   */
  createPrice(request: UtilityPriceRequest): Observable<UtilityPrice> {
    return this.http.post<UtilityPrice>(this.baseUrl, request).pipe(
      map(price => this.convertDate(price)),
      catchError(this.handleError)
    );
  }

  /**
   * Löscht einen Preis
   */
  deletePrice(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(
      catchError(this.handleError)
    );
  }

  /**
   * Konvertiert ISO-Date-Strings zu Date-Objekten für ein Array
   */
  private convertDates(prices: UtilityPrice[]): UtilityPrice[] {
    return prices.map(price => this.convertDate(price));
  }

  /**
   * Konvertiert ISO-Date-Strings zu Date-Objekten für einen einzelnen Preis
   */
  private convertDate(price: UtilityPrice): UtilityPrice {
    return {
      ...price,
      validFrom: new Date(price.validFrom),
      validTo: price.validTo ? new Date(price.validTo) : undefined,
      createdAt: price.createdAt ? new Date(price.createdAt) : undefined,
      updatedAt: price.updatedAt ? new Date(price.updatedAt) : undefined
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
        case 409:
          errorMessage = 'Ein Preis für diesen Zeitraum existiert bereits.';
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
