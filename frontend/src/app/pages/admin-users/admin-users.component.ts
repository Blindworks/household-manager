import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { UserAdminService, CreateUserRequest } from '../../services/user-admin.service';
import { AppUser, UserRole } from '../../models/auth.model';

const MIN_PASSWORD_LENGTH = 8;
const PASSWORD_HINT = `Das Passwort muss mindestens ${MIN_PASSWORD_LENGTH} Zeichen lang sein.`;

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-users.component.html',
  styleUrl: './admin-users.component.scss'
})
export class AdminUsersComponent implements OnInit {
  private readonly userAdminService = inject(UserAdminService);

  readonly roles: UserRole[] = ['ADMIN', 'MEMBER', 'KIOSK'];
  users: AppUser[] = [];
  form: CreateUserRequest = { username: '', displayName: '', password: '', role: 'MEMBER', wallTablet: false };
  errorMessage: string | null = null;
  successMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.userAdminService.getUsers().subscribe({
      next: users => (this.users = users),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  create(): void {
    this.clearMessages();
    const request = {
      ...this.form,
      username: this.form.username.trim(),
      displayName: this.form.displayName.trim()
    };
    const problem = this.createProblem(request);
    if (problem) {
      this.errorMessage = problem;
      return;
    }
    this.userAdminService.createUser(request).subscribe({
      next: user => {
        this.successMessage = `Benutzer „${user.username}“ wurde angelegt.`;
        this.form = { username: '', displayName: '', password: '', role: 'MEMBER', wallTablet: false };
        this.users = [...this.users, user];
      },
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  save(user: AppUser): void {
    this.clearMessages();
    const displayName = user.displayName.trim();
    if (!displayName) {
      this.errorMessage = 'Bitte einen Anzeigenamen eingeben.';
      return;
    }
    this.userAdminService.updateUser(user.id, {
      displayName, role: user.role, enabled: user.enabled,
      wallTablet: user.wallTablet
    }).subscribe({
      next: saved => {
        this.successMessage = `Benutzer „${saved.username}“ wurde gespeichert.`;
        // Nur diese Zeile ersetzen: ein Neuladen verwürfe ungespeicherte Eingaben der anderen.
        this.users = this.users.map(u => (u.id === saved.id ? saved : u));
      },
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  changePassword(user: AppUser): void {
    const password = prompt(`Neues Passwort für ${user.username} (min. 8 Zeichen):`);
    if (password === null) {
      return;
    }
    this.clearMessages();
    if (!this.isValidPassword(password)) {
      this.errorMessage = PASSWORD_HINT;
      return;
    }
    this.userAdminService.setPassword(user.id, password).subscribe({
      next: () => (this.successMessage = `Passwort von „${user.username}“ wurde geändert.`),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  private createProblem(request: CreateUserRequest): string | null {
    if (!request.username) {
      return 'Bitte einen Benutzernamen eingeben.';
    }
    if (!request.displayName) {
      return 'Bitte einen Anzeigenamen eingeben.';
    }
    return this.isValidPassword(request.password) ? null : PASSWORD_HINT;
  }

  private isValidPassword(password: string): boolean {
    return password.length >= MIN_PASSWORD_LENGTH && password.trim().length > 0;
  }

  private clearMessages(): void {
    this.errorMessage = null;
    this.successMessage = null;
  }
}
