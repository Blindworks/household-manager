import { formatBasePrice, formatReference, formatSaving, formatValidity, parseWordList } from './grocery-format.util';

describe('grocery-format.util', () => {
  it('formatiert den Grundpreis mit Bezugseinheit', () => {
    expect(formatBasePrice(1.43, 'LITER')).toBe('1,43 €/l');
    expect(formatBasePrice(5, 'KILOGRAM')).toBe('5,00 €/kg');
    expect(formatBasePrice(0.3, 'PIECE')).toBe('0,30 €/Stück');
  });

  it('formatiert die Gueltigkeit als bis, solange das Angebot gilt', () => {
    const today = new Date(2026, 10, 17);
    expect(formatValidity('2026-11-16', '2026-11-21', today)).toBe('bis Sa 21.11.');
  });

  it('formatiert die Gueltigkeit als ab, wenn das Angebot noch nicht gilt', () => {
    const today = new Date(2026, 10, 17);
    expect(formatValidity('2026-11-19', '2026-11-25', today)).toBe('ab Do 19.11.');
  });

  it('formatiert die Ersparnis mit Minuszeichen', () => {
    expect(formatSaving(23)).toBe('−23 %');
    expect(formatSaving(null)).toBe('');
  });

  it('formatiert die Vergleichsbasis je Art', () => {
    expect(formatReference({ reference: 1.85, referenceKind: 'USUAL', baseUnit: 'LITER' })).toBe('üblich 1,85 €/l');
    expect(formatReference({ reference: 2, referenceKind: 'OWN_NORMAL_PRICE', baseUnit: 'LITER' }))
      .toBe('dein Normalpreis 2,00 €/l');
    expect(formatReference({ reference: 1.1, referenceKind: 'LOWEST_30_DAYS', baseUnit: 'KILOGRAM' }))
      .toBe('30-Tage-Tiefstpreis 1,10 €/kg');
    expect(formatReference({ reference: null, referenceKind: null, baseUnit: 'LITER' })).toBe('');
  });

  it('zerlegt Wortlisten ohne Leereintraege', () => {
    expect(parseWordList(' Cola, Coca Cola ,, ')).toEqual(['Cola', 'Coca Cola']);
    expect(parseWordList('')).toEqual([]);
  });
});
