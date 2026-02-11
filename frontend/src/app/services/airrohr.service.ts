import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AirrohrReading } from '../models/airrohr.model';

/**
 * Service for Airrohr fine dust sensor.
 */
@Injectable({
  providedIn: 'root'
})
export class AirrohrService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/airrohr';

  getCurrentReading(): Observable<AirrohrReading> {
    return this.http.get<AirrohrReading>(`${this.baseUrl}/current`);
  }
}
