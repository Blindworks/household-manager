import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { AuthService } from '../../services/auth.service';

interface NavLink {
  path: string;
  label: string;
  exact?: boolean;
  minRole?: 'MEMBER' | 'ADMIN';
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
    { path: '/calendar', label: 'Kalender' },
    {
      path: '/energy',
      label: 'Energie',
      children: [
        { path: '/energy', label: 'Uebersicht', exact: true },
        { path: '/energy/battery-control', label: 'Akku Steuerung' },
        { path: '/energy/history', label: 'Energieverlauf' }
      ]
    },
    {
      path: '/environment',
      label: 'Umwelt',
      children: [
        { path: '/air-quality', label: 'Luftqualitaet' },
        { path: '/weather', label: 'Wetter' },
        { path: '/temperatures', label: 'Temperaturen' },
        { path: '/waste-collection', label: 'Muellabfuhr' }
      ]
    },
    {
      path: '/smart-home',
      label: 'Smart Home',
      children: [
        { path: '/zigbee', label: 'Zigbee-Sensoren' },
        { path: '/pets', label: 'Hundetracker' },
        { path: '/pet-food', label: 'Futtervorrat' },
        { path: '/notifications', label: 'Benachrichtigungen' },
        { path: '/devices', label: 'Geraete' },
        { path: '/entities', label: 'Entitaeten' },
        { path: '/custom-entities', label: 'Eigene Helfer' }
      ]
    },
    {
      path: '/admin',
      label: 'Admin',
      children: [
        { path: '/admin', label: 'Uebersicht', exact: true, minRole: 'ADMIN' },
        { path: '/flows', label: 'Automatisierungen', minRole: 'ADMIN' },
        { path: '/announcements', label: 'Ansagen', minRole: 'MEMBER' },
        { path: '/vision', label: 'Gesichtserkennung', minRole: 'ADMIN' },
        { path: '/admin/users', label: 'Nutzer', minRole: 'ADMIN' },
        { path: '/admin/service-tokens', label: 'API-Tokens', minRole: 'ADMIN' },
        { path: '/admin/audit-log', label: 'Audit-Log', minRole: 'ADMIN' },
        { path: '/admin/calendar-categories', label: 'Kalender-Kategorien', minRole: 'ADMIN' },
        { path: '/admin/tractive', label: 'Hundetracker-Einstellungen', minRole: 'ADMIN' }
      ]
    },
    {
      path: '/finance',
      label: 'Ausgaben',
      minRole: 'MEMBER',
      children: [
        { path: '/finance', label: 'Uebersicht', exact: true },
        { path: '/finance/transactions', label: 'Transaktionen' },
        { path: '/finance/recurring', label: 'Wiederkehrende' },
        { path: '/finance/budgets', label: 'Budgets' },
        { path: '/finance/categories', label: 'Kategorien' },
        { path: '/finance/rules', label: 'Regeln' },
        { path: '/finance/accounts', label: 'Konten' }
      ]
    },
  ];

  /** Public: das Template filtert die Navigation nach der aktuellen Rolle. */
  readonly auth = inject(AuthService);

  /** Navigationseintraege, gefiltert nach der aktuellen Rolle; leere Gruppen verschwinden. */
  readonly visibleNavLinks = computed(() => this.navLinks
    .map(link => ({
      ...link,
      children: link.children?.filter(child => this.allows(child.minRole ?? link.minRole))
    }))
    .filter(link => this.allows(link.minRole)
      && (link.children === undefined || link.children.length > 0)));

  /** Signal to track mobile menu open/closed state */
  isMobileMenuOpen = signal<boolean>(false);

  /** Signal to track which submenu is expanded */
  expandedMenu = signal<string | null>(null);

  constructor(private readonly router: Router) {}

  private allows(minRole?: 'MEMBER' | 'ADMIN'): boolean {
    if (!minRole) {
      return true;
    }
    return minRole === 'ADMIN' ? this.auth.isAdmin() : this.auth.isMember();
  }

  logout(): void {
    this.closeMobileMenu();
    this.auth.logout().subscribe(() => this.router.navigate(['/login']));
  }

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
   * Collapses any expanded submenu (used when navigating via a sub-link).
   */
  closeSubmenu(): void {
    this.expandedMenu.set(null);
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
