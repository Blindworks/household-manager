import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { AnkerSolixAutoControlSettings, AnkerSolixAutoControlStatus, AnkerSolixDeviceParams, AnkerSolixEnergyDay, AnkerSolixLive } from '../models/ankersolix.model';

/**
 * Service for Anker Solix solar station data.
 * Provides SSE live stream, energy history and device parameter control.
 */
@Injectable({
  providedIn: 'root'
})
export class AnkerSolixService {
  private readonly baseUrl = '/api/v1/ankersolix';
  private eventSource: EventSource | null = null;
  private readonly statusSubject = new BehaviorSubject<'disconnected' | 'connecting' | 'connected' | 'error'>('disconnected');

  readonly connectionStatus$ = this.statusSubject.asObservable();

  constructor(private readonly http: HttpClient) {}

  getLiveStream(): Observable<AnkerSolixLive> {
    return new Observable<AnkerSolixLive>((observer) => {
      // Always create a fresh connection so each subscription has its own
      // EventSource instance with exactly one listener — prevents duplicate
      // events if the component is destroyed and re-created by navigation.
      this.disconnectLive();
      this.statusSubject.next('connecting');
      const es = new EventSource(`${this.baseUrl}/live`);
      this.eventSource = es;

      es.addEventListener('live', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as AnkerSolixLive;
          this.statusSubject.next('connected');
          observer.next(data);
        } catch (error) {
          observer.error(error);
        }
      });

      es.onerror = () => {
        this.statusSubject.next('error');
        observer.error(new Error('SSE connection error'));
      };

      return () => {
        this.disconnectLive();
      };
    });
  }

  disconnectLive(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.statusSubject.next('disconnected');
  }

  getEnergyDay(date: string): Observable<AnkerSolixEnergyDay> {
    return this.http.get<AnkerSolixEnergyDay>(`${this.baseUrl}/energy`, { params: { date } });
  }

  getDeviceParams(): Observable<AnkerSolixDeviceParams> {
    return this.http.get<AnkerSolixDeviceParams>(`${this.baseUrl}/device-params`);
  }

  setOutputPower(watts: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/output-power`, { watts });
  }

  getAutoControlStatus(): Observable<AnkerSolixAutoControlStatus> {
    return this.http.get<AnkerSolixAutoControlStatus>(`${this.baseUrl}/auto-control/status`);
  }

  getAutoControlSettings(): Observable<AnkerSolixAutoControlSettings> {
    return this.http.get<AnkerSolixAutoControlSettings>(`${this.baseUrl}/auto-control/settings`);
  }

  updateAutoControlSettings(settings: AnkerSolixAutoControlSettings): Observable<AnkerSolixAutoControlSettings> {
    return this.http.put<AnkerSolixAutoControlSettings>(`${this.baseUrl}/auto-control/settings`, settings);
  }

  private connect(): void {
    if (this.eventSource) {
      return;
    }
    this.statusSubject.next('connecting');
    this.eventSource = new EventSource(`${this.baseUrl}/live`);
  }
}
