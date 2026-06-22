import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { ZigbeeLiveEvent } from '../models/zigbee.model';

type LiveStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * SSE-Service für Live-Zigbee-Messwerte.
 * Listens on the named event 'live' as emitted by the backend ZigbeeLiveService.
 */
@Injectable({ providedIn: 'root' })
export class ZigbeeLiveService {
  private readonly url = '/api/v1/zigbee/live';
  private eventSource: EventSource | null = null;
  private readonly statusSubject = new BehaviorSubject<LiveStatus>('disconnected');

  getStatusStream(): Observable<LiveStatus> {
    return this.statusSubject.asObservable();
  }

  getLiveStream(): Observable<ZigbeeLiveEvent> {
    return new Observable<ZigbeeLiveEvent>((observer) => {
      this.connect();

      this.eventSource?.addEventListener('live', (event: MessageEvent) => {
        try {
          observer.next(JSON.parse(event.data) as ZigbeeLiveEvent);
        } catch (error) {
          observer.error(error);
        }
      });

      if (this.eventSource) {
        this.eventSource.onopen = () => this.statusSubject.next('connected');
        this.eventSource.onerror = (error) => {
          this.statusSubject.next('error');
          observer.error(error);
        };
      }

      return () => this.disconnect();
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
    if (this.eventSource) { return; }
    this.statusSubject.next('connecting');
    this.eventSource = new EventSource(this.url);
  }
}
