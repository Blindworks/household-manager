import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ServiceTokenAdminService } from '../../services/service-token-admin.service';
import { ServiceTokenInfo, UserRole } from '../../models/auth.model';

@Component({
  selector: 'app-admin-service-tokens',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-service-tokens.component.html',
  styleUrl: './admin-service-tokens.component.scss'
})
export class AdminServiceTokensComponent implements OnInit {
  private readonly tokenService = inject(ServiceTokenAdminService);

  readonly roles: UserRole[] = ['ADMIN', 'MEMBER', 'KIOSK'];
  tokens: ServiceTokenInfo[] = [];
  name = '';
  role: UserRole = 'KIOSK';
  createdToken: string | null = null;
  errorMessage: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.tokenService.getTokens().subscribe({
      next: tokens => (this.tokens = tokens),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  create(): void {
    if (!this.name) {
      return;
    }
    this.tokenService.createToken(this.name, this.role).subscribe({
      next: created => {
        this.createdToken = created.token;
        this.name = '';
        this.load();
      },
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }

  revoke(token: ServiceTokenInfo): void {
    if (!confirm(`Token "${token.name}" widerrufen? Der Client verliert sofort den Zugriff.`)) {
      return;
    }
    this.tokenService.revokeToken(token.id).subscribe({
      next: () => this.load(),
      error: (e: Error) => (this.errorMessage = e.message)
    });
  }
}
