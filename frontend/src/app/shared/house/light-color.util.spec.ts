import { COLOR_SWATCHES, activeSwatch, colorTempRange, sliderValue } from './light-color.util';

describe('light-color.util', () => {
  const range = { min: 2500, max: 6500 };

  it('hat acht Farbfelder in fester Reihenfolge', () => {
    expect(COLOR_SWATCHES.map(s => s.id)).toEqual(['warm', 'red', 'orange', 'yellow', 'green', 'cyan', 'blue', 'purple']);
    expect(COLOR_SWATCHES[0].request).toEqual({ colorTemp: 2700 });
    expect(COLOR_SWATCHES.find(s => s.id === 'blue')!.request).toEqual({ hue: 225, saturation: 100 });
  });

  describe('colorTempRange', () => {
    it('liest den gemeldeten Bereich', () => {
      expect(colorTempRange({ colorTempRangeMin: 2700, colorTempRangeMax: 6000 })).toEqual({ min: 2700, max: 6000 });
    });

    it('faellt auf den Standard zurueck', () => {
      const standard = { min: 2500, max: 6500 };
      expect(colorTempRange(null)).toEqual(standard);
      expect(colorTempRange({ colorTempRangeMin: 'x' })).toEqual(standard);
      expect(colorTempRange({ colorTempRangeMin: 5000, colorTempRangeMax: 5000 })).toEqual(standard);
    });
  });

  describe('activeSwatch', () => {
    it('misst den Farbton zirkulaer', () => {
      expect(activeSwatch({ hue: 350, saturation: 90, colorTemp: 0 })).toBe('red');
    });

    it('findet Blau im Farbmodus', () => {
      expect(activeSwatch({ hue: 220, saturation: 100, colorTemp: null })).toBe('blue');
    });

    it('gibt der Farbtemperatur Vorrang', () => {
      expect(activeSwatch({ hue: 30, saturation: 100, colorTemp: 2700 })).toBe('warm');
    });

    it('toleriert 150 K um Warmweiss', () => {
      expect(activeSwatch({ hue: null, saturation: null, colorTemp: 2850 })).toBe('warm');
      expect(activeSwatch({ hue: null, saturation: null, colorTemp: 2851 })).toBeNull();
    });

    it('ist ohne Angaben null', () => {
      expect(activeSwatch({ hue: null, saturation: null, colorTemp: null })).toBeNull();
    });
  });

  describe('sliderValue', () => {
    it('klemmt auf den Bereich', () => {
      expect(sliderValue({ hue: null, saturation: null, colorTemp: 7000 }, range)).toBe(6500);
    });

    it('nimmt ohne Wert die gerundete Mitte', () => {
      expect(sliderValue({ hue: 10, saturation: 10, colorTemp: 0 }, range)).toBe(4500);
    });
  });
});
