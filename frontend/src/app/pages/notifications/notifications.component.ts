import { Component, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { PushService } from '../../services/push.service';
import { PushDevice } from '../../models/push.model';

type PushStatus = 'unsupported' | 'denied' | 'inactive' | 'active';

/** Seite "Benachrichtigungen": Web-Push fuer dieses Geraet aktivieren und eigene Geraete verwalten. */
@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './notifications.component.html',
  styleUrls: ['./notifications.component.scss']
})
export class NotificationsComponent implements OnInit {
  private readonly pushService = inject(PushService);

  status: PushStatus = 'inactive';
  devices: PushDevice[] = [];
  currentEndpoint: string | null = null;
  busy = false;
  errorMessage = '';
  infoMessage = '';

  async ngOnInit(): Promise<void> {
    void this.pushService.preloadPublicKey();
    await this.refresh();
  }

  async refresh(): Promise<void> {
    // Geraeteliste haengt an keiner SW-Interaktion und darf nicht auf sie warten
    // (die 3s-Wartezeit auf swPush.subscription weiter unten darf die Liste nicht blockieren).
    await this.loadDevices();

    if (!this.pushService.isSupported) {
      this.status = 'unsupported';
      return;
    }
    if (this.pushService.permission === 'denied') {
      this.status = 'denied';
      return;
    }
    try {
      this.currentEndpoint = await this.pushService.currentEndpoint();
    } catch (err) {
      this.currentEndpoint = null;
      this.errorMessage = err instanceof Error ? err.message : String(err);
    }
    // "aktiv" nur, wenn Browser-Subscription UND Server-Geraeteliste uebereinstimmen —
    // sonst behauptet die Seite auf einem geteilten Geraet oder nach Selbstbereinigung
    // (404/410) faelschlich "aktiv", ohne einen Weg zurueck zur Aktivierung zu zeigen.
    this.status = this.currentEndpoint && this.devices.some(d => d.endpoint === this.currentEndpoint)
      ? 'active'
      : 'inactive';
  }

  private async loadDevices(): Promise<void> {
    try {
      this.devices = await firstValueFrom(this.pushService.getDevices());
    } catch (err) {
      this.errorMessage = err instanceof Error ? err.message : String(err);
    }
  }

  async activate(): Promise<void> {
    this.busy = true;
    this.errorMessage = '';
    this.infoMessage = '';
    try {
      await this.pushService.subscribeThisDevice();
      this.infoMessage = 'Benachrichtigungen aktiviert.';
    } catch (err) {
      this.errorMessage = this.pushService.permission === 'denied'
        ? 'Berechtigung verweigert — bitte in den Browser-Einstellungen erlauben.'
        : 'Aktivierung fehlgeschlagen: ' + (err instanceof Error ? err.message : String(err));
    } finally {
      this.busy = false;
      await this.refresh();
    }
  }

  async deactivate(): Promise<void> {
    this.busy = true;
    this.errorMessage = '';
    this.infoMessage = '';
    try {
      await this.pushService.unsubscribeThisDevice();
      this.infoMessage = 'Benachrichtigungen fuer dieses Geraet deaktiviert.';
    } catch (err) {
      this.errorMessage = err instanceof Error ? err.message : String(err);
    } finally {
      this.busy = false;
      await this.refresh();
    }
  }

  removeDevice(device: PushDevice): void {
    this.busy = true;
    this.errorMessage = '';
    this.pushService.deleteDevice(device.id).subscribe({
      next: () => {
        this.busy = false;
        this.refresh();
      },
      error: err => {
        this.busy = false;
        this.errorMessage = err.message;
      }
    });
  }

  sendTest(): void {
    this.busy = true;
    this.errorMessage = '';
    this.infoMessage = '';
    this.pushService.sendTest().subscribe({
      next: () => {
        this.busy = false;
        this.infoMessage = 'Testnachricht verschickt.';
      },
      error: err => {
        this.busy = false;
        this.errorMessage = err.message;
      }
    });
  }

  isCurrentDevice(device: PushDevice): boolean {
    return device.endpoint === this.currentEndpoint;
  }
}
