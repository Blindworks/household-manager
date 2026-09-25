import { FlowSummary } from '../../models/flow.model';
import {
  UNCATEGORIZED_LABEL, distinctCategories, groupFlowsByCategory, matchesFlowSearch
} from './flow-grouping.util';

function flow(id: number, name: string, category?: string | null, enabled = true, description?: string): FlowSummary {
  return { id, name, category, enabled, deployed: true, description };
}

describe('groupFlowsByCategory', () => {
  it('sorts sections alphabetically (German collation) and puts "Sonstiges" last', () => {
    const sections = groupFlowsByCategory([
      flow(1, 'a', null),
      flow(2, 'b', 'Taster'),
      flow(3, 'c', 'Ärger'),
      flow(4, 'd', 'Licht')
    ]);

    expect(sections.map(s => s.title)).toEqual(['Ärger', 'Licht', 'Taster', UNCATEGORIZED_LABEL]);
  });

  it('treats blank categories as uncategorized', () => {
    const sections = groupFlowsByCategory([flow(1, 'a', '   '), flow(2, 'b', undefined)]);

    expect(sections.length).toBe(1);
    expect(sections[0].title).toBe(UNCATEGORIZED_LABEL);
    expect(sections[0].flows.length).toBe(2);
  });

  it('sorts flows by name within a section', () => {
    const sections = groupFlowsByCategory([flow(1, 'Zeta', 'Licht'), flow(2, 'Älpha', 'Licht'), flow(3, 'beta', 'Licht')]);

    expect(sections[0].flows.map(f => f.name)).toEqual(['Älpha', 'beta', 'Zeta']);
  });

  it('flags sections that contain a disabled flow', () => {
    const sections = groupFlowsByCategory([flow(1, 'a', 'Licht', false), flow(2, 'b', 'Taster', true)]);

    expect(sections.find(s => s.title === 'Licht')!.hasDisabled).toBeTrue();
    expect(sections.find(s => s.title === 'Taster')!.hasDisabled).toBeFalse();
  });

  it('returns no sections for no flows', () => {
    expect(groupFlowsByCategory([])).toEqual([]);
  });
});

describe('matchesFlowSearch', () => {
  it('matches everything for an empty query', () => {
    expect(matchesFlowSearch(flow(1, 'Nachtmodus'), '  ')).toBeTrue();
  });

  it('matches name and description case-insensitively', () => {
    const f = flow(1, 'Nachtmodus', null, true, 'Verriegelt die Tür');
    expect(matchesFlowSearch(f, 'NACHT')).toBeTrue();
    expect(matchesFlowSearch(f, 'tür')).toBeTrue();
    expect(matchesFlowSearch(f, 'Waschmaschine')).toBeFalse();
  });
});

describe('distinctCategories', () => {
  it('lists each non-blank category once, trimmed and sorted', () => {
    expect(distinctCategories([
      flow(1, 'a', 'Taster'), flow(2, 'b', ' Licht '), flow(3, 'c', 'Taster'), flow(4, 'd', null), flow(5, 'e', ' ')
    ])).toEqual(['Licht', 'Taster']);
  });
});
