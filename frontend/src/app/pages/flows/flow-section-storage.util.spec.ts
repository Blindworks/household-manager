import { COLLAPSED_SECTIONS_KEY, loadCollapsedSections, saveCollapsedSections } from './flow-section-storage.util';

describe('flow section storage', () => {
  afterEach(() => localStorage.removeItem(COLLAPSED_SECTIONS_KEY));

  it('round-trips the collapsed section titles', () => {
    saveCollapsedSections(new Set(['Licht', 'Taster']));

    expect([...loadCollapsedSections()].sort()).toEqual(['Licht', 'Taster']);
  });

  it('returns an empty set for nothing stored', () => {
    expect(loadCollapsedSections().size).toBe(0);
  });

  it('ignores garbage in storage', () => {
    localStorage.setItem(COLLAPSED_SECTIONS_KEY, '{kaputt');
    expect(loadCollapsedSections().size).toBe(0);

    localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify(['Licht', 42, null]));
    expect([...loadCollapsedSections()]).toEqual(['Licht']);
  });

  it('survives a throwing storage', () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('blocked');
    spyOn(Storage.prototype, 'setItem').and.throwError('blocked');

    expect(loadCollapsedSections().size).toBe(0);
    expect(() => saveCollapsedSections(new Set(['Licht']))).not.toThrow();
  });
});
