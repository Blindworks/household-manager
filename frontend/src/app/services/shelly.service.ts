import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ShellyReading, ShellyStatus } from '../models/shelly.model';

@Injectable({
  providedIn: 'root'
})
export class ShellyService {
  private readonly baseUrl = '/api/shelly';

  constructor(private readonly http: HttpClient) {}

  getAllDevicesStatus(): Observable<ShellyStatus[]> {
    return this.http.get<ShellyStatus[]>(this.baseUrl);
  }

  getReadings(deviceName: string): Observable<ShellyReading[]> {
    return this.http.get<ShellyReading[]>(`${this.baseUrl}/${deviceName}/readings`);
  }

  turnOn(deviceName: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${deviceName}/on`, null);
  }

  turnOff(deviceName: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${deviceName}/off`, null);
  }
}
