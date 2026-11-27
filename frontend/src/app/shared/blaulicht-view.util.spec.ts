import { BlaulichtBriefing, BlaulichtEvent } from '../models/blaulicht.model';
import { buildBlaulichtInsight, formatEventTime } from './blaulicht-view.util';

function event(overrides: Partial<BlaulichtEvent> = {}): BlaulichtEvent {
  return {
    id: 1, source: 'PRESSEPORTAL', category: 'EINBRUCH', locality: 'Dortelweil', nearby: false,
    title: 'Einbruch', summary: null, occurredAt: null, occurredUntil: null,
    publishedAt: '2026-11-25T14:12:00', url: null, relevance: 100, newSinceEvening: true,
    ...overrides
  };
}

function briefing(overrides: Partial<BlaulichtBriefing> = {}): BlaulichtBriefing {
  return {
    nightStart: '2026-11-24T20:00', nightEnd: '2026-11-25T07:00',
    text: 'Heute Nacht war es in Bad Vilbel ruhig.', mainCount: 0, nearbyCount: 0, olderNewCount: 0,
    addendumText: '', addendumCount: 0, cardVisible: true, addendumVisible: false, events: [],
    ...overrides
  };
}

describe('blaulicht-view.util', () => {
  it('zeigt keine Karte ausserhalb des Tagesfensters oder ohne Daten', () => {
    expect(buildBlaulichtInsight(null)).toBeNull();
    expect(buildBlaulichtInsight(briefing({ cardVisible: false }))).toBeNull();
  });

  it('hides the card when the briefing has nothing to report', () => {
    expect(buildBlaulichtInsight(briefing())).toBeNull();
    expect(buildBlaulichtInsight(briefing({ addendumVisible: true, addendumCount: 0 }))).toBeNull();
  });

  it('zeigt eine ruhige Nacht mit Meldungen aus Nachbarorten gedaempft', () => {
    const insight = buildBlaulichtInsight(briefing({
      text: 'Heute Nacht war es in Bad Vilbel ruhig. Dazu 1 Meldung aus Nachbarorten.', nearbyCount: 1,
      events: [event({ nearby: true, locality: 'Karben' })]
    }))!;
    expect(insight.tone).toBe('muted');
    expect(insight.tapAction).toBe('blaulicht');
  });

  it('haengt den Nachtrag an, sobald er sichtbar ist', () => {
    const insight = buildBlaulichtInsight(briefing({
      text: 'Heute Nacht: 1 Einbruch in Dortelweil.', mainCount: 1,
      addendumText: 'Nachtrag zur Nacht: 1 Unfall in Bad Vilbel.', addendumCount: 1, addendumVisible: true
    }))!;
    expect(insight.tone).toBe('tertiary');
    expect(insight.text).toBe('Heute Nacht: 1 Einbruch in Dortelweil. Nachtrag zur Nacht: 1 Unfall in Bad Vilbel.');
  });

  it('hebt eine Warnung fuer Bad Vilbel hervor', () => {
    const insight = buildBlaulichtInsight(briefing({ mainCount: 1, events: [event({ category: 'WARNUNG' })] }))!;
    expect(insight.tone).toBe('error');
    expect(insight.icon).toBe('warning');
  });

  it('formatiert Tatzeit, Zeitraum und Veroeffentlichung', () => {
    const now = new Date(2026, 10, 25, 15, 0);
    expect(formatEventTime(event({ occurredAt: '2026-11-24T21:50' }), now)).toBe('gestern 21:50');
    expect(formatEventTime(event({ occurredAt: '2026-11-25T01:00', occurredUntil: '2026-11-25T03:30' }), now))
      .toBe('heute 01:00–03:30');
    expect(formatEventTime(event(), now)).toBe('gemeldet heute 14:12');
    expect(formatEventTime(event({ occurredAt: '2026-11-16T18:00', occurredUntil: '2026-11-23T14:00' }), now))
      .toContain(' – ');
  });
});
