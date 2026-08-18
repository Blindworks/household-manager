import { hexToHueSaturation, hueSaturationToHex } from './color-conversion.util';

describe('hexToHueSaturation', () => {
  it('bildet reines Rot auf Farbton 0 bei voller Saettigung ab', () => {
    expect(hexToHueSaturation('#ff0000')).toEqual({ hue: 0, saturation: 100 });
  });

  it('bildet reines Gruen auf Farbton 120 bei voller Saettigung ab', () => {
    expect(hexToHueSaturation('#00ff00')).toEqual({ hue: 120, saturation: 100 });
  });

  it('bildet reines Blau auf Farbton 240 bei voller Saettigung ab', () => {
    expect(hexToHueSaturation('#0000ff')).toEqual({ hue: 240, saturation: 100 });
  });

  it('bildet Cyan auf Farbton 180 ab', () => {
    expect(hexToHueSaturation('#00ffff')).toEqual({ hue: 180, saturation: 100 });
  });

  it('bildet Magenta auf Farbton 300 ab', () => {
    expect(hexToHueSaturation('#ff00ff')).toEqual({ hue: 300, saturation: 100 });
  });

  it('bildet Gelb auf Farbton 60 ab', () => {
    expect(hexToHueSaturation('#ffff00')).toEqual({ hue: 60, saturation: 100 });
  });

  it('behandelt Weiss als saettigungslos', () => {
    expect(hexToHueSaturation('#ffffff')).toEqual({ hue: 0, saturation: 0 });
  });

  it('behandelt Schwarz als saettigungslos', () => {
    expect(hexToHueSaturation('#000000')).toEqual({ hue: 0, saturation: 0 });
  });

  it('behandelt Grau als saettigungslos', () => {
    expect(hexToHueSaturation('#808080')).toEqual({ hue: 0, saturation: 0 });
  });

  it('rechnet einen gemischten Blauton korrekt um', () => {
    // r=0.2 g=0.4 b=0.8 (max) -> hue = 60*((r-g)/delta+4) = 220, sat = delta/max = 0.75
    expect(hexToHueSaturation('#3366cc')).toEqual({ hue: 220, saturation: 75 });
  });

  it('behandelt den negativen Zwischenwert bei rotdominantem Blauanteil korrekt (Wraparound)', () => {
    // r dominant, b > g -> Zwischenwert wird negativ und muss auf 360 aufaddiert werden
    expect(hexToHueSaturation('#cc0033')).toEqual({ hue: 345, saturation: 100 });
  });

  it('akzeptiert 3-stellige Kurzform', () => {
    expect(hexToHueSaturation('#f00')).toEqual({ hue: 0, saturation: 100 });
  });

  it('akzeptiert Hex-Werte ohne fuehrendes Rautezeichen', () => {
    expect(hexToHueSaturation('00ff00')).toEqual({ hue: 120, saturation: 100 });
  });
});

describe('hueSaturationToHex', () => {
  it('bildet Farbton 0 bei voller Saettigung auf reines Rot ab', () => {
    expect(hueSaturationToHex(0, 100)).toBe('#ff0000');
  });

  it('bildet Farbton 120 bei voller Saettigung auf reines Gruen ab', () => {
    expect(hueSaturationToHex(120, 100)).toBe('#00ff00');
  });

  it('bildet Farbton 240 bei voller Saettigung auf reines Blau ab', () => {
    expect(hueSaturationToHex(240, 100)).toBe('#0000ff');
  });

  it('bildet Farbton 60 auf Gelb ab', () => {
    expect(hueSaturationToHex(60, 100)).toBe('#ffff00');
  });

  it('bildet Farbton 180 auf Cyan ab', () => {
    expect(hueSaturationToHex(180, 100)).toBe('#00ffff');
  });

  it('bildet Farbton 300 auf Magenta ab', () => {
    expect(hueSaturationToHex(300, 100)).toBe('#ff00ff');
  });

  it('bildet Saettigung 0 unabhaengig vom Farbton auf Weiss ab (voller Hellwert)', () => {
    expect(hueSaturationToHex(200, 0)).toBe('#ffffff');
  });
});

describe('Rundweg hexToHueSaturation <-> hueSaturationToHex', () => {
  // Der Hellwert (V) wird von hexToHueSaturation bewusst verworfen und von hueSaturationToHex
  // bewusst fest auf 1 gesetzt - der Rundweg ueber die Geraete-Werte (hue/saturation) ist deshalb
  // verlustfrei, ein Rundweg ueber eine beliebige, vom Nutzer gewaehlte Hex-Farbe mit anderem
  // Hellwert waere es nicht (siehe Doku an hueSaturationToHex).
  const primaries: ReadonlyArray<[number, number]> = [
    [0, 100], [60, 100], [120, 100], [180, 100], [240, 100], [300, 100]
  ];

  for (const [hue, saturation] of primaries) {
    it(`haelt Farbton ${hue} / Saettigung ${saturation} stabil`, () => {
      const hex = hueSaturationToHex(hue, saturation);
      expect(hexToHueSaturation(hex)).toEqual({ hue, saturation });
    });
  }

  it('haelt einen gemischten Farbton (220/75) ueber den Rundweg stabil', () => {
    const hex = hueSaturationToHex(220, 75);
    expect(hexToHueSaturation(hex)).toEqual({ hue: 220, saturation: 75 });
  });

  it('haelt einen gemischten Farbton mit Wraparound (345/100) ueber den Rundweg stabil', () => {
    const hex = hueSaturationToHex(345, 100);
    expect(hexToHueSaturation(hex)).toEqual({ hue: 345, saturation: 100 });
  });
});
