import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { TractiveService } from '../../../services/tractive.service';
import { TractiveAuthStatus } from '../../../models/tractive.model';

/**
 * Tractive-Konto des Hundetrackers. Liegt im Admin-Bereich, weil {@code POST /v1/tractive/login}
 * ADMIN-only ist – auf der für alle offenen Hundetracker-Seite gäbe ein Formular nur 403.
 */
@Component({
  selector: 'app-tractive-account',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './tractive-account.component.html',
  styleUrl: '../account-login.scss'
})
export class TractiveAccountComponent implements OnInit {
  private readonly tractiveService = inject(TractiveService);

  readonly connected = signal(false);
  readonly connectionChecked = signal(false);
  readonly connectedEmail = signal<string | null>(null);
  readonly tokenExpiresAt = signal<string | null>(null);
  readonly authError = signal<string | null>(null);
  readonly authBusy = signal(false);

  email = '';
  password = '';

  ngOnInit(): void {
    this.tractiveService.getStatus().subscribe({
      next: status => this.applyStatus(status),
      error: () => {
        this.connectionChecked.set(true);
        this.authError.set('Verbindungszustand konnte nicht geladen werden.');
      }
    });
  }

  login(): void {
    this.authBusy.set(true);
    this.authError.set(null);
    this.tractiveService.login(this.email, this.password).subscribe({
      next: status => {
        this.password = '';
        this.authBusy.set(false);
        this.applyStatus(status);
      },
      // 401 = falsche Zugangsdaten; alles andere heißt, die Tractive-Cloud ist nicht erreichbar.
      error: (err: HttpErrorResponse) => {
        this.password = '';
        this.authBusy.set(false);
        this.authError.set(err.status === 401
          ? 'Anmeldung fehlgeschlagen. Bitte Zugangsdaten prüfen.'
          : 'Tractive ist derzeit nicht erreichbar. Bitte später erneut versuchen.');
      }
    });
  }

  logout(): void {
    this.authBusy.set(true);
    this.authError.set(null);
    this.tractiveService.logout().subscribe({
      next: () => {
        this.authBusy.set(false);
        this.connected.set(false);
        this.connectedEmail.set(null);
        this.tokenExpiresAt.set(null);
      },
      error: () => {
        this.authBusy.set(false);
        this.authError.set('Abmelden fehlgeschlagen.');
      }
    });
  }

  private applyStatus(status: TractiveAuthStatus): void {
    this.connectionChecked.set(true);
    this.connected.set(status.authenticated);
    this.connectedEmail.set(status.email ?? null);
    this.tokenExpiresAt.set(status.expiresAt ?? null);
  }
}
