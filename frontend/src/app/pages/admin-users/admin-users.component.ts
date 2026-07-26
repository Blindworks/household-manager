import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { UserAdminService, CreateUserRequest } from '../../services/user-admin.service';
import { AppUser, UserRole } from '../../models/auth.model';

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
  form: CreateUserRequest = { username: '', displayName: '', password: '', role: 'MEMBER' };
  errorMessage: string | null = null;

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
    if (!this.form.username || !this.form.displayName || this.form.password.length < 8) {
      return;
    }
    this.userAdminService.createUser(this.form).subscribe({
      next: () => {
        this.form = { username: '', displayName: '', password: '', role: 'MEMBER' };
        this.load();
      },
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  save(user: AppUser): void {
    this.userAdminService.updateUser(user.id, {
      displayName: user.displayName, role: user.role, enabled: user.enabled
    }).subscribe({
      next: () => this.load(),
      error: (e: Error) => { this.errorMessage = e.message; this.load(); }
    });
  }

  changePassword(user: AppUser): void {
    const password = prompt(`Neues Passwort für ${user.username} (min. 8 Zeichen):`);
    if (!password || password.length < 8) {
      return;
    }
    this.userAdminService.setPassword(user.id, password).subscribe({
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }
}
