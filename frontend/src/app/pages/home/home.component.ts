import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';

/**
 * Legacy home component - redirects to dashboard.
 * @deprecated Use DashboardComponent instead. This component exists for backwards compatibility only.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [],
  template: '<div>Redirecting to dashboard...</div>',
  styles: ['div { text-align: center; padding: 2rem; }']
})
export class HomeComponent implements OnInit {
  constructor(private router: Router) {}

  ngOnInit(): void {
    // Redirect to dashboard immediately
    this.router.navigate(['/']);
  }
}
