import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-change-password',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './change-password.component.html',
  styleUrl: './change-password.component.scss'
})
export class ChangePasswordComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  currentPassword = '';
  newPassword = '';
  repeatPassword = '';
  errorMessage: string | null = null;
  isLoading = false;

  /** true, wenn der Wechsel erzwungen ist (dann gibt es kein Zurueck ohne Wechsel). */
  get forced(): boolean {
    return this.auth.currentUser()?.mustChangePassword ?? false;
  }

  get valid(): boolean {
    return this.currentPassword.length > 0
      && this.newPassword.length >= 8
      && this.newPassword === this.repeatPassword;
  }

  submit(): void {
    if (!this.valid || this.isLoading) {
      return;
    }
    if (this.newPassword === this.currentPassword) {
      this.errorMessage = 'Das neue Passwort muss sich vom aktuellen unterscheiden.';
      return;
    }
    this.isLoading = true;
    this.errorMessage = null;
    this.auth.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => this.router.navigate(['/']),
      error: (e: Error) => {
        this.errorMessage = e.message;
        this.isLoading = false;
      }
    });
  }
}
