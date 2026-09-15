import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { ZigbeeBridgeEvent, ZigbeeBridgeStatus, ZigbeeLiveEvent } from '../models/zigbee.model';

type LiveStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

/**
 * SSE-Service fuer /api/v1/zigbee/live. Eine Verbindung, drei benannte Ereignisse:
 * 'live' (Messwerte), 'bridge-event' (Anlernen live), 'bridge-info' (Anlernfenster,
 * Verfuegbarkeitspruefung). Ein Unsubscribe entfernt nur seinen Listener — die
 * Verbindung schliesst erst disconnect() (die Seite ruft es in ngOnDestroy).
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
    return this.stream<ZigbeeLiveEvent>('live');
  }

  getBridgeEvents(): Observable<ZigbeeBridgeEvent> {
    return this.stream<ZigbeeBridgeEvent>('bridge-event');
  }

  getBridgeInfo(): Observable<ZigbeeBridgeStatus> {
    return this.stream<ZigbeeBridgeStatus>('bridge-info');
  }

  disconnect(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.statusSubject.next('disconnected');
  }

  private stream<T>(name: string): Observable<T> {
    return new Observable<T>((observer) => {
      const source = this.connect();
      const listener = (event: MessageEvent) => {
        try {
          observer.next(JSON.parse(event.data) as T);
        } catch (error) {
          observer.error(error);
        }
      };
      source.addEventListener(name, listener);
      return () => source.removeEventListener(name, listener);
    });
  }

  private connect(): EventSource {
    if (this.eventSource) { return this.eventSource; }
    this.statusSubject.next('connecting');
    const source = new EventSource(this.url);
    source.onopen = () => this.statusSubject.next('connected');
    // Kein observer.error hier: der Browser verbindet SSE selbst neu, ein Fehler
    // wuerde alle drei Streams beenden, obwohl die Verbindung gleich wiederkommt.
    source.onerror = () => this.statusSubject.next('error');
    this.eventSource = source;
    return source;
  }
}
