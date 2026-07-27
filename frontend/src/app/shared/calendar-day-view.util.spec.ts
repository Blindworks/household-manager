import { CalendarOccurrence } from '../models/calendar-event.model';
import { CalendarCategoryRef } from '../models/calendar-category.model';
import { MonthDay } from './month-grid.util';
import {
  CHIP_INITIAL_LIMIT, DAY_CHIP_LIMIT, buildDayView, matchesPersonFilter
} from './calendar-day-view.util';

const DAY: MonthDay = {
  isoDate: '2026-08-10', dayOfMonth: 10, inMonth: true, isToday: false
};

const HEALTH: CalendarCategoryRef =
  { id: 3, key: 'health', name: 'Gesundheit', color: '#e57373', icon: null };

function occurrence(overrides: Partial<CalendarOccurrence> = {}): CalendarOccurrence {
  return {
    eventId: 1,
    occurrenceDate: DAY.isoDate,
    recurrenceDate: null,
    title: 'Zahnarzt',
    notes: null,
    category: HEALTH,
    persons: [],
    allDay: true,
    startTime: null,
    endTime: null,
    endDate: null,
    recurring: false,
    daysUntil: 0,
    ...overrides
  };
}

describe('calendar-day-view.util', () => {

  describe('matchesPersonFilter', () => {
    const annasTermin = occurrence({ persons: [{ id: 11, displayName: 'Anna' }] });
    const haushaltstermin = occurrence({ persons: [] });

    it('ohne Filter passt jeder Termin', () => {
      expect(matchesPersonFilter(annasTermin, null)).toBeTrue();
      expect(matchesPersonFilter(haushaltstermin, null)).toBeTrue();
    });

    it('ein Personenfilter passt auf die Termine der gewaehlten Person', () => {
      expect(matchesPersonFilter(annasTermin, 11)).toBeTrue();
    });

    it('ein Personenfilter passt zusaetzlich immer auf Termine ohne Zuordnung', () => {
      // "Meine Termine" heisst "mir zugeordnet ODER den ganzen Haushalt betreffend".
      // Sonst verschwaende die Muellabfuhr genau dann, wenn jemand auf sich filtert.
      expect(matchesPersonFilter(haushaltstermin, 11)).toBeTrue();
    });

    it('ein Personenfilter passt nicht auf die Termine anderer Personen', () => {
      expect(matchesPersonFilter(annasTermin, 10)).toBeFalse();
    });

    it('einer von mehreren Zugeordneten genuegt', () => {
      const geteilt = occurrence({
        persons: [{ id: 10, displayName: 'Benedikt' }, { id: 11, displayName: 'Anna' }]
      });
      expect(matchesPersonFilter(geteilt, 11)).toBeTrue();
    });
  });

  describe('buildDayView', () => {

    it('deckelt die Chips und weist den Rest als overflow aus', () => {
      const viele = [1, 2, 3, 4, 5].map(n => occurrence({ eventId: n, title: `Termin ${n}` }));

      const view = buildDayView(DAY, viele, null);

      expect(view.chips.length).toBe(DAY_CHIP_LIMIT);
      expect(view.chips.map(chip => chip.title)).toEqual(['Termin 1', 'Termin 2', 'Termin 3']);
      expect(view.overflow).toBe(2);
    });

    it('zaehlt als overflow nur die vom Filter durchgelassenen Termine', () => {
      // Sonst verspraeche die "+n weitere"-Anzeige Termine, die der Filter ausblendet.
      const annas = [1, 2, 3, 4].map(n =>
        occurrence({ eventId: n, persons: [{ id: 11, displayName: 'Anna' }] }));
      const fremd = occurrence({ eventId: 9, persons: [{ id: 10, displayName: 'Benedikt' }] });

      expect(buildDayView(DAY, [...annas, fremd], null).overflow).toBe(2);
      expect(buildDayView(DAY, [...annas, fremd], 11).overflow).toBe(1);
    });

    it('reicht den Tag und die Kategoriefarbe durch', () => {
      const view = buildDayView(DAY, [occurrence()], null);

      expect(view.day).toBe(DAY);
      expect(view.chips[0].color).toBe(HEALTH.color);
    });

    it('ohne aufloesbare Kategorie bleibt die Farbe null (SCSS-Default greift)', () => {
      const view = buildDayView(DAY, [occurrence({ category: null })], null);

      expect(view.chips[0].color).toBeNull();
    });

    it('ein ganztaegiger Termin traegt keine Uhrzeit', () => {
      const view = buildDayView(DAY, [occurrence({ allDay: true, startTime: null })], null);

      expect(view.chips[0].time).toBeNull();
      expect(view.chips[0].label).toBe('Zahnarzt');
    });

    it('ein Termin mit Uhrzeit traegt sie im Chip und vorn in der Beschriftung', () => {
      const view = buildDayView(DAY, [occurrence({ allDay: false, startTime: '18:30' })], null);

      expect(view.chips[0].time).toBe('18:30');
      expect(view.chips[0].label).toBe('18:30 Zahnarzt');
    });

    it('eine Uhrzeit an einem ganztaegigen Termin wird nicht angezeigt', () => {
      // allDay gewinnt: das Backend liefert startTime dann zwar als null, aber die
      // Anzeige darf sich nicht darauf verlassen - eine Uhrzeit an einem Ganztagestermin
      // waere eine Aussage, die der Termin nicht macht.
      const view = buildDayView(DAY, [occurrence({ allDay: true, startTime: '18:30' })], null);

      expect(view.chips[0].time).toBeNull();
      expect(view.chips[0].label).toBe('Zahnarzt');
    });

    it('deckelt die Initialen und zaehlt die uebrigen Personen', () => {
      const crowded = occurrence({
        persons: [
          { id: 10, displayName: 'Anna' }, { id: 11, displayName: 'anton' },
          { id: 12, displayName: 'Bert' }, { id: 13, displayName: 'Clara' }
        ]
      });

      const [chip] = buildDayView(DAY, [crowded], null).chips;

      expect(chip.initials).toEqual(['A', 'A']);
      expect(chip.initials.length).toBe(CHIP_INITIAL_LIMIT);
      expect(chip.hiddenPersons).toBe(2);
    });

    it('nennt in der Beschriftung alle Personen, auch die ohne Initiale', () => {
      // Der Titel wird im Raster oft abgeschnitten, und eine Initiale ist zwischen
      // "Anna" und "Anton" nicht zu unterscheiden.
      const crowded = occurrence({
        persons: [
          { id: 10, displayName: 'Anna' }, { id: 11, displayName: 'Anton' },
          { id: 12, displayName: 'Bert' }
        ]
      });

      const [chip] = buildDayView(DAY, [crowded], null).chips;

      expect(chip.label).toBe('Zahnarzt — Anna, Anton, Bert');
    });

    it('kombiniert Uhrzeit und Personen in der Beschriftung', () => {
      const abends = occurrence({
        allDay: false, startTime: '18:30', persons: [{ id: 11, displayName: 'Anna' }]
      });

      expect(buildDayView(DAY, [abends], null).chips[0].label).toBe('18:30 Zahnarzt — Anna');
    });

    it('ein Haushaltstermin hat keine Initialen und keinen Personen-Zusatz', () => {
      const [chip] = buildDayView(DAY, [occurrence({ persons: [] })], null).chips;

      expect(chip.initials).toEqual([]);
      expect(chip.hiddenPersons).toBe(0);
      expect(chip.label).toBe('Zahnarzt');
    });

    it('haelt das Vorkommen fuer den Bearbeiten-Klick am Chip', () => {
      const occ = occurrence({ eventId: 42 });

      expect(buildDayView(DAY, [occ], null).chips[0].occurrence).toBe(occ);
    });

    it('ein leerer Tag hat weder Chips noch overflow', () => {
      const view = buildDayView(DAY, [], null);

      expect(view.chips).toEqual([]);
      expect(view.overflow).toBe(0);
    });
  });
});
