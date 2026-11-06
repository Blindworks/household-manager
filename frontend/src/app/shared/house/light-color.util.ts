export type LightColorRequest = { hue: number; saturation: number } | { colorTemp: number };

export interface ColorSwatch {
  id: 'warm' | 'red' | 'orange' | 'yellow' | 'green' | 'cyan' | 'blue' | 'purple';
  label: string;
  request: LightColorRequest;
  /** Anzeigefarbe des Felds. */
  css: string;
}

export const WARM_WHITE_K = 2700;
export const COLOR_TEMP_STEP_K = 100;
const WARM_TOLERANCE_K = 150;
const DEFAULT_RANGE: ColorTempRange = { min: 2500, max: 6500 };

function hueSwatch(id: ColorSwatch['id'], label: string, hue: number): ColorSwatch {
  return { id, label, request: { hue, saturation: 100 }, css: `hsl(${hue} 100% 50%)` };
}

export const COLOR_SWATCHES: readonly ColorSwatch[] = [
  { id: 'warm', label: 'Warmweiß', request: { colorTemp: WARM_WHITE_K }, css: '#ffd59a' },
  hueSwatch('red', 'Rot', 0),
  hueSwatch('orange', 'Orange', 30),
  hueSwatch('yellow', 'Gelb', 55),
  hueSwatch('green', 'Grün', 120),
  hueSwatch('cyan', 'Türkis', 175),
  hueSwatch('blue', 'Blau', 225),
  hueSwatch('purple', 'Lila', 280),
];

export interface ColorTempRange { min: number; max: number }

export function colorTempRange(metadata: Record<string, unknown> | null | undefined): ColorTempRange {
  const min = metadata?.['colorTempRangeMin'];
  const max = metadata?.['colorTempRangeMax'];
  if (typeof min === 'number' && typeof max === 'number' && Number.isFinite(min) && Number.isFinite(max) && min < max) {
    return { min, max };
  }
  return { ...DEFAULT_RANGE };
}

export interface LightColorState { hue: number | null; saturation: number | null; colorTemp: number | null }

function hueDistance(a: number, b: number): number {
  const diff = Math.abs(a - b) % 360;
  return Math.min(diff, 360 - diff);
}

export function activeSwatch(state: LightColorState): ColorSwatch['id'] | null {
  if (state.colorTemp !== null && state.colorTemp > 0) {
    return Math.abs(state.colorTemp - WARM_WHITE_K) <= WARM_TOLERANCE_K ? 'warm' : null;
  }
  if (state.hue === null) {
    return null;
  }
  const hue = state.hue;
  let best: ColorSwatch['id'] | null = null;
  let bestDistance = Infinity;
  for (const swatch of COLOR_SWATCHES) {
    if ('colorTemp' in swatch.request) {
      continue;
    }
    const distance = hueDistance(hue, swatch.request.hue);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = swatch.id;
    }
  }
  return best;
}

export function sliderValue(state: LightColorState, range: ColorTempRange): number {
  if (state.colorTemp !== null && state.colorTemp > 0) {
    return Math.min(range.max, Math.max(range.min, state.colorTemp));
  }
  return Math.round((range.min + range.max) / 2 / 100) * 100;
}
