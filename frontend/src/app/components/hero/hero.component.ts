import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';

/**
 * Hero section component for the landing page.
 * Displays welcome message and call-to-action buttons.
 */
@Component({
  selector: 'app-hero',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './hero.component.html',
  styleUrl: './hero.component.scss'
})
export class HeroComponent {
  /** Application title for the hero section */
  readonly title: string = 'Willkommen beim Household Manager';

  /** Hero subtitle/description */
  readonly subtitle: string = 'Verwalten Sie Ihre Haushaltszählerstände digital und behalten Sie Ihren Verbrauch im Blick';

  /** Description text for the hero section */
  readonly description: string = 'Mit dem Household Manager erfassen Sie spielend leicht Ihre Strom-, Gas- und Wasserzählerstände und erhalten wertvolle Einblicke in Ihren Verbrauch. Perfekt für Mieter und Eigenheimbesitzer.';
}
