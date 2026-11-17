import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { RainRadarForecast } from '../models/rain-radar.model';

/** Radarverlauf des letzten Backend-Abrufs (gemessen + 2-h-Vorhersage) fuer den Dashboard-Dialog. */
@Injectable({ providedIn: 'root' })
export class RainRadarService {
  private readonly http = inject(HttpClient);

  getForecast(): Observable<RainRadarForecast> {
    return this.http.get<RainRadarForecast>('/api/v1/rain-radar/forecast');
  }
}
