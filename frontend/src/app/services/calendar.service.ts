import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  CalendarEvent, CalendarEventRequest, CalendarOccurrence
} from '../models/calendar-event.model';

/** Service fuer die Haushaltskalender-API. */
@Injectable({ providedIn: 'root' })
export class CalendarService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/calendar';

  /** Expandierte Vorkommen im Zeitraum [from, to] (ISO-Daten). */
  getOccurrences(from: string, to: string): Observable<CalendarOccurrence[]> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<CalendarOccurrence[]>(`${this.baseUrl}/events`, { params })
      .pipe(catchError(this.handleError));
  }

  getUpcoming(limit = 3): Observable<CalendarOccurrence[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<CalendarOccurrence[]>(`${this.baseUrl}/upcoming`, { params })
      .pipe(catchError(this.handleError));
  }

  getEvent(id: number): Observable<CalendarEvent> {
    return this.http.get<CalendarEvent>(`${this.baseUrl}/events/${id}`)
      .pipe(catchError(this.handleError));
  }

  create(request: CalendarEventRequest): Observable<CalendarEvent> {
    return this.http.post<CalendarEvent>(`${this.baseUrl}/events`, request)
      .pipe(catchError(this.handleError));
  }

  update(id: number, request: CalendarEventRequest): Observable<CalendarEvent> {
    return this.http.put<CalendarEvent>(`${this.baseUrl}/events/${id}`, request)
      .pipe(catchError(this.handleError));
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/events/${id}`)
      .pipe(catchError(this.handleError));
  }

  /** Nur dieses Vorkommen loeschen (EXDATE). */
  deleteOccurrence(eventId: number, recurrenceDate: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/events/${eventId}/occurrences/${recurrenceDate}`)
      .pipe(catchError(this.handleError));
  }

  /** Nur dieses Vorkommen aendern (Override). */
  updateOccurrence(eventId: number, recurrenceDate: string,
                   request: CalendarEventRequest): Observable<CalendarOccurrence> {
    return this.http.put<CalendarOccurrence>(
      `${this.baseUrl}/events/${eventId}/occurrences/${recurrenceDate}`, request)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Kalender-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Kalender-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
