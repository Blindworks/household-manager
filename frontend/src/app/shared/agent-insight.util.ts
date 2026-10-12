import { AgentSummary } from '../models/agent.model';
import { HubInsight } from './hub-insight.model';

/**
 * Hub-Karte fuer offene Vorschlaege des Hausverstands. Ohne Summary, bei inaktivem
 * Hausverstand oder ohne offene Vorschlaege gibt es keine Karte.
 */
export function buildAgentInsight(summary: AgentSummary | null): HubInsight | null {
  if (!summary || !summary.active || summary.pendingProposals <= 0) {
    return null;
  }
  const count = summary.pendingProposals;
  return {
    icon: 'psychology',
    tone: 'secondary',
    title: 'Hausverstand',
    text: count === 1 ? '1 Vorschlag offen' : `${count} Vorschläge offen`,
    link: '/admin/agent'
  };
}
