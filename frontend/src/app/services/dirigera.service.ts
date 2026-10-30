import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DirigeraHub, DirigeraPairingStatus, DirigeraSensor } from '../models/dirigera.model';

/** REST-Service fuer die Admin-Pflege der IKEA-Dirigera-Anbindung. */
@Injectable({ providedIn: 'root' })
export class DirigeraService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/dirigera';

  getHub(): Observable<DirigeraHub> {
    return this.http.get<DirigeraHub>(`${this.baseUrl}/hub`);
  }

  updateHost(host: string): Observable<DirigeraHub> {
    return this.http.put<DirigeraHub>(`${this.baseUrl}/hub`, { host });
  }

  startPairing(): Observable<DirigeraPairingStatus> {
    return this.http.post<DirigeraPairingStatus>(`${this.baseUrl}/pairing`, {});
  }

  getPairing(): Observable<DirigeraPairingStatus> {
    return this.http.get<DirigeraPairingStatus>(`${this.baseUrl}/pairing`);
  }

  unpair(): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/pairing`);
  }

  getSensors(): Observable<DirigeraSensor[]> {
    return this.http.get<DirigeraSensor[]>(`${this.baseUrl}/sensors`);
  }

  deleteSensor(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/sensors/${id}`);
  }
}
