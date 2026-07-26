import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss'
})
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  username = '';
  password = '';
  errorMessage: string | null = null;
  isLoading = false;

  /**
   * Holt vorab das XSRF-TOKEN-Cookie. Die Login-Route ist die einzige ohne
   * Guard — ohne diesen GET gaebe es vor dem Login-POST kein Cookie und
   * Spring lehnte ihn mit 403 ab.
   */
  ngOnInit(): void {
    this.auth.primeCsrfToken().subscribe();
  }

  submit(): void {
    if (!this.username || !this.password || this.isLoading) {
      return;
    }
    this.isLoading = true;
    this.errorMessage = null;
    this.auth.login({ username: this.username, password: this.password }).subscribe({
      next: user => {
        if (user.mustChangePassword) {
          this.router.navigate(['/change-password']);
          return;
        }
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
        this.router.navigateByUrl(returnUrl);
      },
      error: (e: Error) => {
        this.errorMessage = e.message;
        this.isLoading = false;
      }
    });
  }
}
