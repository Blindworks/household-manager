import { iaqLevel } from './alexa-air-quality.model';

describe('iaqLevel', () => {
  it('maps scores to Amazon IAQ levels', () => {
    expect(iaqLevel(0)).toBe('good');
    expect(iaqLevel(50)).toBe('good');
    expect(iaqLevel(51)).toBe('moderate');
    expect(iaqLevel(100)).toBe('moderate');
    expect(iaqLevel(101)).toBe('bad');
  });

  it('returns unknown for missing values', () => {
    expect(iaqLevel(null)).toBe('unknown');
    expect(iaqLevel(Number.NaN)).toBe('unknown');
  });
});
