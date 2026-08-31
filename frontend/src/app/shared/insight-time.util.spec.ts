import { elapsedText, sinceText } from './insight-time.util';

/** Bezugszeitpunkt am selben Tag wie die Test-Zeitstempel. */
const NOW_MS = new Date('2026-08-14T19:00:00').getTime();

describe('sinceText', () => {

  it('nennt nur die Uhrzeit, wenn der Zeitpunkt vom selben Tag stammt', () => {
    expect(sinceText('2026-08-14T17:46:32', NOW_MS, 'Offen', 'egal')).toBe('Offen seit 17:46 Uhr.');
  });

  it('nennt zusaetzlich das Datum, wenn der Zeitpunkt aelter als heute ist', () => {
    expect(sinceText('2026-08-13T22:10:00', NOW_MS, 'Fertig', 'egal'))
      .toBe('Fertig seit 13.08., 22:10 Uhr.');
  });

  it('gibt bei unlesbarem Zeitstempel den Rueckfalltext des Aufrufers zurueck', () => {
    expect(sinceText('kaputt', NOW_MS, 'Fertig', 'Die Maschine ist fertig.'))
      .toBe('Die Maschine ist fertig.');
  });
});

describe('elapsedText', () => {

  /** Bildet einen Zeitstempel, der `minutes` Minuten vor NOW_MS liegt. */
  function minutesAgo(minutes: number): string {
    return new Date(NOW_MS - minutes * 60_000).toISOString();
  }

  it('nennt unter einer Minute keine Zahl', () => {
    expect(elapsedText(minutesAgo(0.5), NOW_MS, 'Läuft', 'egal'))
      .toBe('Läuft seit weniger als einer Minute.');
  });

  it('setzt die Einzahl bei genau einer Minute', () => {
    expect(elapsedText(minutesAgo(1), NOW_MS, 'Läuft', 'egal')).toBe('Läuft seit 1 Minute.');
  });

  it('nennt Minuten bis knapp unter einer Stunde', () => {
    expect(elapsedText(minutesAgo(42), NOW_MS, 'Läuft', 'egal')).toBe('Läuft seit 42 Minuten.');
    expect(elapsedText(minutesAgo(59), NOW_MS, 'Läuft', 'egal')).toBe('Läuft seit 59 Minuten.');
  });

  it('wechselt ab einer Stunde auf Stunden und Minuten', () => {
    expect(elapsedText(minutesAgo(75), NOW_MS, 'Läuft', 'egal')).toBe('Läuft seit 1 Std. 15 Min.');
  });

  it('laesst die Minuten bei einer vollen Stunde weg', () => {
    expect(elapsedText(minutesAgo(120), NOW_MS, 'Läuft', 'egal')).toBe('Läuft seit 2 Std.');
  });

  it('gibt bei unlesbarem Zeitstempel den Rueckfalltext des Aufrufers zurueck', () => {
    expect(elapsedText('kaputt', NOW_MS, 'Läuft', 'Die Maschine läuft gerade.'))
      .toBe('Die Maschine läuft gerade.');
  });

  it('gibt bei einem Zeitstempel aus der Zukunft den Rueckfalltext zurueck', () => {
    expect(elapsedText(minutesAgo(-5), NOW_MS, 'Läuft', 'Die Maschine läuft gerade.'))
      .toBe('Die Maschine läuft gerade.');
  });
});
