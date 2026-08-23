import {
  PET_FOOD_CRITICAL_CANS,
  PET_FOOD_WARN_PERCENT,
  petFoodBarWidth,
  petFoodTone
} from './pet-food-level.util';

describe('pet-food-level.util', () => {
  it('meldet kritisch unterhalb der Dosenschwelle', () => {
    expect(petFoodTone({ cansRemaining: 6.5, percent: 14 })).toBe('critical');
  });

  it('meldet genau auf der Dosenschwelle nicht mehr kritisch', () => {
    // Die Flow-Bedingung lautet "< 7", nicht "<= 7" - die Grenze muss gleich liegen.
    expect(petFoodTone({ cansRemaining: PET_FOOD_CRITICAL_CANS, percent: 15 })).toBe('warn');
  });

  it('warnt unterhalb des Fuellstands-Schwellwerts', () => {
    expect(petFoodTone({ cansRemaining: 10, percent: PET_FOOD_WARN_PERCENT - 1 })).toBe('warn');
  });

  it('meldet ab dem Fuellstands-Schwellwert normal', () => {
    expect(petFoodTone({ cansRemaining: 12, percent: PET_FOOD_WARN_PERCENT })).toBe('ok');
  });

  it('klemmt die Balkenbreite auf 0 bis 100', () => {
    expect(petFoodBarWidth(-5)).toBe(0);
    expect(petFoodBarWidth(140)).toBe(100);
    expect(petFoodBarWidth(63)).toBe(63);
  });
});
