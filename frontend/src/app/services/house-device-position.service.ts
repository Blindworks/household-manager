import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { HouseDevicePosition } from '../models/house-device-position.model';
import { PlanPoint } from '../shared/house/house-placement.util';

/** Gespeicherte Geraeteplaetze des Digitalen Zwillings. Lesen KIOSK, Schreiben ADMIN. */
@Injectable({ providedIn: 'root' })
export class HouseDevicePositionService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/house/device-positions';

  list(): Observable<HouseDevicePosition[]> {
    return this.http.get<HouseDevicePosition[]>(this.baseUrl);
  }

  set(deviceId: string, point: PlanPoint): Observable<HouseDevicePosition> {
    return this.http.put<HouseDevicePosition>(this.url(deviceId), { x: point.x, y: point.y });
  }

  remove(deviceId: string): Observable<void> {
    return this.http.delete<void>(this.url(deviceId));
  }

  private url(deviceId: string): string {
    return `${this.baseUrl}/${encodeURIComponent(deviceId)}`;
  }
}
