import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  Thermostat,
  ThermostatHistoryResponse,
  ThermostatMode,
  ThermostatRange
} from '../models/thermostat.model';
import { ThermostatMock } from './thermostat-mock';

/**
 * REST-Service fuer die Heizkoerperthermostate (`/v1/thermostats`). Solange
 * `environment.useThermostatMock` gesetzt ist, antwortet stattdessen ein
 * Speicher-Mock mit demselben Vertrag.
 */
@Injectable({ providedIn: 'root' })
export class ThermostatService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/thermostats';
  private readonly mock = new ThermostatMock();
  private useMock = environment.useThermostatMock;

  list(): Observable<Thermostat[]> {
    return this.useMock ? this.mock.list() : this.http.get<Thermostat[]>(this.baseUrl);
  }

  setTarget(id: number, targetTemp: number): Observable<Thermostat> {
    return this.useMock
      ? this.mock.setTarget(id, targetTemp)
      : this.http.put<Thermostat>(`${this.baseUrl}/${id}/target`, { targetTemp });
  }

  setMode(id: number, mode: ThermostatMode): Observable<Thermostat> {
    return this.useMock
      ? this.mock.setMode(id, mode)
      : this.http.put<Thermostat>(`${this.baseUrl}/${id}/mode`, { mode });
  }

  /** `ids` null = alle Thermostate. */
  bulk(ids: number[] | null, targetTemp: number): Observable<Thermostat[]> {
    return this.useMock
      ? this.mock.bulk(ids, targetTemp)
      : this.http.post<Thermostat[]>(`${this.baseUrl}/bulk`, { ids, targetTemp });
  }

  history(id: number, range: ThermostatRange): Observable<ThermostatHistoryResponse> {
    return this.useMock
      ? this.mock.history(id, range)
      : this.http.get<ThermostatHistoryResponse>(`${this.baseUrl}/${id}/history`, {
          params: { range }
        });
  }
}
