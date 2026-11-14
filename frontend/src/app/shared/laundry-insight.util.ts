import { LaundryState } from '../models/laundry.model';
import { HubInsight } from './hub-insight.model';
import { relativeDayLabel } from './relative-day.util';

const ICON = 'dry_cleaning';

const HIDDEN_DRYING_KEY = 'laundry.hiddenDryingSession';

/**
 * Hub-Karte fuer die Waesche im Keller. Bei moeglicherweise trockener Waesche
 * (`maybe_dry`) meldet ein Antippen sie als abgenommen. Die Karte der laufenden
 * Trocknung (`drying`) blendet ein Antippen nur aus (`hiddenDryingStartedAt` = Beginn
 * des ausgeblendeten Waschgangs) — sonst endete der Waschgang, und die spaetere Karte
 * „könnte trocken sein“ samt Push kaeme nie.
 */
export function buildLaundryInsight(
  state: LaundryState | null,
  now: Date,
  hiddenDryingStartedAt: string | null = null
): HubInsight | null {
  if (!state) {
    return null;
  }
  if (state.status === 'maybe_dry') {
    return {
      icon: ICON,
      tone: 'tertiary',
      title: 'Wäsche könnte trocken sein',
      text: 'Die Wäsche im Keller könnte jetzt trocken sein.',
      tapAction: 'laundry-removed'
    };
  }
  if (state.status === 'drying') {
    if (hiddenDryingStartedAt !== null && state.startedAt === hiddenDryingStartedAt) {
      return null;
    }
    return {
      icon: ICON,
      tone: 'primary',
      title: 'Wäsche trocknet',
      text: dryingText(state, now),
      tapAction: 'laundry-hide'
    };
  }
  return null;
}

function dryingText(state: LaundryState, now: Date): string {
  if (state.estimatePaused) {
    return 'Schätzung pausiert – Kellerfühler meldet nichts';
  }
  const predicted = state.predictedDryAt ? roundToFullHour(state.predictedDryAt) : null;
  if (!predicted) {
    return 'Voraussichtlich trocken – Schätzung folgt';
  }
  const daysUntil = Math.round(
    (startOfDay(predicted).getTime() - startOfDay(now).getTime()) / 86_400_000);
  const isoDate = `${predicted.getFullYear()}-${pad(predicted.getMonth() + 1)}-${pad(predicted.getDate())}`;
  const label = relativeDayLabel(daysUntil, isoDate);
  const day = daysUntil >= 0 && daysUntil <= 2 ? label.toLowerCase() : label;
  return `Voraussichtlich trocken ${day} gegen ${predicted.getHours()} Uhr`;
}

/** Rundet auf die naechste volle Stunde (ab :30 auf); kann dabei den Tag wechseln. */
function roundToFullHour(iso: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso);
  if (!match) {
    return null;
  }
  const [year, month, day, hour, minute] = match.slice(1).map(Number);
  return new Date(year, month - 1, day, hour + (minute >= 30 ? 1 : 0), 0, 0);
}

function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** Beginn des Waschgangs, dessen Trocknungskarte auf diesem Geraet ausgeblendet ist; wirft nie. */
export function loadHiddenDryingSession(): string | null {
  try {
    return localStorage.getItem(HIDDEN_DRYING_KEY);
  } catch {
    return null;
  }
}

/** Merkt sich den ausgeblendeten Waschgang auf diesem Geraet; wirft nie. */
export function saveHiddenDryingSession(startedAt: string): void {
  try {
    localStorage.setItem(HIDDEN_DRYING_KEY, startedAt);
  } catch {
    /* ohne Speicher gilt das Ausblenden nur bis zum Neuladen */
  }
}
