import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  NetworkDeviceAdminResponse,
  NetworkDeviceRequest,
  NetworkHistoryResponse,
  NetworkStatusResponse,
  SpeedtestSummary,
  TimeRange
} from '../models/network.model';

/** REST-Service fürs Netzwerk-Monitoring (Status, Historie, Speedtest, pflegbare Geräte). */
@Injectable({ providedIn: 'root' })
export class NetworkService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/network';

  getStatus(): Observable<NetworkStatusResponse> {
    return this.http.get<NetworkStatusResponse>(`${this.baseUrl}/status`);
  }

  getHistory(range: TimeRange): Observable<NetworkHistoryResponse> {
    return this.http.get<NetworkHistoryResponse>(`${this.baseUrl}/history`, {
      params: { range }
    });
  }

  runSpeedtest(): Observable<SpeedtestSummary> {
    return this.http.post<SpeedtestSummary>(`${this.baseUrl}/speedtest`, {});
  }

  getDevices(): Observable<NetworkDeviceAdminResponse[]> {
    return this.http.get<NetworkDeviceAdminResponse[]>(`${this.baseUrl}/devices`);
  }

  createDevice(request: NetworkDeviceRequest): Observable<NetworkDeviceAdminResponse> {
    return this.http.post<NetworkDeviceAdminResponse>(`${this.baseUrl}/devices`, request);
  }

  updateDevice(id: number, request: NetworkDeviceRequest): Observable<NetworkDeviceAdminResponse> {
    return this.http.put<NetworkDeviceAdminResponse>(`${this.baseUrl}/devices/${id}`, request);
  }

  deleteDevice(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/devices/${id}`);
  }
}
