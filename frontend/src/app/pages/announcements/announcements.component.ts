import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AlexaService } from '../../services/alexa.service';
import {
  AlexaAuthStatus, AlexaDevice,
  AlexaTtsMode, ScheduledAnnouncement
} from '../../models/alexa.model';

/** Seite fuer Alexa-Durchsagen: Konto, manuelle Durchsage und geplante Ansagen. */
@Component({
  selector: 'app-announcements',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './announcements.component.html',
  styleUrl: './announcements.component.scss'
})
export class AnnouncementsComponent implements OnInit, OnDestroy {
  private readonly alexa = inject(AlexaService);

  readonly weekdayOptions = [
    { key: 'MONDAY', label: 'Mo' }, { key: 'TUESDAY', label: 'Di' },
    { key: 'WEDNESDAY', label: 'Mi' }, { key: 'THURSDAY', label: 'Do' },
    { key: 'FRIDAY', label: 'Fr' }, { key: 'SATURDAY', label: 'Sa' },
    { key: 'SUNDAY', label: 'So' }
  ];

  readonly authStatus = signal<AlexaAuthStatus | null>(null);
  readonly devices = signal<AlexaDevice[]>([]);
  readonly scheduled = signal<ScheduledAnnouncement[]>([]);
  readonly message = signal<string>('');

  // Browser-Login
  readonly proxyUrl = signal<string | null>(null);
  readonly loginWaiting = signal<boolean>(false);
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  // Durchsage-Formular
  announceText = '';
  announceMode: AlexaTtsMode = 'ANNOUNCE';
  selectedSerials: Record<string, boolean> = {};

  // Neue geplante Ansage
  newSchedule: ScheduledAnnouncement = {
    text: '', timeOfDay: '08:00', weekdays: [], serialNumbers: [], mode: 'ANNOUNCE', enabled: true
  };

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
          this.loadScheduled();
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
            this.loadScheduled();
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

  loadScheduled(): void {
    this.alexa.getScheduled().subscribe({
      next: s => this.scheduled.set(s),
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

  toggleNewScheduleWeekday(key: string): void {
    const days = this.newSchedule.weekdays;
    this.newSchedule.weekdays = days.includes(key) ? days.filter(d => d !== key) : [...days, key];
  }

  createSchedule(): void {
    this.newSchedule.serialNumbers = this.selectedSerialNumbers();
    if (!this.newSchedule.text.trim() || this.newSchedule.serialNumbers.length === 0
        || this.newSchedule.weekdays.length === 0) {
      this.message.set('Bitte Text, Wochentage und Geraete fuer die geplante Ansage waehlen.');
      return;
    }
    this.alexa.createScheduled(this.newSchedule).subscribe({
      next: () => {
        this.loadScheduled();
        this.newSchedule = { text: '', timeOfDay: '08:00', weekdays: [], serialNumbers: [], mode: 'ANNOUNCE', enabled: true };
      },
      error: e => this.message.set(e.message)
    });
  }

  toggleScheduleEnabled(a: ScheduledAnnouncement): void {
    this.alexa.updateScheduled(a.id!, { ...a, enabled: !a.enabled })
      .subscribe({ next: () => this.loadScheduled() });
  }

  deleteSchedule(a: ScheduledAnnouncement): void {
    this.alexa.deleteScheduled(a.id!).subscribe({ next: () => this.loadScheduled() });
  }
}
