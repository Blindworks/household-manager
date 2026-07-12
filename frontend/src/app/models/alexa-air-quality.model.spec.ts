import { iaqLevel } from './alexa-air-quality.model';

describe('iaqLevel', () => {
  it('maps scores to Amazon IAQ levels (higher is better)', () => {
    expect(iaqLevel(100)).toBe('good');
    expect(iaqLevel(80)).toBe('good');
    expect(iaqLevel(65)).toBe('good');
    expect(iaqLevel(64)).toBe('moderate');
    expect(iaqLevel(35)).toBe('moderate');
    expect(iaqLevel(34)).toBe('bad');
    expect(iaqLevel(0)).toBe('bad');
  });

  it('returns unknown for missing values', () => {
    expect(iaqLevel(null)).toBe('unknown');
    expect(iaqLevel(Number.NaN)).toBe('unknown');
  });
});
