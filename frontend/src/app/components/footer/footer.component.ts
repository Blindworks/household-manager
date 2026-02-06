import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../shared/components/icon/icon.component';

/**
 * Footer component for the Household Manager application.
 * Displays copyright information and links.
 */
@Component({
  selector: 'app-footer',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './footer.component.html',
  styleUrl: './footer.component.scss'
})
export class FooterComponent {
  /** Current year for copyright notice */
  readonly currentYear: number = new Date().getFullYear();

  /** Application name */
  readonly appName: string = 'Household Manager';
}
