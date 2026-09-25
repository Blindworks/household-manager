export const COLLAPSED_SECTIONS_KEY = 'flows.collapsedSections';

/**
 * Zugeklappte Abschnitte der Flow-Übersicht. Reine Komfortfunktion: ein gesperrter
 * oder kaputter Storage ergibt „alles aufgeklappt", nie einen Fehler.
 */
export function loadCollapsedSections(): Set<string> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(COLLAPSED_SECTIONS_KEY) ?? '[]');
    return new Set(Array.isArray(parsed) ? parsed.filter((t): t is string => typeof t === 'string') : []);
  } catch {
    return new Set();
  }
}

export function saveCollapsedSections(titles: Set<string>): void {
  try {
    localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify([...titles]));
  } catch {
    // bewusst geschluckt, siehe loadCollapsedSections
  }
}
