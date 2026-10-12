import {
  ActionClass,
  ActionPolicy,
  NoteKind,
  ProposalStatus
} from '../models/agent.model';

/** Einzige Definition der deutschen Anzeigetexte des Hausverstands. */
export const ACTION_CLASS_LABELS: Record<ActionClass, string> = {
  HEATING: 'Heizung',
  SWITCH: 'Schalter & Licht',
  MODE: 'Modi & Helfer',
  NOTIFY: 'Nachrichten',
  LOCK: 'Haustür verriegeln'
};

export const POLICY_LABELS: Record<ActionPolicy, string> = {
  PROPOSE: 'nur vorschlagen',
  DIRECT: 'direkt ausführen'
};

export const PROPOSAL_STATUS_LABELS: Record<ProposalStatus, string> = {
  PENDING: 'Offen',
  APPROVED: 'Angenommen',
  REJECTED: 'Abgelehnt',
  EXPIRED: 'Verfallen',
  EXECUTED: 'Ausgeführt',
  FAILED: 'Fehlgeschlagen'
};

export const NOTE_KIND_LABELS: Record<NoteKind, string> = {
  OBSERVATION: 'Beobachtung',
  PREFERENCE: 'Vorliebe'
};

const SOURCE_LABELS: Record<string, string> = {
  AGENT: 'Hausverstand',
  USER: 'Haushalt'
};

/** Unbekannte Quellen werden roh angezeigt statt zu verschwinden. */
export function sourceLabel(source: string): string {
  return SOURCE_LABELS[source] ?? source;
}
