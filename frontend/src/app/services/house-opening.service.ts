import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { HouseOpening, HouseOpeningRequest } from '../models/house-opening.model';

/** Fenster und Tueren des Digitalen Zwillings. Lesen KIOSK, Schreiben ADMIN. */
@Injectable({ providedIn: 'root' })
export class HouseOpeningService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/house/openings';

  list(): Observable<HouseOpening[]> {
    return this.http.get<HouseOpening[]>(this.baseUrl);
  }

  create(request: HouseOpeningRequest): Observable<HouseOpening> {
    return this.http.post<HouseOpening>(this.baseUrl, request);
  }

  update(id: number, request: HouseOpeningRequest): Observable<HouseOpening> {
    return this.http.put<HouseOpening>(`${this.baseUrl}/${id}`, request);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
