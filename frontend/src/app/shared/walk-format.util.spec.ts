import { TractiveWalk } from '../models/tractive.model';
import {
  groupWalksByDay,
  walkDayLabel,
  walkDayTotals,
  walkDistance,
  walkDuration,
  walkTimeRange
} from './walk-format.util';

/** Baut eine Runde mit lokaler Wandzeit - die Anzeige rechnet ueberall lokal. */
function walk(startLocal: string, endLocal: string,
              durationMinutes: number, distanceMeters: number): TractiveWalk {
  return {
    start: new Date(startLocal).toISOString(),
    end: new Date(endLocal).toISOString(),
    durationMinutes,
    distanceMeters
  };
}

describe('walk-format.util', () => {
  describe('walkDuration', () => {
    it('zeigt unter einer Stunde nur Minuten', () => {
      expect(walkDuration(walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100))).toBe('36 min');
    });

    it('zeigt ab einer Stunde Stunden und Minuten', () => {
      expect(walkDuration(walk('2026-08-22T07:00', '2026-08-22T08:25', 85, 5000)))
        .toBe('1 h 25 min');
    });
  });

  describe('walkDistance', () => {
    it('zeigt unter einem Kilometer ganze Meter', () => {
      expect(walkDistance(walk('2026-08-22T07:00', '2026-08-22T07:10', 10, 842.4))).toBe('842 m');
    });

    it('zeigt ab genau einem Kilometer Kilometer mit Komma', () => {
      expect(walkDistance(walk('2026-08-22T07:00', '2026-08-22T07:20', 20, 1000))).toBe('1,0 km');
      expect(walkDistance(walk('2026-08-22T07:00', '2026-08-22T07:40', 40, 2149))).toBe('2,1 km');
    });
  });

  describe('walkTimeRange', () => {
    it('nennt Start und Ende als Uhrzeit', () => {
      expect(walkTimeRange(walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100)))
        .toBe('07:12–07:48 Uhr');
    });
  });

  describe('walkDayLabel', () => {
    const now = new Date('2026-08-22T18:00');

    it('nennt den heutigen Tag beim Namen', () => {
      expect(walkDayLabel(new Date('2026-08-22T07:12').toISOString(), now)).toBe('Heute');
    });

    it('nennt den Vortag beim Namen', () => {
      expect(walkDayLabel(new Date('2026-08-21T19:30').toISOString(), now)).toBe('Gestern');
    });

    it('nennt aeltere Tage mit Wochentag und Datum', () => {
      expect(walkDayLabel(new Date('2026-08-18T09:00').toISOString(), now)).toContain('18.8.');
    });
  });

  describe('groupWalksByDay', () => {
    it('fasst Runden desselben Kalendertags zusammen', () => {
      const groups = groupWalksByDay([
        walk('2026-08-22T18:00', '2026-08-22T18:30', 30, 1800),
        walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100),
        walk('2026-08-21T19:00', '2026-08-21T19:20', 20, 900)
      ]);

      expect(groups.length).toBe(2);
      expect(groups[0].walks.length).toBe(2);
      expect(groups[1].walks.length).toBe(1);
    });
  });

  describe('walkDayTotals', () => {
    const now = new Date('2026-08-22T18:00');

    it('summiert die Gehdauer je Kalendertag, aelteste zuerst', () => {
      const totals = walkDayTotals([
        walk('2026-08-22T18:00', '2026-08-22T18:30', 30, 1800),
        walk('2026-08-22T07:12', '2026-08-22T07:48', 36, 2100),
        walk('2026-08-20T19:00', '2026-08-20T19:20', 20, 900)
      ], 3, now);

      expect(totals.map(total => total.minutes)).toEqual([20, 0, 66]);
    });

    it('liefert fuer jeden Tag des Zeitraums einen Balken, auch ohne Runde', () => {
      // Ein fehlender Balken saehe aus wie ein Datenloch; eine Null ist eine Aussage.
      const totals = walkDayTotals([], 7, now);
      expect(totals.length).toBe(7);
      expect(totals.every(total => total.minutes === 0)).toBeTrue();
    });

    it('beschriftet die Balken mit Tag und Monat', () => {
      const totals = walkDayTotals([], 2, now);
      expect(totals.map(total => total.label)).toEqual(['21.8.', '22.8.']);
    });

    it('ignoriert Runden ausserhalb des Zeitraums', () => {
      const totals = walkDayTotals(
        [walk('2026-08-01T07:00', '2026-08-01T07:30', 30, 1500)], 3, now);
      expect(totals.every(total => total.minutes === 0)).toBeTrue();
    });
  });
});
