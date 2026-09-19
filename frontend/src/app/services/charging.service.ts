import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChargingSettings, ChargingStationsResponse } from '../models/charging.model';

/**
 * REST-Service fuer die Ladesaeulen-Uebersicht. Fehler werden bewusst NICHT auf eine
 * Einheitsmeldung reduziert: die Seiten zeigen die Server-Meldung (400 "kein Zuhause",
 * 429 "gerade eben", 502 "EnBW nicht erreichbar") direkt an.
 */
@Injectable({ providedIn: 'root' })
export class ChargingService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/charging';

  getStations(): Observable<ChargingStationsResponse> {
    return this.http.get<ChargingStationsResponse>(`${this.baseUrl}/stations`);
  }

  refresh(): Observable<ChargingStationsResponse> {
    return this.http.post<ChargingStationsResponse>(`${this.baseUrl}/refresh`, {});
  }

  addFavorite(stationId: string): Observable<ChargingStationsResponse> {
    return this.http.put<ChargingStationsResponse>(`${this.baseUrl}/favorites/${encodeURIComponent(stationId)}`, {});
  }

  removeFavorite(stationId: string): Observable<ChargingStationsResponse> {
    return this.http.delete<ChargingStationsResponse>(`${this.baseUrl}/favorites/${encodeURIComponent(stationId)}`);
  }

  getSettings(): Observable<ChargingSettings> {
    return this.http.get<ChargingSettings>(`${this.baseUrl}/settings`);
  }

  saveSettings(settings: ChargingSettings): Observable<ChargingSettings> {
    return this.http.put<ChargingSettings>(`${this.baseUrl}/settings`, settings);
  }
}
