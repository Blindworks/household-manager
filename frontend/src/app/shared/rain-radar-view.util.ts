import { EntityState } from '../models/entity-state.model';

/** Entität des Regenradars, die alle Attribute trägt (Backend `RainRadarPollingService`). */
export const RAIN_RADAR_INTENSITY_ENTITY_ID = 'sensor.rain_radar_intensity';

/** Ab hier gilt der Regen als „gleich“ (Gegenstück zu `rain-radar.soon-minutes` im Backend). */
const SOON_MINUTES = 15;
/** Älter als eine Radarmessung sein darf, bevor die Anzeige abgedunkelt wird. */
const STALE_AFTER_MS = 30 * 60 * 1000;

export type RainRadarTone = 'dry' | 'later' | 'soon' | 'raining';

/** Was die Kopfzeile zum Regenradar zeigt. */
export interface RainRadarView {
  readonly tone: RainRadarTone;
  readonly icon: string;
  /** Große Zeile, z. B. „trocken“, „in 12 Min.“, „1,2 mm/h“. */
  readonly value: string;
  /** Kleine Zeile darunter. */
  readonly label: string;
  /** Tooltip mit Messzeit. */
  readonly title: string;
  readonly stale: boolean;
}

/**
 * Einzige Frontend-Definition der Regenradar-Anzeige. Liest `sensor.rain_radar_intensity`;
 * fehlt sie oder ist sie `unavailable`, gibt es keine Anzeige (nie ein geratenes „trocken“).
 * Die Minuten bis zum Regen werden aus `expectedRainAt` gegen `now` gerechnet, nicht aus
 * `minutesUntilRain` — das Attribut ist bis zu 5 Minuten alt.
 */
export function buildRainRadarView(entities: EntityState[], now: number): RainRadarView | null {
  const entity = entities.find(e => e.entityId === RAIN_RADAR_INTENSITY_ENTITY_ID);
  if (!entity || entity.state === 'unavailable' || entity.state === 'unknown') {
    return null;
  }
  const mmPerHour = Number(entity.state);
  if (!Number.isFinite(mmPerHour)) {
    return null;
  }
  const threshold = numberAttribute(entity, 'thresholdMmPerHour') ?? 0.3;
  const measuredAt = parseLocal(entity.attributes['measuredAt']);
  const stale = measuredAt === null || now - measuredAt > STALE_AFTER_MS;
  const title = measuredAt === null ? 'DWD-Regenradar' : `DWD-Regenradar, Messung ${formatClock(measuredAt)} Uhr`;

  if (mmPerHour >= threshold) {
    return { tone: 'raining', icon: 'rainy', value: `${formatMm(mmPerHour)} mm/h`, label: 'Regen am Haus', title, stale };
  }
  const expectedAt = parseLocal(entity.attributes['expectedRainAt']);
  if (expectedAt !== null) {
    const minutes = Math.max(0, Math.round((expectedAt - now) / 60000));
    const value = minutes === 0 ? 'jetzt' : minutes <= 60 ? `in ${minutes} Min.` : `ab ${formatClock(expectedAt)}`;
    return {
      tone: minutes <= SOON_MINUTES ? 'soon' : 'later',
      icon: 'rainy',
      value,
      label: 'Regen erwartet',
      title,
      stale
    };
  }
  return { tone: 'dry', icon: 'umbrella', value: 'trocken', label: 'Radar · 2 h', title, stale };
}

function numberAttribute(entity: EntityState, key: string): number | null {
  const value = entity.attributes[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Backend liefert lokale Haushaltszeit ohne Zone (`yyyy-MM-ddTHH:mm:ss`), Browser und Tablet laufen in derselben Zone. */
function parseLocal(value: unknown): number | null {
  if (typeof value !== 'string') {
    return null;
  }
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? null : time;
}

function formatClock(time: number): string {
  const date = new Date(time);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function formatMm(value: number): string {
  return value.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}
