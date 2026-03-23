import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ShellyStatus } from '../models/shelly.model';

@Injectable({
  providedIn: 'root'
})
export class ShellyLiveService {
  private readonly url = '/api/shelly/live';
  private eventSource: EventSource | null = null;

  getLiveStream(): Observable<ShellyStatus[]> {
    return new Observable<ShellyStatus[]>((observer) => {
      this.connect();

      this.eventSource?.addEventListener('live', (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as ShellyStatus[];
          observer.next(data);
        } catch (error) {
          observer.error(error);
        }
      });

      if (this.eventSource) {
        this.eventSource.onerror = () => {
          observer.error(new Error('Shelly SSE connection error'));
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
  }

  private connect(): void {
    if (this.eventSource) {
      return;
    }
    this.eventSource = new EventSource(this.url);
  }
}
