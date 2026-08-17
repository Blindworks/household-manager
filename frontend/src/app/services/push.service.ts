import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { SwPush } from '@angular/service-worker';
import { Observable, firstValueFrom, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PushDevice } from '../models/push.model';

/** Web-Push: Anmeldung dieses Geraets + Verwaltung der eigenen Geraete. */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly baseUrl = '/api/v1/push';

  /** Push braucht einen aktiven Service Worker und die Notification-API (Dev-Server: SW aus -> false). */
  get isSupported(): boolean {
    return this.swPush.isEnabled && 'Notification' in window;
  }

  get permission(): NotificationPermission | null {
    return 'Notification' in window ? Notification.permission : null;
  }

  getDevices(): Observable<PushDevice[]> {
    return this.http.get<PushDevice[]>(`${this.baseUrl}/subscriptions`)
      .pipe(catchError(this.handleError));
  }

  /** Endpoint der auf DIESEM Geraet aktiven Subscription (null = keine). */
  async currentEndpoint(): Promise<string | null> {
    if (!this.isSupported) {
      return null;
    }
    const subscription = await firstValueFrom(this.swPush.subscription);
    return subscription?.endpoint ?? null;
  }

  /** Fragt die Berechtigung an (nur aus einer Nutzer-Geste aufrufen!) und registriert das Geraet. */
  async subscribeThisDevice(): Promise<void> {
    const { publicKey } = await firstValueFrom(
      this.http.get<{ publicKey: string }>(`${this.baseUrl}/vapid-public-key`));
    const subscription = await this.swPush.requestSubscription({ serverPublicKey: publicKey });
    const json = subscription.toJSON();
    await firstValueFrom(this.http.post(`${this.baseUrl}/subscriptions`, {
      endpoint: json.endpoint,
      p256dh: json.keys?.['p256dh'],
      auth: json.keys?.['auth'],
      userAgent: navigator.userAgent
    }));
  }

  /** Meldet DIESES Geraet ab (serverseitig + lokal). */
  async unsubscribeThisDevice(devices: PushDevice[]): Promise<void> {
    const endpoint = await this.currentEndpoint();
    const device = endpoint ? devices.find(d => d.endpoint === endpoint) : undefined;
    if (device) {
      await firstValueFrom(this.deleteDevice(device.id));
    }
    await this.swPush.unsubscribe().catch(() => undefined);
  }

  deleteDevice(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/subscriptions/${id}`)
      .pipe(catchError(this.handleError));
  }

  sendTest(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/test`, {})
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    console.error('Push-API-Fehler:', error);
    const message = error.error?.message || 'Fehler bei der Push-Kommunikation.';
    return throwError(() => new Error(message));
  }
}
