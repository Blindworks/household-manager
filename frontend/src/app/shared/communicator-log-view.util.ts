import {
  CommunicatorHealth, CommunicatorRequestRow, CommunicatorRequestStatus, CommunicatorTokens
} from '../models/communicator-log.model';
import { CommunicatorBlock, CommunicatorCard } from '../models/communicator.model';

export type LogTone = 'ok' | 'warn' | 'error';

export function requestStatusLabel(status: CommunicatorRequestStatus): string {
  switch (status) {
    case 'OK': return 'beantwortet';
    case 'NOT_UNDERSTOOD': return 'nichts verstanden';
    case 'SPEECH_ERROR': return 'Whisper-Fehler';
    case 'MODEL_MISSING': return 'Sprachmodell fehlt';
    case 'AI_ERROR': return 'Claude-Fehler';
    case 'INCOMPLETE': return 'ohne Antwort';
    case 'UNAVAILABLE': return 'nicht eingerichtet';
    case 'REJECTED': return 'Aufnahme abgelehnt';
  }
}

export function requestTone(row: CommunicatorRequestRow): LogTone {
  if (row.status !== 'OK') {
    return row.status === 'NOT_UNDERSTOOD' ? 'warn' : 'error';
  }
  return row.problem ? 'warn' : 'ok';
}

/** „850 ms“, „4,2 s“; ohne Messung ein Strich. */
export function formatMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return '–';
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1).replace('.', ',')} s`;
}

export function formatCount(value: number): string {
  return value.toLocaleString('de-DE');
}

export function formatDecimal(value: number, digits: number): string {
  return value.toLocaleString('de-DE', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function totalTokens(tokens: CommunicatorTokens): number {
  return tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite;
}

export function rowTokens(row: CommunicatorRequestRow): CommunicatorTokens {
  return {
    input: row.inputTokens, output: row.outputTokens, cacheRead: row.cacheReadTokens, cacheWrite: row.cacheWriteTokens
  };
}

/** Anteil der Eingabe, der aus dem Zwischenspeicher kam; null ohne Eingabe. */
export function cacheShare(tokens: CommunicatorTokens): number | null {
  const input = tokens.input + tokens.cacheRead + tokens.cacheWrite;
  return input === 0 ? null : Math.round((tokens.cacheRead / input) * 100);
}

export function whisperState(speech: CommunicatorHealth['speech']): { tone: LogTone; text: string } {
  if (!speech.configured) {
    return { tone: 'warn', text: 'Nicht eingerichtet, nur Texteingabe' };
  }
  if (!speech.reachable) {
    return { tone: 'error', text: 'Nicht erreichbar' };
  }
  if (speech.modelInstalled) {
    return { tone: 'ok', text: 'Bereit' };
  }
  return speech.installing
    ? { tone: 'warn', text: 'Modell wird geladen' }
    : { tone: 'error', text: 'Modell ist nicht installiert' };
}

/** Eine Karte als Textzeilen: was der Nutzer gesehen hat, ohne bedienbare Knöpfe. */
export function cardLines(card: CommunicatorCard): string[] {
  return [
    ...(card.blocks ?? []).map(blockLine),
    ...(card.actions ?? []).map(action => `Knopf „${action.label}“ (${action.method} ${action.path})`),
    ...(card.questions ?? []).map(question => `Folgefrage „${question}“`)
  ];
}

function blockLine(block: CommunicatorBlock): string {
  const label = block.label ? `${block.label}: ` : '';
  const unit = block.unit ? ` ${block.unit}` : '';
  switch (block.type) {
    case 'text': return block.text ?? '';
    case 'value': return `${label}${block.value ?? '–'}${unit}`;
    case 'list': return `${label}Liste mit ${block.items?.length ?? 0} Einträgen`;
    case 'chart': return `${label}Verlauf mit ${block.points?.length ?? 0} Punkten`;
    case 'slider': return `${label}Regler ${block.number ?? '–'}${unit}`;
  }
}
