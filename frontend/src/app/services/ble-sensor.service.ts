import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BleSensorAdminResponse, BleSensorUpdateRequest } from '../models/ble-sensor.model';

/** REST-Service fuer die Admin-Pflege der Bluetooth-Thermometer. */
@Injectable({ providedIn: 'root' })
export class BleSensorService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/ble-sensors';

  list(): Observable<BleSensorAdminResponse[]> {
    return this.http.get<BleSensorAdminResponse[]>(this.baseUrl);
  }

  update(id: number, request: BleSensorUpdateRequest): Observable<BleSensorAdminResponse> {
    return this.http.put<BleSensorAdminResponse>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
