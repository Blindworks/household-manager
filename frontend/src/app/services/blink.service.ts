import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BlinkCamera, BlinkClip } from '../models/blink.model';

/** REST-Service fuer das Blink-Kamera-Dashboard (Liste, Schalten, Schnappschuss, Clips). */
@Injectable({ providedIn: 'root' })
export class BlinkService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/blink';

  getCameras(): Observable<BlinkCamera[]> {
    return this.http.get<BlinkCamera[]>(`${this.baseUrl}/cameras`);
  }

  setCameraArmed(cameraId: string, armed: boolean): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/${armed ? 'arm' : 'disarm'}`, {});
  }

  setSystemArmed(syncName: string, armed: boolean): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/system/${encodeURIComponent(syncName)}/${armed ? 'arm' : 'disarm'}`, {});
  }

  /** Loest ein neues Standbild aus; das Bild selbst laedt danach die <img> per Cache-Buster. */
  takeSnapshot(cameraId: string): Observable<Blob> {
    return this.http.post(
      `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/snapshot`, {},
      { responseType: 'blob' });
  }

  getClips(cameraId: string): Observable<BlinkClip[]> {
    return this.http.get<BlinkClip[]>(
      `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/clips`);
  }

  /** Bild-URL fuer <img>; cacheKey erzwingt nach einem Schnappschuss ein frisches Bild. */
  thumbnailUrl(cameraId: string, cacheKey: number): string {
    return `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/thumbnail?t=${cacheKey}`;
  }

  clipUrl(cameraId: string, clipId: string): string {
    return `${this.baseUrl}/cameras/${encodeURIComponent(cameraId)}/clips/${encodeURIComponent(clipId)}`;
  }
}
