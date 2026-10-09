import { ThermostatHistoryPoint } from '../../models/thermostat.model';

// Diagrammfarben stehen bewusst in der .ts-Datei (ECharts kennt keine CSS-
// Variablen) und sind in hell und dunkel gleich - wie bei den anderen
// Tablet-Ansichten.
const ACTUAL_COLOR = '#6fa8ff';
const TARGET_COLOR = '#94a3b8';
const HEATING_BAND = 'rgba(255, 154, 77, 0.18)';

/** Zusammenhaengende Zeitspannen mit offenem Ventil als [Beginn, Ende]. */
export function heatingSegments(points: ThermostatHistoryPoint[]): [string, string][] {
  const segments: [string, string][] = [];
  let start: string | null = null;
  let last: string | null = null;

  for (const point of points) {
    if (point.heating) {
      start ??= point.time;
      last = point.time;
      continue;
    }
    if (start !== null) {
      // Das Band reicht bis zum ersten Punkt OHNE Heizen: so hat auch ein
      // einzelner Heizpunkt sichtbare Breite.
      segments.push([start, point.time]);
      start = null;
    }
  }
  if (start !== null && last !== null) {
    segments.push([start, last]);
  }
  return segments;
}

/**
 * Sparkline einer Kachel: Ist-Linie, Soll als Stufenlinie, Heizphasen als
 * warmes Band. Ohne einen einzigen Messwert null - dann zeigt die Kachel einen
 * Hinweis statt eines leeren Diagramms.
 */
export function buildSparklineOptions(points: ThermostatHistoryPoint[]): Record<string, unknown> | null {
  if (!points.some(point => point.currentTemp !== null)) {
    return null;
  }

  return {
    animation: false,
    grid: { left: 0, right: 0, top: 6, bottom: 2 },
    xAxis: { type: 'time', show: false },
    yAxis: { type: 'value', show: false, scale: true },
    series: [
      {
        name: 'Soll',
        type: 'line',
        step: 'end',
        silent: true,
        showSymbol: false,
        connectNulls: false,
        data: points.map(point => [point.time, point.targetTemp]),
        lineStyle: { width: 1.2, color: TARGET_COLOR, opacity: 0.55 }
      },
      {
        name: 'Ist',
        type: 'line',
        smooth: true,
        silent: true,
        showSymbol: false,
        connectNulls: false,
        data: points.map(point => [point.time, point.currentTemp]),
        lineStyle: { width: 2.5, color: ACTUAL_COLOR },
        markArea: {
          silent: true,
          itemStyle: { color: HEATING_BAND },
          data: heatingSegments(points).map(([from, to]) => [{ xAxis: from }, { xAxis: to }])
        }
      }
    ]
  };
}
