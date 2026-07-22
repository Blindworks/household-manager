import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PowerConsumer } from '../models/power-consumer.model';

/**
 * REST-Service für die Verbraucher-Kachel (Stromverbraucher, größter zuerst).
 */
@Injectable({ providedIn: 'root' })
export class PowerConsumerService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/power-consumers';

  /** Stromverbraucher, absteigend nach Leistung sortiert. */
  getConsumers(limit?: number): Observable<PowerConsumer[]> {
    let params = new HttpParams();
    if (limit != null) {
      params = params.set('limit', limit);
    }
    return this.http.get<PowerConsumer[]>(this.baseUrl, { params }).pipe(
      catchError(this.handleError)
    );
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Verbraucher-API-Fehler:', error);
    return throwError(() => new Error('Fehler bei der Verbraucher-Anfrage.'));
  }
}
