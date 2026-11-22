import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AlexaService } from '../../../services/alexa.service';
import { AlexaAuthStatus } from '../../../models/alexa.model';

/** Amazon-Konto (Alexa): Browser-Login über den Sidecar-Proxy und Abmelden. */
@Component({
  selector: 'app-alexa-account',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './alexa-account.component.html',
  styleUrl: '../account-login.scss'
})
export class AlexaAccountComponent implements OnInit, OnDestroy {
  private readonly alexa = inject(AlexaService);

  readonly authStatus = signal<AlexaAuthStatus | null>(null);
  readonly statusFailed = signal(false);
  readonly message = signal('');
  readonly proxyUrl = signal<string | null>(null);
  readonly loginWaiting = signal(false);
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  ngOnInit(): void {
    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.clearPolling();
  }

  refreshStatus(): void {
    this.alexa.getAuthStatus().subscribe({
      next: status => {
        this.statusFailed.set(false);
        this.authStatus.set(status);
      },
      error: () => this.statusFailed.set(true)
    });
  }

  /** Startet den Browser-Login: holt die Proxy-URL und pollt anschließend den Status. */
  startLogin(): void {
    this.message.set('');
    this.alexa.startProxyLogin().subscribe({
      next: response => {
        this.proxyUrl.set(response.proxyUrl);
        this.loginWaiting.set(true);
        this.startPolling();
      },
      error: e => this.message.set(e.message)
    });
  }

  cancelLogin(): void {
    this.finishLogin();
  }

  logout(): void {
    this.message.set('');
    this.alexa.logout().subscribe({
      next: () => this.refreshStatus(),
      error: e => this.message.set(e.message)
    });
  }

  private startPolling(): void {
    this.clearPolling();
    this.pollHandle = setInterval(() => {
      this.alexa.getAuthStatus().subscribe({
        next: status => {
          this.authStatus.set(status);
          if (status.loggedIn) {
            this.finishLogin();
          } else if (status.loginError) {
            this.finishLogin();
            this.message.set('Anmeldung fehlgeschlagen: ' + status.loginError);
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
}
