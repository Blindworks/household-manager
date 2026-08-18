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

/**
 * Kehrfunktion zu {@link hexToHueSaturation}: wandelt ein Farbton/Saettigung-Paar, wie es das
 * Geraet als Ist-Wert meldet, in eine `#rrggbb`-Farbe fuer den `<input type="color">` um.
 *
 * Der Hellwert (V) ist am Geraet nicht Teil von hue/saturation (siehe {@link hexToHueSaturation})
 * und wird hier deshalb fest auf 1 (voll) gesetzt - der Rundweg hue/sat -> hex -> hue/sat ist damit
 * verlustfrei, der Rundweg ueber eine vom Nutzer gewaehlte Farbe mit anderem Hellwert nicht (der
 * Farbwaehler zeigt dann die "hellste" Version derselben Farbe). Das ist beabsichtigt: die
 * Helligkeit der Lampe bleibt allein Sache des Helligkeits-Reglers.
 */
export function hueSaturationToHex(hue: number, saturation: number): string {
  const h = normalizeHue(hue);
  const s = clampPercent(saturation) / 100;
  const v = 1;

  const c = v * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = v - c;

  let rPrime = 0;
  let gPrime = 0;
  let bPrime = 0;
  if (h < 60) {
    rPrime = c; gPrime = x; bPrime = 0;
  } else if (h < 120) {
    rPrime = x; gPrime = c; bPrime = 0;
  } else if (h < 180) {
    rPrime = 0; gPrime = c; bPrime = x;
  } else if (h < 240) {
    rPrime = 0; gPrime = x; bPrime = c;
  } else if (h < 300) {
    rPrime = x; gPrime = 0; bPrime = c;
  } else {
    rPrime = c; gPrime = 0; bPrime = x;
  }

  const r = Math.round((rPrime + m) * 255);
  const g = Math.round((gPrime + m) * 255);
  const b = Math.round((bPrime + m) * 255);

  return `#${toHexByte(r)}${toHexByte(g)}${toHexByte(b)}`;
}

function normalizeHue(hue: number): number {
  const wrapped = hue % 360;
  return wrapped < 0 ? wrapped + 360 : wrapped;
}

function clampPercent(value: number): number {
  return Math.min(100, Math.max(0, value));
}

function toHexByte(value: number): string {
  return Math.min(255, Math.max(0, value)).toString(16).padStart(2, '0');
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
