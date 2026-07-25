import { RecurrenceOptions, buildRrule, parseRrule } from './rrule.util';

function options(overrides: Partial<RecurrenceOptions>): RecurrenceOptions {
  return {
    freq: 'WEEKLY', interval: 1, weekdays: [], monthlyMode: 'DAY_OF_MONTH',
    endType: 'NEVER', untilDate: null, count: null,
    ...overrides
  };
}

describe('buildRrule', () => {
  it('baut eine einfache woechentliche Regel', () => {
    expect(buildRrule(options({}), '2026-07-07')).toBe('FREQ=WEEKLY');
  });

  it('baut Intervall und Wochentage ein', () => {
    expect(buildRrule(options({ interval: 2, weekdays: ['MO', 'FR'] }), '2026-07-06'))
      .toBe('FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR');
  });

  it('baut monatlich am Monatstag', () => {
    expect(buildRrule(options({ freq: 'MONTHLY' }), '2026-07-14'))
      .toBe('FREQ=MONTHLY;BYMONTHDAY=14');
  });

  it('baut monatlich am n-ten Wochentag aus dem Startdatum', () => {
    // 14.07.2026 ist der zweite Dienstag des Monats
    expect(buildRrule(options({ freq: 'MONTHLY', monthlyMode: 'NTH_WEEKDAY' }), '2026-07-14'))
      .toBe('FREQ=MONTHLY;BYDAY=2TU');
  });

  it('baut UNTIL und COUNT', () => {
    expect(buildRrule(options({ endType: 'UNTIL', untilDate: '2026-12-31' }), '2026-07-07'))
      .toBe('FREQ=WEEKLY;UNTIL=20261231');
    expect(buildRrule(options({ endType: 'COUNT', count: 10 }), '2026-07-07'))
      .toBe('FREQ=WEEKLY;COUNT=10');
  });

  it('baut die Negativform fuer ein "5." Vorkommen am Monatsende', () => {
    // 29.09.2026 ist ein Dienstag; ein fuenfter Dienstag existiert in den meisten
    // Monaten nicht, "letzter Dienstag" (BYDAY=-1TU) trifft dagegen immer.
    expect(buildRrule(options({ freq: 'MONTHLY', monthlyMode: 'NTH_WEEKDAY' }), '2026-09-29'))
      .toBe('FREQ=MONTHLY;BYDAY=-1TU');
  });

  it('wirft bei COUNT=0, statt eine unendlich laufende Regel zu erzeugen', () => {
    expect(() => buildRrule(options({ endType: 'COUNT', count: 0 }), '2026-07-07')).toThrow();
  });

  it('wirft bei fehlendem untilDate, statt eine unendlich laufende Regel zu erzeugen', () => {
    expect(() => buildRrule(options({ endType: 'UNTIL', untilDate: '' }), '2026-07-07')).toThrow();
  });
});

describe('parseRrule', () => {
  it('liest eine Builder-Regel zurueck (Roundtrip)', () => {
    const original = options({ interval: 2, weekdays: ['MO', 'FR'], endType: 'COUNT', count: 5 });
    const parsed = parseRrule(buildRrule(original, '2026-07-06'));
    expect(parsed).toEqual(jasmine.objectContaining({
      freq: 'WEEKLY', interval: 2, weekdays: ['MO', 'FR'], endType: 'COUNT', count: 5
    }));
  });

  it('erkennt monatlich am n-ten Wochentag', () => {
    expect(parseRrule('FREQ=MONTHLY;BYDAY=2TU')).toEqual(jasmine.objectContaining({
      freq: 'MONTHLY', monthlyMode: 'NTH_WEEKDAY'
    }));
  });

  it('liefert null fuer Regeln, die der Builder nicht abbildet', () => {
    expect(parseRrule('FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=-1')).toBeNull();
    expect(parseRrule('FREQ=HOURLY')).toBeNull();
  });

  it('erkennt die Negativform "letzter Wochentag" als n-ten Wochentag', () => {
    expect(parseRrule('FREQ=MONTHLY;BYDAY=-1TU')).toEqual(jasmine.objectContaining({
      freq: 'MONTHLY', monthlyMode: 'NTH_WEEKDAY'
    }));
  });

  it('akzeptiert WKST und ignoriert den Wert (harmlos ohne INTERVAL>1 mit mehreren Wochentagen)', () => {
    expect(parseRrule('FREQ=WEEKLY;BYDAY=MO;WKST=SU')).toEqual(jasmine.objectContaining({
      freq: 'WEEKLY', weekdays: ['MO']
    }));
  });
});
