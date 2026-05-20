import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { EnergyLive } from '../models/energy-live.model';

type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

@Injectable({
  providedIn: 'root'
})
export class EnergyLiveService {
  private readonly url = '/api/energy/live';
  private eventSource: EventSource | null = null;
  private readonly statusSubject = new BehaviorSubject<ConnectionStatus>('disconnected');

  getStatusStream(): Observable<ConnectionStatus> {
    return this.statusSubject.asObservable();
  }

  getLiveStream(): Observable<EnergyLive> {
    return new Observable<EnergyLive>((observer) => {
      this.connect();

      this.eventSource?.addEventListener('live', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as EnergyLive;
          observer.next(data);
        } catch (error) {
          observer.error(error);
        }
      });

      if (this.eventSource) {
        this.eventSource.onopen = () => {
          this.statusSubject.next('connected');
        };
        this.eventSource.onerror = (error) => {
          this.statusSubject.next('error');
          observer.error(error);
        };
      }

      return () => {
        this.disconnect();
      };
    });
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.statusSubject.next('disconnected');
  }

  private connect(): void {
    if (this.eventSource) {
      return;
    }
    this.statusSubject.next('connecting');
    this.eventSource = new EventSource(this.url);
  }
}
