import { sinceText } from './insight-time.util';

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
