export const COLLAPSED_SECTIONS_KEY = 'flows.collapsedSections';

/**
 * Zugeklappte Abschnitte der Flow-Übersicht (und über `key` der Zigbee-Seite). Reine Komfortfunktion: ein gesperrter
 * oder kaputter Storage ergibt „alles aufgeklappt", nie einen Fehler.
 */
export function loadCollapsedSections(key = COLLAPSED_SECTIONS_KEY): Set<string> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(key) ?? '[]');
    return new Set(Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []);
  } catch {
    return new Set();
  }
}

export function saveCollapsedSections(titles: Set<string>, key = COLLAPSED_SECTIONS_KEY): void {
  try {
    localStorage.setItem(key, JSON.stringify([...titles]));
  } catch {
    // bewusst geschluckt, siehe loadCollapsedSections
  }
}
