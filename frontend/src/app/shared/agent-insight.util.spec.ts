import { buildAgentInsight } from './agent-insight.util';
import { AgentSummary } from '../models/agent.model';

function summary(overrides: Partial<AgentSummary> = {}): AgentSummary {
  return { pendingProposals: 1, active: true, telegramConfigured: true, ...overrides };
}

describe('buildAgentInsight', () => {
  it('liefert null ohne Summary', () => {
    expect(buildAgentInsight(null)).toBeNull();
  });

  it('liefert null ohne offene Vorschlaege', () => {
    expect(buildAgentInsight(summary({ pendingProposals: 0 }))).toBeNull();
  });

  it('liefert null bei inaktivem Hausverstand', () => {
    expect(buildAgentInsight(summary({ active: false, pendingProposals: 4 }))).toBeNull();
  });

  it('nutzt bei einem Vorschlag den Singular und verlinkt die Admin-Seite', () => {
    expect(buildAgentInsight(summary({ pendingProposals: 1 }))).toEqual({
      icon: 'psychology',
      tone: 'secondary',
      title: 'Hausverstand',
      text: '1 Vorschlag offen',
      link: '/admin/agent'
    });
  });

  it('nutzt bei mehreren Vorschlaegen den Plural', () => {
    expect(buildAgentInsight(summary({ pendingProposals: 3 }))?.text).toBe('3 Vorschläge offen');
  });
});
