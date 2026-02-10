import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { TasmotaLiveReading } from '../models/tasmota-live.model';

/**
 * SSE service for live Tasmota electricity data.
 */
@Injectable({
  providedIn: 'root'
})
export class TasmotaLiveService {
  private readonly url = '/api/v1/tasmota-electricity/live';
  private eventSource: EventSource | null = null;
  private readonly statusSubject = new BehaviorSubject<'disconnected' | 'connecting' | 'connected' | 'error'>('disconnected');

  getStatusStream(): Observable<'disconnected' | 'connecting' | 'connected' | 'error'> {
    return this.statusSubject.asObservable();
  }

  getLiveStream(): Observable<TasmotaLiveReading> {
    return new Observable<TasmotaLiveReading>((observer) => {
      this.connect();

      this.eventSource?.addEventListener('live', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as TasmotaLiveReading;
          observer.next(data);
        } catch (error) {
          observer.error(error);
        }
      });

      if (this.eventSource) {
        this.eventSource.onopen = () => {
          this.statusSubject.next('connected');
        };
      }

      this.eventSource!.onerror = (error) => {
        this.statusSubject.next('error');
        observer.error(error);
      };

      return () => {
        this.disconnect();
      };
    });
  }

  reconnect(): void {
    this.disconnect();
    this.connect();
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
