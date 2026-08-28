import {
  PET_SUPPLY_CRITICAL_DAYS,
  PET_SUPPLY_WARN_PERCENT,
  petSupplyBarWidth,
  petSupplyTone,
  worstPetSupplyTone
} from './pet-supply-level.util';

describe('pet-supply-level.util', () => {
  it('meldet kritisch unterhalb der Reichweitenschwelle', () => {
    expect(petSupplyTone({ daysRemaining: 6, percent: 90 })).toBe('critical');
  });

  it('meldet genau auf der Schwelle nicht mehr kritisch', () => {
    // Die Flow-Bedingung lautet "< 7", nicht "<= 7" - die Grenze muss gleich liegen.
    expect(petSupplyTone({ daysRemaining: PET_SUPPLY_CRITICAL_DAYS, percent: 90 })).toBe('ok');
  });

  it('warnt unterhalb des Fuellstands-Schwellwerts trotz Reichweite', () => {
    expect(petSupplyTone({ daysRemaining: 20, percent: PET_SUPPLY_WARN_PERCENT - 1 })).toBe('warn');
  });

  it('bewertet Tabletten mit ihrem eigenen Tagesverbrauch', () => {
    // 12 Tabletten bei 2 pro Tag sind 6 Tage - kritisch, obwohl es mehr
    // Stueck sind als die alte Dosenschwelle von 7.
    expect(petSupplyTone({ daysRemaining: 6, percent: 20 })).toBe('critical');
  });

  it('klemmt die Balkenbreite auf 0..100', () => {
    expect(petSupplyBarWidth(140)).toBe(100);
    expect(petSupplyBarWidth(-5)).toBe(0);
  });

  it('nimmt den schlechtesten Ton einer Liste', () => {
    const voll = { daysRemaining: 40, percent: 90 };
    const knapp = { daysRemaining: 3, percent: 5 };
    expect(worstPetSupplyTone([voll, knapp])).toBe('critical');
    expect(worstPetSupplyTone([voll])).toBe('ok');
    expect(worstPetSupplyTone([])).toBe('ok');
  });
});
