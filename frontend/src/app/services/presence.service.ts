import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  PresenceDeviceAdmin,
  PresenceDeviceRequest,
  PresenceSettings,
  PresenceStatusResponse
} from '../models/presence.model';

/** REST-Service für die Anwesenheitserkennung (Status, Geräte, Karenzzeit). */
@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/presence';

  getStatus(): Observable<PresenceStatusResponse> {
    return this.http.get<PresenceStatusResponse>(`${this.baseUrl}/status`);
  }

  getDevices(): Observable<PresenceDeviceAdmin[]> {
    return this.http.get<PresenceDeviceAdmin[]>(`${this.baseUrl}/devices`);
  }

  createDevice(request: PresenceDeviceRequest): Observable<PresenceDeviceAdmin> {
    return this.http.post<PresenceDeviceAdmin>(`${this.baseUrl}/devices`, request);
  }

  updateDevice(id: number, request: PresenceDeviceRequest): Observable<PresenceDeviceAdmin> {
    return this.http.put<PresenceDeviceAdmin>(`${this.baseUrl}/devices/${id}`, request);
  }

  deleteDevice(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/devices/${id}`);
  }

  getSettings(): Observable<PresenceSettings> {
    return this.http.get<PresenceSettings>(`${this.baseUrl}/settings`);
  }

  updateSettings(settings: PresenceSettings): Observable<PresenceSettings> {
    return this.http.put<PresenceSettings>(`${this.baseUrl}/settings`, settings);
  }
}
