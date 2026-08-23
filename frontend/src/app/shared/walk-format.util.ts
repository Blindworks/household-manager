import { TractiveWalk } from '../models/tractive.model';

/**
 * Darstellung von Spaziergaengen. Einzige Definition - der Walks-Dialog des
 * Dashboards und die Tablet-Ansicht fragen dieselben Funktionen, damit dieselbe
 * Runde nicht an einer Stelle "1,4 km" und an der anderen "1400 m" heisst.
 *
 * Bewusst reine Funktionen ohne Angular-Bezug statt einer gemeinsamen
 * Kind-Komponente: die lumina-Styles des Dashboards sind in dessen SCSS
 * gekapselt und erreichen ein Kind nicht - es renderte lautlos ungestylt.
 */

/** Ein Tagesbalken des Spaziergangs-Diagramms. */
export interface WalkDayTotal {
  /** Kurzes Etikett fuer die Achse, z. B. "22.8.". */
  label: string;
  /** Summe der Gehdauer dieses Tages in Minuten; 0 an Tagen ohne Runde. */
  minutes: number;
}

export function walkTimeRange(walk: TractiveWalk): string {
  const format = (iso: string) =>
    new Date(iso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  return `${format(walk.start)}–${format(walk.end)} Uhr`;
}

export function walkDuration(walk: TractiveWalk): string {
  const hours = Math.floor(walk.durationMinutes / 60);
  const minutes = walk.durationMinutes % 60;
  return hours > 0 ? `${hours} h ${minutes} min` : `${minutes} min`;
}

export function walkDistance(walk: TractiveWalk): string {
  return walk.distanceMeters >= 1000
    ? `${(walk.distanceMeters / 1000).toFixed(1).replace('.', ',')} km`
    : `${Math.round(walk.distanceMeters)} m`;
}

/** Gruppiert nach Kalendertag; die Reihenfolge (neueste zuerst) kommt vom Server. */
export function groupWalksByDay(walks: TractiveWalk[]): { label: string; walks: TractiveWalk[] }[] {
  const groups: { label: string; walks: TractiveWalk[] }[] = [];
  for (const walk of walks) {
    const label = new Date(walk.start).toLocaleDateString('de-DE', {
      weekday: 'long', day: 'numeric', month: 'long'
    });
    const last = groups[groups.length - 1];
    if (last && last.label === label) {
      last.walks.push(walk);
    } else {
      groups.push({ label, walks: [walk] });
    }
  }
  return groups;
}

/** Kurzes Tagesetikett fuer eine einzelne Runde: "Heute", "Gestern" oder "Di, 18.8.". */
export function walkDayLabel(iso: string, now: Date = new Date()): string {
  const start = new Date(iso);
  if (dayKey(start) === dayKey(now)) {
    return 'Heute';
  }
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (dayKey(start) === dayKey(yesterday)) {
    return 'Gestern';
  }
  return start.toLocaleDateString('de-DE', { weekday: 'short', day: 'numeric', month: 'numeric' });
}

/**
 * Ein Balken je Kalendertag des Zeitraums, aeltester zuerst. Tage ohne Runde
 * bekommen eine 0 statt gar keinen Eintrag - eine Luecke im Diagramm saehe aus
 * wie ein Datenloch, eine leere Saeule ist eine Aussage.
 */
export function walkDayTotals(walks: TractiveWalk[], days: number,
                              now: Date = new Date()): WalkDayTotal[] {
  const minutesByDay = new Map<string, number>();
  for (const walk of walks) {
    const key = dayKey(new Date(walk.start));
    minutesByDay.set(key, (minutesByDay.get(key) ?? 0) + walk.durationMinutes);
  }

  const totals: WalkDayTotal[] = [];
  for (let offset = days - 1; offset >= 0; offset--) {
    const day = new Date(now);
    day.setDate(day.getDate() - offset);
    totals.push({
      label: day.toLocaleDateString('de-DE', { day: 'numeric', month: 'numeric' }),
      minutes: minutesByDay.get(dayKey(day)) ?? 0
    });
  }
  return totals;
}

/** Kalendertag in lokaler Zeit - bewusst nicht ueber die ISO-Zeichenkette, die in UTC steht. */
function dayKey(date: Date): string {
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}
