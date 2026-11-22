import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { AlexaService } from '../../services/alexa.service';
import { AlexaAuthStatus, AlexaDevice, AlexaTtsMode } from '../../models/alexa.model';

/** Seite fuer Alexa-Durchsagen; die Anmeldung liegt unter Admin → Konten. */
@Component({
  selector: 'app-announcements',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './announcements.component.html',
  styleUrl: './announcements.component.scss'
})
export class AnnouncementsComponent implements OnInit {
  private readonly alexa = inject(AlexaService);
  /** Public: der Hinweis auf die Anmeldung verlinkt nur für Administratoren. */
  readonly auth = inject(AuthService);

  readonly authStatus = signal<AlexaAuthStatus | null>(null);
  readonly devices = signal<AlexaDevice[]>([]);
  readonly message = signal<string>('');

  // Durchsage-Formular
  announceText = '';
  announceMode: AlexaTtsMode = 'ANNOUNCE';
  selectedSerials: Record<string, boolean> = {};

  ngOnInit(): void {
    this.refreshStatus();
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
