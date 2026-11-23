import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { AuthService } from '../../services/auth.service';
import {
  NAV_ENTRIES, MinRole, VisibleNavEntry, VisibleNavGroup, isNavGroup, isPathInGroup, toSections
} from './nav-entries';

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
  /** Public: das Template filtert die Navigation nach der aktuellen Rolle. */
  readonly auth = inject(AuthService);

  /** Menüeinträge, gefiltert nach der aktuellen Rolle; leere Gruppen verschwinden. */
  readonly visibleNavEntries = computed<VisibleNavEntry[]>(() => NAV_ENTRIES
    .filter(entry => this.allows(entry.minRole))
    .map((entry): VisibleNavEntry | null => {
      if (!isNavGroup(entry)) {
        return { kind: 'link', link: entry };
      }
      const children = entry.children.filter(child => this.allows(child.minRole ?? entry.minRole));
      return children.length === 0 ? null : {
        kind: 'group',
        group: {
          key: entry.key,
          label: entry.label,
          sections: toSections(children),
          paths: children.map(child => child.path)
        }
      };
    })
    .filter((entry): entry is VisibleNavEntry => entry !== null));

  /** Signal to track mobile menu open/closed state */
  isMobileMenuOpen = signal<boolean>(false);

  /** Signal to track which submenu is expanded */
  expandedMenu = signal<string | null>(null);

  constructor(private readonly router: Router) {}

  private allows(minRole?: MinRole): boolean {
    if (!minRole) {
      return true;
    }
    return minRole === 'ADMIN' ? this.auth.isAdmin() : this.auth.isMember();
  }

  logout(): void {
    this.closeMobileMenu();
    this.auth.logout().subscribe(() => this.router.navigate(['/login']));
  }

  toggleMobileMenu(): void {
    this.isMobileMenuOpen.update(value => !value);
  }

  closeMobileMenu(): void {
    this.isMobileMenuOpen.set(false);
  }

  toggleSubmenu(key: string): void {
    this.expandedMenu.update(current => current === key ? null : key);
  }

  closeSubmenu(): void {
    this.expandedMenu.set(null);
  }

  isSubmenuExpanded(key: string): boolean {
    return this.expandedMenu() === key;
  }

  /** Eine Gruppe gilt als aktiv, wenn die aktuelle Seite zu einem ihrer Einträge gehört. */
  isGroupActive(group: VisibleNavGroup): boolean {
    return isPathInGroup(this.router.url, group.paths);
  }
}
