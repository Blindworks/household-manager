import { hexToHueSaturation } from './color-conversion.util';

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
