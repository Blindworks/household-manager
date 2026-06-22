import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';

interface NavLink {
  path: string;
  label: string;
  exact?: boolean;
  children?: NavLink[];
}

/**
 * Header component for the Household Manager application.
 * Displays navigation menu and responsive mobile menu toggle.
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive, IconComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss'
})
export class HeaderComponent {
  readonly navLinks: NavLink[] = [
    { path: '/', label: 'Home', exact: true },
    { path: '/meter-readings', label: 'Zaehlerstaende' },
    { path: '/consumption', label: 'Verbrauch' },
    {
      path: '/energy',
      label: 'Energie',
      children: [
        { path: '/energy', label: 'Uebersicht', exact: true },
        { path: '/energy/battery-control', label: 'Akku Steuerung' },
        { path: '/energy/history', label: 'Energieverlauf' }
      ]
    },
    { path: '/air-quality', label: 'Luftqualitaet' },
    { path: '/weather', label: 'Wetter' },
    { path: '/zigbee', label: 'Zigbee-Sensoren' },
    { path: '/devices', label: 'Geraete' },
    { path: '/admin', label: 'Admin' }
  ];

  /** Signal to track mobile menu open/closed state */
  isMobileMenuOpen = signal<boolean>(false);

  /** Signal to track which submenu is expanded */
  expandedMenu = signal<string | null>(null);

  constructor(private readonly router: Router) {}

  /**
   * Toggles the mobile menu visibility.
   */
  toggleMobileMenu(): void {
    this.isMobileMenuOpen.update(value => !value);
  }

  /**
   * Closes the mobile menu (used when navigating).
   */
  closeMobileMenu(): void {
    this.isMobileMenuOpen.set(false);
  }

  /**
   * Toggles a submenu open/closed.
   */
  toggleSubmenu(path: string): void {
    this.expandedMenu.update(current => current === path ? null : path);
  }

  /**
   * Checks if a submenu is currently expanded.
   */
  isSubmenuExpanded(path: string): boolean {
    return this.expandedMenu() === path;
  }

  /**
   * Checks if any child route of a parent link is currently active.
   */
  isParentActive(link: NavLink): boolean {
    if (!link.children) {
      return false;
    }
    return this.router.url.startsWith(link.path);
  }
}
