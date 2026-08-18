/** Farbton (0-360°) und Saettigung (0-100%), wie sie Tapo-Lampen fuer den Farbmodus erwarten. */
export interface HueSaturation {
  hue: number;
  saturation: number;
}

/**
 * Wandelt eine `#rrggbb`-Farbe (z. B. aus einem `<input type="color">`) in das Farbton/Saettigung-
 * Paar um, das Tapo-Lampen fuer den Farbmodus annehmen.
 *
 * Die dritte HSV-Komponente (Hellwert/Value) wird bewusst verworfen: Der eigene Helligkeits-Regler
 * der Lampe ist die einzige Quelle fuer die Helligkeit, eine Farbauswahl darf sie nicht nebenbei
 * mitveraendern - genau deshalb sendet der Aufrufer hue/saturation immer getrennt von brightness.
 */
export function hexToHueSaturation(hex: string): HueSaturation {
  const { r, g, b } = parseHexColor(hex);

  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;

  let hue = 0;
  if (delta !== 0) {
    if (max === r) {
      hue = 60 * (((g - b) / delta) % 6);
    } else if (max === g) {
      hue = 60 * ((b - r) / delta + 2);
    } else {
      hue = 60 * ((r - g) / delta + 4);
    }
  }
  if (hue < 0) {
    hue += 360;
  }

  const saturation = max === 0 ? 0 : delta / max;

  return {
    hue: Math.round(hue) % 360,
    saturation: Math.round(saturation * 100)
  };
}

/** Zerlegt eine Hex-Farbe (mit oder ohne '#', 3- oder 6-stellig) in RGB-Anteile im Bereich 0-1. */
function parseHexColor(hex: string): { r: number; g: number; b: number } {
  const stripped = hex.trim().replace(/^#/, '');
  const normalized = stripped.length === 3
    ? stripped.split('').map(digit => digit + digit).join('')
    : stripped;
  const value = parseInt(normalized, 16);

  return {
    r: ((value >> 16) & 255) / 255,
    g: ((value >> 8) & 255) / 255,
    b: (value & 255) / 255
  };
}
