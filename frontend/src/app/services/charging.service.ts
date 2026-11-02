import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ChargePoint, ChargingForecast, ChargingSearchResponse, ChargingSettings, ChargingStationsResponse
} from '../models/charging.model';
import { SearchBox } from '../shared/charging-search.util';

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

  /** Ladepunkte einer beliebigen Station auf Anfrage (Backend cacht 60 s). */
  getChargePoints(stationId: string): Observable<ChargePoint[]> {
    return this.http.get<ChargePoint[]>(`${this.baseUrl}/stations/${encodeURIComponent(stationId)}/charge-points`);
  }

  getForecast(stationId: string): Observable<ChargingForecast> {
    return this.http.get<ChargingForecast>(`${this.baseUrl}/stations/${encodeURIComponent(stationId)}/forecast`);
  }

  /** Einmalige Suche im sichtbaren Kartenausschnitt (Backend: max. 30 km Diagonale, 10 s Sperre). */
  search(box: SearchBox): Observable<ChargingSearchResponse> {
    return this.http.get<ChargingSearchResponse>(`${this.baseUrl}/search`, {
      params: { fromLat: box.fromLat, toLat: box.toLat, fromLon: box.fromLon, toLon: box.toLon }
    });
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

  /** Direktpreis eines Favoriten setzen; {@code null} loescht Preis und Notiz (MEMBER). */
  setDirectPrice(stationId: string, pricePerKwh: number | null, note: string | null): Observable<ChargingStationsResponse> {
    return this.http.put<ChargingStationsResponse>(
      `${this.baseUrl}/favorites/${encodeURIComponent(stationId)}/price`, { pricePerKwh, note });
  }

  getSettings(): Observable<ChargingSettings> {
    return this.http.get<ChargingSettings>(`${this.baseUrl}/settings`);
  }

  saveSettings(settings: ChargingSettings): Observable<ChargingSettings> {
    return this.http.put<ChargingSettings>(`${this.baseUrl}/settings`, settings);
  }
}
