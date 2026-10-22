import { FlowRunStep, SystemLogKind, SystemLogStatus } from '../models/system-log.model';

export type StatusTone = 'ok' | 'warn' | 'error' | 'neutral';

export function statusLabel(status: SystemLogStatus): string {
  switch (status) {
    case 'OK': return 'ok';
    case 'STOPPED': return 'an Bedingung gestoppt';
    case 'RATE_LIMITED': return 'Rate-Limit';
    case 'ERROR': return 'Fehler';
    case 'MISSED': return 'verpasst';
    case 'WAITING': return 'wartet';
    case 'ABORTED': return 'abgebrochen';
    case 'INFO': return 'Info';
    case 'WARN': return 'Warnung';
    case 'RECOVERED': return 'wieder ok';
  }
}

export function statusTone(status: SystemLogStatus): StatusTone {
  switch (status) {
    case 'OK':
    case 'RECOVERED':
      return 'ok';
    case 'STOPPED':
    case 'RATE_LIMITED':
    case 'WARN':
    case 'MISSED':
    case 'ABORTED':
      return 'warn';
    case 'ERROR':
      return 'error';
    case 'WAITING':
    case 'INFO':
      return 'neutral';
  }
}

export function kindIcon(kind: SystemLogKind): string {
  switch (kind) {
    case 'FLOW_RUN': return 'account_tree';
    case 'JOB': return 'schedule';
    case 'ENTITY': return 'sensors';
    case 'LOG': return 'article';
  }
}

export function kindLabel(kind: SystemLogKind): string {
  switch (kind) {
    case 'FLOW_RUN': return 'Flow-Läufe';
    case 'JOB': return 'Jobs';
    case 'ENTITY': return 'Entitäten';
    case 'LOG': return 'Log';
  }
}

export const ALL_KINDS: SystemLogKind[] = ['FLOW_RUN', 'JOB', 'ENTITY', 'LOG'];

/** „Nur Probleme“ – muss mit dem Backend übereinstimmen. */
export const PROBLEM_STATUSES: SystemLogStatus[] =
  ['ERROR', 'STOPPED', 'RATE_LIMITED', 'MISSED', 'ABORTED', 'WARN'];

export const ALL_STATUSES: SystemLogStatus[] =
  ['OK', 'STOPPED', 'RATE_LIMITED', 'ERROR', 'MISSED', 'WAITING', 'ABORTED', 'INFO', 'WARN', 'RECOVERED'];

export interface RangeOption {
  key: 'TODAY' | 'H24' | 'D7' | 'D30';
  label: string;
}

export const RANGE_OPTIONS: RangeOption[] = [
  { key: 'TODAY', label: 'Heute' },
  { key: 'H24', label: '24 h' },
  { key: 'D7', label: '7 Tage' },
  { key: 'D30', label: '30 Tage' }
];

/** Lokale Zeit ohne Zone (yyyy-MM-ddTHH:mm:ss), wie das Backend sie als LocalDateTime erwartet. */
export function toLocalIso(date: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`
    + `T${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`;
}

export function rangeStart(key: RangeOption['key'], now: Date): Date {
  const hour = 3_600_000;
  switch (key) {
    case 'TODAY': return new Date(now.getFullYear(), now.getMonth(), now.getDate());
    case 'H24': return new Date(now.getTime() - 24 * hour);
    case 'D7': return new Date(now.getTime() - 7 * 24 * hour);
    case 'D30': return new Date(now.getTime() - 30 * 24 * hour);
  }
}

export function stepOutcomeLabel(outcome: string): string {
  if (outcome.startsWith('port:')) {
    return `weiter über Ausgang ${outcome.substring('port:'.length)}`;
  }
  switch (outcome) {
    case 'no-target': return 'kein Nachfolger';
    case 'stopped': return 'gestoppt';
    case 'dropped': return 'verworfen';
    case 'deferred': return 'zurückgestellt (läuft später weiter)';
    case 'failed': return 'Fehler';
    case 'reported-failure': return 'Fehlschlag gemeldet';
    default: return outcome;
  }
}

export function stepTone(outcome: string): StatusTone {
  if (outcome === 'failed' || outcome === 'reported-failure') { return 'error'; }
  if (outcome === 'stopped' || outcome === 'dropped') { return 'warn'; }
  if (outcome === 'deferred') { return 'neutral'; }
  return 'ok';
}

/** Dauer seit einem Zeitpunkt als kurzer Text („12 min“, „5 h“, „3 Tagen“). */
export function formatSince(iso: string | null, now: Date): string {
  if (!iso) { return ''; }
  const minutes = Math.max(0, Math.floor((now.getTime() - new Date(iso).getTime()) / 60_000));
  if (minutes < 60) { return `${minutes} min`; }
  const hours = Math.floor(minutes / 60);
  if (hours < 48) { return `${hours} h`; }
  return `${Math.floor(hours / 24)} Tagen`;
}

export function isStepList(details: unknown): details is FlowRunStep[] {
  return Array.isArray(details);
}
