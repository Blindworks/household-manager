import { Component, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
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
    await this.refresh();
  }

  async refresh(): Promise<void> {
    if (!this.pushService.isSupported) {
      this.status = 'unsupported';
    } else if (this.pushService.permission === 'denied') {
      this.status = 'denied';
    } else {
      this.currentEndpoint = await this.pushService.currentEndpoint();
      this.status = this.currentEndpoint ? 'active' : 'inactive';
    }
    this.loadDevices();
  }

  private loadDevices(): void {
    this.pushService.getDevices().subscribe({
      next: devices => this.devices = devices,
      error: err => this.errorMessage = err.message
    });
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
      await this.pushService.unsubscribeThisDevice(this.devices);
      this.infoMessage = 'Benachrichtigungen fuer dieses Geraet deaktiviert.';
    } catch (err) {
      this.errorMessage = err instanceof Error ? err.message : String(err);
    } finally {
      this.busy = false;
      await this.refresh();
    }
  }

  removeDevice(device: PushDevice): void {
    this.pushService.deleteDevice(device.id).subscribe({
      next: () => this.refresh(),
      error: err => this.errorMessage = err.message
    });
  }

  sendTest(): void {
    this.infoMessage = '';
    this.pushService.sendTest().subscribe({
      next: () => this.infoMessage = 'Testnachricht verschickt.',
      error: err => this.errorMessage = err.message
    });
  }

  isCurrentDevice(device: PushDevice): boolean {
    return device.endpoint === this.currentEndpoint;
  }
}
