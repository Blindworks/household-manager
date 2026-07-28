import { buildCalendarInsights } from './calendar-insight.util';
import { CalendarOccurrence } from '../models/calendar-event.model';

function occurrence(overrides: Partial<CalendarOccurrence>): CalendarOccurrence {
  return {
    eventId: 1, occurrenceDate: '2026-07-25', recurrenceDate: null,
    title: 'Zahnarzt', notes: null,
    category: { id: 3, key: 'health', name: 'Gesundheit', color: '#e57373', icon: null },
    persons: [], allDay: false,
    startTime: '14:30', endTime: null, endDate: null, recurring: false, daysUntil: 0,
    ...overrides
  };
}

describe('buildCalendarInsights', () => {
  it('liefert hoechstens drei Eintraege', () => {
    const occurrences = [0, 1, 2, 3].map(i =>
      occurrence({ eventId: i, daysUntil: i }));
    expect(buildCalendarInsights(occurrences).length).toBe(3);
  });

  it('liefert eine leere Liste, wenn nichts ansteht', () => {
    expect(buildCalendarInsights([])).toEqual([]);
  });

  it('formatiert Uhrzeit-Termine mit Tag und Uhrzeit', () => {
    const [insight] = buildCalendarInsights([occurrence({ daysUntil: 1 })]);
    expect(insight.title).toBe('Zahnarzt');
    expect(insight.text).toBe('Morgen, 14:30 Uhr');
  });

  it('formatiert ganztaegige Termine ohne Uhrzeit', () => {
    const [insight] = buildCalendarInsights([
      occurrence({ allDay: true, startTime: null, daysUntil: 0 })]);
    expect(insight.text).toBe('Heute');
  });

  it('faerbt heute und morgen rot, uebermorgen gelb, spaeter blau', () => {
    const tones = [0, 1, 2, 3].map(daysUntil =>
      buildCalendarInsights([occurrence({ daysUntil })])[0].tone);
    expect(tones).toEqual(['error', 'error', 'tertiary', 'primary']);
  });

  it('haengt die zugeordnete Person an die Zeitangabe', () => {
    const [insight] = buildCalendarInsights([
      occurrence({ daysUntil: 1, persons: [{ id: 7, displayName: 'Anna' }] })]);
    expect(insight.text).toBe('Morgen, 14:30 Uhr · Anna');
  });

  it('listet mehrere Personen auf', () => {
    const [insight] = buildCalendarInsights([occurrence({
      allDay: true, startTime: null, daysUntil: 0,
      persons: [{ id: 7, displayName: 'Anna' }, { id: 8, displayName: 'Ben' }]
    })]);
    expect(insight.text).toBe('Heute · Anna, Ben');
  });

  it('laesst Haushaltstermine ohne Personenzusatz', () => {
    const [insight] = buildCalendarInsights([occurrence({ daysUntil: 1, persons: [] })]);
    expect(insight.text).toBe('Morgen, 14:30 Uhr');
  });

  it('nutzt den Wochentag fuer fernere Termine', () => {
    // 29.07.2026 ist ein Mittwoch
    const [insight] = buildCalendarInsights([
      occurrence({ occurrenceDate: '2026-07-29', daysUntil: 4 })]);
    expect(insight.text).toBe('Mittwoch, 14:30 Uhr');
  });
});
