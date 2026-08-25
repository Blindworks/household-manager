import { TimeValue } from '../../models/network.model';

/**
 * Reine Hilfsfunktionen der Tablet-Netzwerkansicht. Bewusst NICHT in shared/,
 * weil sie ausschliesslich von dieser einen Komponente gebraucht werden - anders
 * als z. B. walk-format.util.ts, das Dashboard-Dialog UND Tablet-Ansicht teilen.
 */

/** Ein Zeit/Wert-Punkt, dessen Wert eine Luecke markieren kann (null). */
export interface GapAwareValue {
  time: string;
  value: number | null;
}

/**
 * Fuegt vor dem Zeichnen null-Punkte ein, wo der Abstand zweier Nachbarpunkte
 * ueber dem Dreifachen der Bucket-Laenge des Zeitraums liegt (strikt groesser -
 * genau die dreifache Bucket-Laenge zaehlt noch als normaler Abstand und bricht
 * die Linie NICHT) - so entsteht bei einem Offline-Fenster eine echte Luecke im
 * Diagramm (`connectNulls: false`) statt einer irrefuehrenden geraden
 * Verbindungslinie ueber die Ausfallzeit.
 */
export function insertGaps(points: TimeValue[], bucketMinutes: number): GapAwareValue[] {
  if (points.length === 0) {
    return [];
  }
  const maxGapMs = bucketMinutes * 3 * 60_000;
  const result: GapAwareValue[] = [{ time: points[0].time, value: points[0].value }];

  for (let i = 1; i < points.length; i++) {
    const previous = points[i - 1];
    const current = points[i];
    const gapMs = new Date(current.time).getTime() - new Date(previous.time).getTime();
    if (gapMs > maxGapMs) {
      // Zeitpunkt der Luecke ist beliebig, solange er strikt zwischen den beiden
      // Nachbarn liegt - er bricht die Linie, mehr verlangt die Zeitachse nicht.
      const gapTime = new Date(new Date(previous.time).getTime() + gapMs / 2).toISOString();
      result.push({ time: gapTime, value: null });
    }
    result.push({ time: current.time, value: current.value });
  }
  return result;
}

/**
 * Kurze relative Zeitangabe fuer die Geraetekachel: heute nur die Uhrzeit,
 * sonst das Datum. Ohne Zeitstempel (Geraet nie gesehen) ein Gedankenstrich.
 */
export function formatLastSeen(iso: string | null, now: Date = new Date()): string {
  if (!iso) {
    return '–';
  }
  const date = new Date(iso);
  const sameDay = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  return sameDay
    ? date.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString('de-DE', { day: 'numeric', month: 'numeric' });
}
