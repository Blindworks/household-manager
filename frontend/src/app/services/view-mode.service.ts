import { Injectable, signal } from '@angular/core';

/**
 * Verwaltet den globalen Ansichtsmodus der Anwendung.
 *
 * Im Tablet-Modus wird die obere Navigationsleiste ausgeblendet, damit das
 * Dashboard als aufgeraeumte Wand-/Tablet-Ansicht genutzt werden kann. Der
 * Zustand wird in localStorage gespeichert und ueberlebt so einen Reload.
 */
@Injectable({ providedIn: 'root' })
export class ViewModeService {
  private static readonly STORAGE_KEY = 'household-manager-view-mode';

  /** True, wenn die menuelose Tablet-Ansicht aktiv ist. */
  readonly isTabletView = signal<boolean>(this.loadTabletView());

  /** Schaltet zwischen Website- und Tablet-Ansicht um. */
  toggle(): void {
    this.isTabletView.update(value => {
      const next = !value;
      this.persist(next);
      return next;
    });
  }

  private loadTabletView(): boolean {
    try {
      return localStorage.getItem(ViewModeService.STORAGE_KEY) === 'tablet';
    } catch {
      return false;
    }
  }

  private persist(isTablet: boolean): void {
    try {
      localStorage.setItem(ViewModeService.STORAGE_KEY, isTablet ? 'tablet' : 'website');
    } catch {
      // localStorage nicht verfuegbar (z. B. privater Modus) – Zustand bleibt nur im Speicher
    }
  }
}
