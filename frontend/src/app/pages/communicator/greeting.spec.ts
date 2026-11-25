import { formatDayLine, greetingFor } from './greeting';

describe('greetingFor', () => {
  const at = (hour: number) => new Date(2026, 10, 25, hour, 0);

  it('waehlt den Gruss nach Tageszeit und nimmt den Vornamen', () => {
    expect(greetingFor(at(7), 'Benedikt Lind')).toBe('Guten Morgen, Benedikt');
    expect(greetingFor(at(14), 'Anna')).toBe('Hallo, Anna');
    expect(greetingFor(at(19), null)).toBe('Guten Abend');
    expect(greetingFor(at(23), '  ')).toBe('Gute Nacht');
    expect(greetingFor(at(3), 'Anna')).toBe('Gute Nacht, Anna');
  });

  it('schreibt Wochentag und Datum aus', () => {
    expect(formatDayLine(at(7))).toBe('Mittwoch, 25. November');
  });
});
