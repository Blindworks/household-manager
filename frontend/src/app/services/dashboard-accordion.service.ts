import { Injectable, signal } from '@angular/core';

/** Die drei aufklappbaren Kacheln des Dashboards. */
export type DashboardTileKey = 'climate' | 'switches' | 'consumers';

/**
 * Verwaltet, welche der drei Dashboard-Kacheln in der Tablet-Ansicht
 * aufgeklappt ist.
 *
 * Es ist immer hoechstens eine Kachel offen: auf dem Wandtablet (800 CSS-Pixel
 * Hoehe) bleibt der geoeffnete Inhalt so ohne Scrollen sichtbar. Der Zustand
 * wird wie der Ansichtsmodus in localStorage gehalten und ueberlebt damit einen
 * Reload und den Neustart des Kiosk-Browsers.
 */
@Injectable({ providedIn: 'root' })
export class DashboardAccordionService {
  private static readonly STORAGE_KEY = 'household-manager-dashboard-tile';
  private static readonly TILE_KEYS: readonly DashboardTileKey[] = [
    'climate',
    'switches',
    'consumers'
  ];
  private static readonly DEFAULT_TILE: DashboardTileKey = 'climate';

  /** Die aktuell aufgeklappte Kachel, oder null, wenn alle zu sind. */
  readonly openTile = signal<DashboardTileKey | null>(this.loadOpenTile());

  isOpen(key: DashboardTileKey): boolean {
    return this.openTile() === key;
  }

  /** Klappt die Kachel auf und alle anderen zu; eine offene Kachel schliesst sich. */
  toggle(key: DashboardTileKey): void {
    const next = this.openTile() === key ? null : key;
    this.openTile.set(next);
    this.persist(next);
  }

  private loadOpenTile(): DashboardTileKey | null {
    let stored: string | null;
    try {
      stored = localStorage.getItem(DashboardAccordionService.STORAGE_KEY);
    } catch {
      return DashboardAccordionService.DEFAULT_TILE;
    }

    // Noch nie etwas gespeichert: mit der ersten Kachel offen starten.
    if (stored === null) {
      return DashboardAccordionService.DEFAULT_TILE;
    }
    if (DashboardAccordionService.TILE_KEYS.includes(stored as DashboardTileKey)) {
      return stored as DashboardTileKey;
    }
    // 'none' oder ein unbekannter Wert (z. B. aus einer aelteren Version).
    return null;
  }

  private persist(openTile: DashboardTileKey | null): void {
    try {
      localStorage.setItem(DashboardAccordionService.STORAGE_KEY, openTile ?? 'none');
    } catch {
      // localStorage nicht verfuegbar (z. B. privater Modus) – Zustand bleibt nur im Speicher
    }
  }
}
