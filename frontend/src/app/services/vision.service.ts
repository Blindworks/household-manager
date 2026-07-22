import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { VisionPerson, VisionPhoto, VisionRecognition, VisionStatus } from '../models/vision.model';

/** REST-Service der Gesichtserkennung (Personen, Fotos, Erkennungen, Blink-Login). */
@Injectable({ providedIn: 'root' })
export class VisionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/vision';

  getStatus(): Observable<VisionStatus> {
    return this.http.get<VisionStatus>(`${this.baseUrl}/status`).pipe(catchError(this.handleError));
  }

  login(username: string, password: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/login`, { username, password })
      .pipe(catchError(this.handleError));
  }

  verify(code: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/verify`, { code })
      .pipe(catchError(this.handleError));
  }

  getPersons(): Observable<VisionPerson[]> {
    return this.http.get<VisionPerson[]>(`${this.baseUrl}/persons`).pipe(catchError(this.handleError));
  }

  createPerson(name: string): Observable<VisionPerson> {
    return this.http.post<VisionPerson>(`${this.baseUrl}/persons`, { name })
      .pipe(catchError(this.handleError));
  }

  updatePerson(id: number, name: string, active: boolean): Observable<VisionPerson> {
    return this.http.put<VisionPerson>(`${this.baseUrl}/persons/${id}`, { name, active })
      .pipe(catchError(this.handleError));
  }

  deletePerson(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/persons/${id}`).pipe(catchError(this.handleError));
  }

  getPhotos(personId: number): Observable<VisionPhoto[]> {
    return this.http.get<VisionPhoto[]>(`${this.baseUrl}/persons/${personId}/photos`)
      .pipe(catchError(this.handleError));
  }

  uploadPhoto(personId: number, file: File): Observable<VisionPhoto> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<VisionPhoto>(`${this.baseUrl}/persons/${personId}/photos`, form)
      .pipe(catchError(this.handleError));
  }

  deletePhoto(personId: number, photoId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/persons/${personId}/photos/${photoId}`)
      .pipe(catchError(this.handleError));
  }

  getRecognitions(limit = 50): Observable<VisionRecognition[]> {
    return this.http.get<VisionRecognition[]>(`${this.baseUrl}/recognitions?limit=${limit}`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Vision-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Gesichtserkennungs-Anfrage.';
    return throwError(() => new Error(message));
  }
}
