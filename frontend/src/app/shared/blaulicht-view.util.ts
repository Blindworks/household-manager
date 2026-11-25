import { BlaulichtBriefing, BlaulichtCategory, BlaulichtEvent } from '../models/blaulicht.model';
import { HubInsight } from './hub-insight.model';

/** Einzige Frontend-Definition von Beschriftung und Symbol je Kategorie (Reihenfolge = Filterleiste). */
export const BLAULICHT_CATEGORIES: ReadonlyArray<{ key: BlaulichtCategory; label: string; icon: string }> = [
  { key: 'EINBRUCH', label: 'Einbruch', icon: 'lock_open' },
  { key: 'BRAND', label: 'Brand', icon: 'local_fire_department' },
  { key: 'UNFALL', label: 'Unfall', icon: 'car_crash' },
  { key: 'DIEBSTAHL', label: 'Diebstahl', icon: 'back_hand' },
  { key: 'WARNUNG', label: 'Warnung', icon: 'warning' },
  { key: 'SONSTIGES', label: 'Sonstiges', icon: 'info' }
];

export function categoryIcon(category: BlaulichtCategory): string {
  return BLAULICHT_CATEGORIES.find(c => c.key === category)?.icon ?? 'info';
}

export function categoryLabel(category: BlaulichtCategory): string {
  return BLAULICHT_CATEGORIES.find(c => c.key === category)?.label ?? category;
}

/**
 * Hub-Karte „Blaulicht“: das Morgen-Briefing (ab Nachtende bis abends), ab dem Nachtrag-Zeitpunkt
 * ergänzt um den Nachtrag. Eine ruhige Nacht wird bewusst auch gezeigt — „ruhig“ ist die Auskunft.
 */
export function buildBlaulichtInsight(briefing: BlaulichtBriefing | null): HubInsight | null {
  if (!briefing || !briefing.cardVisible) {
    return null;
  }
  const warning = briefing.events.some(e => e.category === 'WARNUNG' && !e.nearby);
  const quiet = briefing.mainCount === 0 && !briefing.addendumVisible;
  const text = briefing.addendumVisible && briefing.addendumText
    ? `${briefing.text} ${briefing.addendumText}`
    : briefing.text;
  return {
    icon: warning ? 'warning' : 'local_police',
    tone: warning ? 'error' : quiet ? 'muted' : 'tertiary',
    title: 'Blaulicht Bad Vilbel',
    text,
    tapAction: 'blaulicht'
  };
}

const TIME = new Intl.DateTimeFormat('de-DE', { hour: '2-digit', minute: '2-digit' });
const DAY = new Intl.DateTimeFormat('de-DE', { weekday: 'short', day: '2-digit', month: '2-digit' });

function parse(value: string): Date {
  return new Date(value.length === 16 ? `${value}:00` : value);
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

function dayLabel(date: Date, now: Date): string {
  if (sameDay(date, now)) {
    return 'heute';
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  return sameDay(date, yesterday) ? 'gestern' : DAY.format(date);
}

/**
 * Zeitangabe einer Meldung: Tatzeit („gestern 21:50“, „Mo., 16.11. 18:00 – So., 23.11. 14:00“),
 * ohne Tatzeit die Veröffentlichung („gemeldet heute 14:12“).
 */
export function formatEventTime(event: BlaulichtEvent, now: Date = new Date()): string {
  if (!event.occurredAt) {
    const published = parse(event.publishedAt);
    return `gemeldet ${dayLabel(published, now)} ${TIME.format(published)}`;
  }
  const from = parse(event.occurredAt);
  const start = `${dayLabel(from, now)} ${TIME.format(from)}`;
  if (!event.occurredUntil) {
    return start;
  }
  const until = parse(event.occurredUntil);
  return sameDay(from, until)
    ? `${start}–${TIME.format(until)}`
    : `${start} – ${dayLabel(until, now)} ${TIME.format(until)}`;
}
