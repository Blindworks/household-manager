import { FlowSummary } from '../../models/flow.model';

/** Abschnitt für Flows ohne Bereich; steht immer am Ende. */
export const UNCATEGORIZED_LABEL = 'Sonstiges';

export interface FlowSection {
  title: string;
  flows: FlowSummary[];
  /** Mindestens ein Flow des Abschnitts ist deaktiviert — im Kopf sichtbar, auch wenn zugeklappt. */
  hasDisabled: boolean;
}

const collator = new Intl.Collator('de', { sensitivity: 'base' });

function categoryOf(flow: FlowSummary): string | null {
  const trimmed = flow.category?.trim();
  return trimmed ? trimmed : null;
}

/**
 * Einzige Definition der Gliederung der Flow-Übersicht: ein Abschnitt je Bereich,
 * alphabetisch, „Sonstiges" zuletzt; innerhalb nach Name.
 */
export function groupFlowsByCategory(flows: FlowSummary[]): FlowSection[] {
  const groups = new Map<string, FlowSummary[]>();
  for (const flow of flows) {
    const title = categoryOf(flow) ?? UNCATEGORIZED_LABEL;
    groups.set(title, [...(groups.get(title) ?? []), flow]);
  }
  return [...groups.entries()]
    .map(([title, members]) => ({
      title,
      flows: [...members].sort((a, b) => collator.compare(a.name, b.name)),
      hasDisabled: members.some(f => !f.enabled)
    }))
    .sort((a, b) => rank(a.title) - rank(b.title) || collator.compare(a.title, b.title));
}

function rank(title: string): number {
  return title === UNCATEGORIZED_LABEL ? 1 : 0;
}

/** Suche über Name und Beschreibung, Groß-/Kleinschreibung egal; leere Suche passt immer. */
export function matchesFlowSearch(flow: FlowSummary, query: string): boolean {
  const needle = query.trim().toLocaleLowerCase('de');
  if (!needle) {
    return true;
  }
  return [flow.name, flow.description ?? '']
    .some(text => text.toLocaleLowerCase('de').includes(needle));
}

/** Vorhandene Bereiche (für die Vorschlagsliste im Editor). */
export function distinctCategories(flows: FlowSummary[]): string[] {
  const categories = new Set<string>();
  for (const flow of flows) {
    const category = categoryOf(flow);
    if (category) {
      categories.add(category);
    }
  }
  return [...categories].sort(collator.compare);
}
