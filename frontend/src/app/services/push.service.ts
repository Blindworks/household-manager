import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { SwPush } from '@angular/service-worker';
import { Observable, firstValueFrom, race, throwError, timer } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { PushDevice } from '../models/push.model';

/** Wie lange auf swPush.subscription gewartet wird, bevor als "keine Subscription" gewertet wird
 *  (auf einer uncontrolled page, z.B. nach hartem Reload, emittiert das Observable sonst nie). */
const SUBSCRIPTION_LOOKUP_TIMEOUT_MS = 3000;

/** Web-Push: Anmeldung dieses Geraets + Verwaltung der eigenen Geraete. */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly baseUrl = '/api/v1/push';

  /** Vorab geladener VAPID-Public-Key, damit activate() requestSubscription ohne await aufrufen
   *  kann - ein zwischengeschalteter HTTP-Roundtrip verbraucht auf iOS sonst die Nutzer-Geste. */
  private cachedPublicKey: string | null = null;

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

  /** Holt und cached den VAPID-Public-Key im Voraus (z.B. beim Seitenaufbau); fehlgeschlagene
   *  Versuche werden verschluckt, subscribeThisDevice() faellt dann auf einen eigenen Abruf zurueck. */
  async preloadPublicKey(): Promise<void> {
    if (this.cachedPublicKey) {
      return;
    }
    try {
      this.cachedPublicKey = await this.fetchPublicKey();
    } catch {
      // Egal - subscribeThisDevice holt den Key bei Bedarf selbst nach.
    }
  }

  /** Endpoint der auf DIESEM Geraet aktiven Subscription (null = keine). Auf einer uncontrolled
   *  page emittiert swPush.subscription nie - ein Timeout verhindert, dass dies fuer immer haengt. */
  async currentEndpoint(): Promise<string | null> {
    if (!this.isSupported) {
      return null;
    }
    const subscription = await firstValueFrom(
      race(this.swPush.subscription, timer(SUBSCRIPTION_LOOKUP_TIMEOUT_MS).pipe(map(() => null))));
    return subscription?.endpoint ?? null;
  }

  /** Fragt die Berechtigung an (nur aus einer Nutzer-Geste aufrufen!) und registriert das Geraet. */
  async subscribeThisDevice(): Promise<void> {
    const publicKey = this.cachedPublicKey ?? await this.fetchPublicKey();
    const subscription = await this.swPush.requestSubscription({ serverPublicKey: publicKey });
    const json = subscription.toJSON();
    if (!json.endpoint || !json.keys) {
      throw new Error('Subscription unvollstaendig - bitte erneut versuchen.');
    }
    await firstValueFrom(this.http.post(`${this.baseUrl}/subscriptions`, {
      endpoint: json.endpoint,
      p256dh: json.keys['p256dh'],
      auth: json.keys['auth'],
      userAgent: navigator.userAgent
    }).pipe(catchError(this.handleError)));
  }

  /** Meldet DIESES Geraet ab (serverseitig + lokal); fragt die Geraeteliste dafuer frisch ab,
   *  eine vom Aufrufer mitgegebene Liste koennte veraltet sein. */
  async unsubscribeThisDevice(): Promise<void> {
    const endpoint = await this.currentEndpoint();
    if (endpoint) {
      const devices = await firstValueFrom(this.getDevices());
      const device = devices.find(d => d.endpoint === endpoint);
      if (device) {
        await firstValueFrom(this.deleteDevice(device.id));
      }
    }
    await this.swPush.unsubscribe().catch(() => undefined);
  }

  private async fetchPublicKey(): Promise<string> {
    const { publicKey } = await firstValueFrom(
      this.http.get<{ publicKey: string }>(`${this.baseUrl}/vapid-public-key`)
        .pipe(catchError(this.handleError)));
    return publicKey;
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
