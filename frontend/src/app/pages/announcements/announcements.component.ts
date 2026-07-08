import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AlexaService } from '../../services/alexa.service';
import { AlexaAuthStatus, AlexaDevice, AlexaTtsMode } from '../../models/alexa.model';

/** Seite fuer Alexa-Durchsagen: Konto und manuelle Durchsage. */
@Component({
  selector: 'app-announcements',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './announcements.component.html',
  styleUrl: './announcements.component.scss'
})
export class AnnouncementsComponent implements OnInit, OnDestroy {
  private readonly alexa = inject(AlexaService);

  readonly authStatus = signal<AlexaAuthStatus | null>(null);
  readonly devices = signal<AlexaDevice[]>([]);
  readonly message = signal<string>('');

  // Browser-Login
  readonly proxyUrl = signal<string | null>(null);
  readonly loginWaiting = signal<boolean>(false);
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  // Durchsage-Formular
  announceText = '';
  announceMode: AlexaTtsMode = 'ANNOUNCE';
  selectedSerials: Record<string, boolean> = {};

  ngOnInit(): void {
    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.clearPolling();
  }

  refreshStatus(): void {
    this.alexa.getAuthStatus().subscribe({
      next: s => {
        this.authStatus.set(s);
        if (s.loggedIn) {
          this.loadDevices(false);
        }
      },
      error: e => this.message.set(e.message)
    });
  }

  /** Startet den Browser-Login: holt die Proxy-URL und pollt anschliessend den Status. */
  startLogin(): void {
    this.message.set('');
    this.alexa.startProxyLogin().subscribe({
      next: r => {
        this.proxyUrl.set(r.proxyUrl);
        this.loginWaiting.set(true);
        this.startPolling();
      },
      error: e => this.message.set(e.message)
    });
  }

  cancelLogin(): void {
    this.clearPolling();
    this.loginWaiting.set(false);
    this.proxyUrl.set(null);
  }

  private startPolling(): void {
    this.clearPolling();
    this.pollHandle = setInterval(() => {
      this.alexa.getAuthStatus().subscribe({
        next: s => {
          this.authStatus.set(s);
          if (s.loggedIn) {
            this.finishLogin();
            this.loadDevices(false);
          } else if (s.loginError) {
            this.finishLogin();
            this.message.set('Anmeldung fehlgeschlagen: ' + s.loginError);
          }
        }
      });
    }, 3000);
  }

  private finishLogin(): void {
    this.clearPolling();
    this.loginWaiting.set(false);
    this.proxyUrl.set(null);
  }

  private clearPolling(): void {
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

  logout(): void {
    this.alexa.logout().subscribe({ next: () => this.refreshStatus() });
  }

  loadDevices(rescan: boolean): void {
    this.alexa.getDevices(rescan).subscribe({
      next: d => this.devices.set(d),
      error: e => this.message.set(e.message)
    });
  }

  private selectedSerialNumbers(): string[] {
    return Object.keys(this.selectedSerials).filter(k => this.selectedSerials[k]);
  }

  sendAnnouncement(): void {
    const serials = this.selectedSerialNumbers();
    if (!this.announceText.trim() || serials.length === 0) {
      this.message.set('Bitte Text eingeben und mindestens ein Geraet waehlen.');
      return;
    }
    this.alexa.announce({ text: this.announceText, serialNumbers: serials, mode: this.announceMode })
      .subscribe({
        next: () => this.message.set('Durchsage gesendet.'),
        error: e => this.message.set(e.message)
      });
  }
}
