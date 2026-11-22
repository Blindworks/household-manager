import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { VisionService } from '../../../services/vision.service';
import { VisionStatus } from '../../../models/vision.model';

/** Blink-Konto: eine Anmeldung für Gesichtserkennung und Kamera-Dashboard (blink-vision-Sidecar). */
@Component({
  selector: 'app-blink-account',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './blink-account.component.html',
  styleUrl: '../account-login.scss'
})
export class BlinkAccountComponent implements OnInit {
  private readonly visionService = inject(VisionService);

  status: VisionStatus | null = null;
  statusChecked = false;
  loginUsername = '';
  loginPassword = '';
  verifyCode = '';
  loginPending = false;
  errorMessage = '';

  ngOnInit(): void {
    this.reloadStatus();
  }

  reloadStatus(): void {
    this.visionService.getStatus().subscribe({
      next: status => {
        this.status = status;
        this.statusChecked = true;
        // Eine bestätigte Anmeldung macht einen noch offenen 2FA-Schritt hinfällig.
        if (status.loggedIn) {
          this.loginPending = false;
          this.verifyCode = '';
        }
      },
      error: () => {
        this.status = null;
        this.statusChecked = true;
      }
    });
  }

  login(): void {
    this.errorMessage = '';
    // Passwort sofort aus dem Komponenten-State entfernen, nicht erst im Erfolgsfall.
    const password = this.loginPassword;
    this.loginPassword = '';
    this.loginPending = true;
    this.visionService.login(this.loginUsername, password).subscribe({
      next: () => this.reloadStatus(),
      error: err => { this.loginPending = false; this.errorMessage = err.message; }
    });
  }

  verify(): void {
    this.errorMessage = '';
    this.visionService.verify(this.verifyCode).subscribe({
      next: () => { this.loginPending = false; this.verifyCode = ''; this.reloadStatus(); },
      error: err => this.errorMessage = err.message
    });
  }
}
