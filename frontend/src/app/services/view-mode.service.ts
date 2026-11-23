import { Injectable, computed, signal } from '@angular/core';

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

  /**
   * Einzige Regel fuer Waesche-Dialog und Toni-allein-Screen: nur beim
   * Wandtablet-Konto UND in der Tablet-Ansicht — ein Handy mit
   * eingeschalteter Tablet-Ansicht sieht beides nie.
   */
  readonly showWallTabletOverlays = computed(() => this.wallTabletAccount() && this.isTabletView());

  /**
   * Wandtablet-Merkmal des angemeldeten Kontos. Gesetzt vom AuthService
   * (diese Richtung, damit der Ansichtsmodus ohne HttpClient auskommt).
   */
  private readonly wallTabletAccount = signal(false);

  /**
   * Uebernimmt das Merkmal. Ein Wandtablet-Konto startet in der
   * Tablet-Ansicht, solange der Browser keine eigene Wahl gespeichert hat —
   * bewusst nicht persistiert, eine spaetere Wahl ueber den Umschalter gewinnt.
   */
  setWallTabletAccount(wallTablet: boolean): void {
    this.wallTabletAccount.set(wallTablet);
    if (this.hasStoredChoice()) {
      this.autoTabletView = false;
      return;
    }
    if (wallTablet) {
      this.autoTabletView = this.autoTabletView || !this.isTabletView();
      this.isTabletView.set(true);
    } else if (this.autoTabletView) {
      // Abmelden des Wandtablet-Kontos: die nur automatisch gesetzte Ansicht zuruecknehmen
      this.autoTabletView = false;
      this.isTabletView.set(false);
    }
  }

  /** true, solange die Tablet-Ansicht nur durch das Wandtablet-Konto (nicht per Umschalter) aktiv ist. */
  private autoTabletView = false;

  /** Schaltet zwischen Website- und Tablet-Ansicht um. */
  toggle(): void {
    this.isTabletView.update(value => {
      const next = !value;
      this.persist(next);
      return next;
    });
  }

  private hasStoredChoice(): boolean {
    try {
      return localStorage.getItem(ViewModeService.STORAGE_KEY) !== null;
    } catch {
      return false;
    }
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
