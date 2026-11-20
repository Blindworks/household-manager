import {
  GroceryRunJudgementLine, GroceryRunLogSummary, GroceryRunProductLine, GroceryVerdict
} from '../models/grocery.model';
import { formatBasePrice, formatEuro } from './grocery-format.util';

/** Einzige Darstellung des Laufprotokolls der Supermarkt-Angebote (Admin-Seite „Supermärkte“). */

export type RunTone = 'ok' | 'warn' | 'error';

export interface RunStatusView {
  tone: RunTone;
  text: string;
}

/** Abgebrochen ⇒ Fehler; gestörte Quellen, Claude-Fehler oder verschobene Produkte ⇒ Hinweis; sonst OK. */
export function runStatusView(run: GroceryRunLogSummary): RunStatusView {
  if (!run.ok) {
    return { tone: 'error', text: `Abgebrochen: ${run.error ?? 'unbekannter Fehler'}` };
  }
  const problems: string[] = [];
  if (run.sourceFailures > 0) {
    problems.push(run.sourceFailures === 1 ? '1 Quelle gestört' : `${run.sourceFailures} Quellen gestört`);
  }
  if (run.aiFailures > 0) {
    problems.push(`${run.aiFailures} Claude-Fehler`);
  }
  if (run.deferred > 0) {
    problems.push(`${run.deferred} Produkte verschoben`);
  }
  return problems.length > 0 ? { tone: 'warn', text: problems.join(', ') } : { tone: 'ok', text: 'OK' };
}

export function triggerLabel(run: Pick<GroceryRunLogSummary, 'trigger'>): string {
  return run.trigger === 'NIGHTLY' ? 'Nachtlauf' : 'Von Hand';
}

/** Dauer aus Serverzeiten ohne Zone („5 Min. 12 s“, „48 s“); unlesbare Zeiten ⇒ „–“. */
export function formatRunDuration(startedAt: string, finishedAt: string): string {
  const ms = Date.parse(finishedAt) - Date.parse(startedAt);
  if (!Number.isFinite(ms) || ms < 0) {
    return '–';
  }
  const seconds = Math.round(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  return minutes > 0 ? `${minutes} Min. ${seconds % 60} s` : `${seconds} s`;
}

/** Tokens kompakt: „12.345“ bzw. „1,2 Mio.“. */
export function formatTokens(tokens: number): string {
  if (tokens >= 1_000_000) {
    return `${(tokens / 1_000_000).toLocaleString('de-DE', { maximumFractionDigits: 1 })} Mio.`;
  }
  return tokens.toLocaleString('de-DE');
}

export const VERDICT_LABELS: Record<GroceryVerdict, string> = {
  MATCH: 'Passt',
  MAYBE: 'Unsicher',
  NO: 'Passt nicht'
};

const REASON_KIND_LABELS: Record<string, string> = {
  SORT_UNCLEAR: 'Sorte unklar',
  PACKAGING_DIFFERS: 'Packung weicht ab',
  OTHER: 'Sonstiges'
};

export function reasonKindLabel(kind: string | null): string | null {
  return kind == null ? null : REASON_KIND_LABELS[kind] ?? kind;
}

export type VerdictFilter = GroceryVerdict | 'ALL';

/** Filtert die Claude-Urteile eines Laufs nach Urteil und Produkt (`null` = alle Produkte). */
export function filterJudgements(judgements: GroceryRunJudgementLine[], verdict: VerdictFilter,
                                 productId: number | null): GroceryRunJudgementLine[] {
  return judgements.filter(j => (verdict === 'ALL' || j.verdict === verdict)
    && (productId == null || j.productId === productId));
}

export function countByVerdict(judgements: GroceryRunJudgementLine[]): Record<GroceryVerdict, number> {
  const counts: Record<GroceryVerdict, number> = { MATCH: 0, MAYBE: 0, NO: 0 };
  judgements.forEach(j => counts[j.verdict]++);
  return counts;
}

/** Angebot zu einem Urteil: „Penny · 0,86 € · 1,43 €/l“ bzw. Hinweis, wenn es nicht mehr im Bestand ist. */
export function judgementOfferMeta(j: GroceryRunJudgementLine): string {
  if (j.offerTitle == null) {
    return 'Angebot nicht mehr im Bestand';
  }
  const parts: string[] = [];
  if (j.chain) {
    parts.push(j.chain);
  }
  if (j.price != null) {
    parts.push(formatEuro(j.price));
  }
  if (j.basePrice != null && j.baseUnit) {
    parts.push(formatBasePrice(j.basePrice, j.baseUnit));
  }
  return parts.join(' · ');
}

/** Normalpreis zum Laufende: eigener vor geschätztem; ohne beide „–“. */
export function productNormalPrice(p: GroceryRunProductLine): string {
  const unit = p.baseUnit;
  if (p.normalPrice != null && unit) {
    return `${formatBasePrice(p.normalPrice, unit)} (eigener)`;
  }
  if (p.estimatedPrice != null && unit) {
    return `${formatBasePrice(p.estimatedPrice, unit)} (geschätzt)`;
  }
  return '–';
}

/** Hinweis je Produkt: Abbruch, Claude-Fehler oder Deckel; sonst leer. */
export function productNote(p: GroceryRunProductLine): string {
  if (p.error) {
    return `Fehler: ${p.error}`;
  }
  const notes: string[] = [];
  if (p.aiFailures > 0) {
    notes.push(`${p.aiFailures} Claude-Aufruf(e) ohne Antwort`);
  }
  if (p.deferred) {
    notes.push('verschoben (Claude-Deckel)');
  }
  return notes.join(', ');
}
