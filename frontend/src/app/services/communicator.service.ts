import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CommunicatorAnswer, CommunicatorStatus } from '../models/communicator.model';

/** REST-Service der Sprach-App: Aufnahme oder Text hinein, Karten heraus. */
@Injectable({ providedIn: 'root' })
export class CommunicatorService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/communicator';

  getStatus(): Observable<CommunicatorStatus> {
    return this.http.get<CommunicatorStatus>(`${this.baseUrl}/status`);
  }

  /** Schickt eine Sprachaufnahme; der Server wandelt sie in Text um und antwortet mit Karten. */
  askAudio(recording: Blob): Observable<CommunicatorAnswer> {
    const form = new FormData();
    form.append('audio', recording, 'aufnahme');
    return this.http.post<CommunicatorAnswer>(`${this.baseUrl}/ask`, form);
  }

  askText(text: string): Observable<CommunicatorAnswer> {
    return this.http.post<CommunicatorAnswer>(`${this.baseUrl}/ask-text`, { text });
  }
}
