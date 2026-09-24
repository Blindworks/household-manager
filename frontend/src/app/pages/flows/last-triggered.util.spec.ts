import { lastTriggeredText } from './last-triggered.util';

describe('lastTriggeredText', () => {
  const now = new Date(2026, 8, 24, 19, 45).getTime();

  it('zeigt „Nie“ ohne Zeitstempel', () => {
    expect(lastTriggeredText(null, now)).toBe('Nie');
    expect(lastTriggeredText(undefined, now)).toBe('Nie');
    expect(lastTriggeredText('kein datum', now)).toBe('Nie');
  });

  it('zeigt nur die Uhrzeit für heute', () => {
    expect(lastTriggeredText('2026-09-24T07:05:00', now)).toBe('heute, 07:05');
  });

  it('zeigt „gestern“ auch kurz vor Mitternacht', () => {
    expect(lastTriggeredText('2026-09-23T23:59:00', now)).toBe('gestern, 23:59');
  });

  it('zeigt ältere Auslösungen mit vollem Datum', () => {
    expect(lastTriggeredText('2026-09-01T16:00:00', now)).toBe('01.09.2026, 16:00');
  });
});
