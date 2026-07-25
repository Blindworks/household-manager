import { buildMonthGrid } from './month-grid.util';

describe('buildMonthGrid', () => {
  const today = new Date(2026, 6, 25); // 25.07.2026

  it('beginnt jede Woche am Montag', () => {
    // Juli 2026 beginnt an einem Mittwoch -> erste Zelle ist Montag, der 29.06.
    const grid = buildMonthGrid(2026, 7, today);
    expect(grid[0][0].isoDate).toBe('2026-06-29');
    expect(grid[0][0].inMonth).toBeFalse();
    expect(grid[0][2].isoDate).toBe('2026-07-01');
  });

  it('endet mit dem Sonntag der letzten Monatswoche', () => {
    const grid = buildMonthGrid(2026, 7, today);
    const lastWeek = grid[grid.length - 1];
    expect(lastWeek[6].isoDate).toBe('2026-08-02');
    expect(grid.flat().filter(d => d.inMonth).length).toBe(31);
  });

  it('markiert heute', () => {
    const grid = buildMonthGrid(2026, 7, today);
    const todayCell = grid.flat().find(d => d.isoDate === '2026-07-25');
    expect(todayCell?.isToday).toBeTrue();
  });

  it('hat immer Wochen mit sieben Tagen', () => {
    for (const month of [1, 2, 6, 12]) {
      for (const week of buildMonthGrid(2026, month, today)) {
        expect(week.length).toBe(7);
      }
    }
  });
});
