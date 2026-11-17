import { RainRadarForecast } from '../models/rain-radar.model';

/** Eine Kennzahl oben im Regenradar-Dialog. */
export interface RainRadarMetric {
  readonly icon: string;
  readonly label: string;
  readonly value: string;
  readonly hint: string;
}

/** Inhalt des Regenradar-Dialogs im Dashboard. */
export interface RainRadarDetail {
  readonly summary: string;
  readonly metrics: readonly RainRadarMetric[];
  readonly measuredLabel: string;
  readonly source: string;
  /** ECharts-Optionen; `null`, wenn es keine Bilder gibt. */
  readonly chartOptions: Record<string, unknown> | null;
}

interface TimedFrame {
  at: number;
  mmPerHour: number;
  forecast: boolean;
}

const MEASURED_COLOR = '#2563eb';
const FORECAST_COLOR = '#60a5fa';
const THRESHOLD_COLOR = '#94a3b8';
/** Mindesthoehe der Y-Achse, damit Nieselreste nicht wie ein Wolkenbruch aussehen. */
const MIN_AXIS_MM_PER_HOUR = 1;

/**
 * Grobe Einordnung der Intensitaet nach den DWD-Stufen (leicht < 2,5 mm/h, maessig < 10, stark).
 * Unter 0,1 mm/h gilt als trocken — das Radar rauscht dort staendig.
 */
export function rainIntensityLabel(mmPerHour: number): string {
  if (mmPerHour < 0.1) {
    return 'trocken';
  }
  if (mmPerHour < 2.5) {
    return 'leicht';
  }
  return mmPerHour < 10 ? 'mäßig' : 'stark';
}

/**
 * Einzige Aufbereitung des Regenradar-Dialogs: Kennzahlen, Satz und Diagramm aus
 * `GET /v1/rain-radar/forecast`. „Jetzt“ ist die juengste Messung; als Ausblick zaehlen nur
 * Vorhersagebilder nach `now`, damit ein bis zu 5 Minuten alter Abruf keine vergangenen
 * Bilder als Zukunft zeigt.
 */
export function buildRainRadarDetail(forecast: RainRadarForecast, now: number): RainRadarDetail {
  const threshold = forecast.thresholdMmPerHour;
  const frames: TimedFrame[] = [];
  for (const frame of forecast.frames) {
    const at = parseLocal(frame.time);
    if (at !== null) {
      frames.push({ at, mmPerHour: frame.mmPerHour, forecast: frame.forecast });
    }
  }
  frames.sort((a, b) => a.at - b.at);
  const measuredAt = parseLocal(forecast.measuredAt);
  const current = forecast.mmPerHour;

  if (frames.length === 0 || current === null || measuredAt === null) {
    return {
      summary: 'Noch keine Radardaten – das Backend fragt das Radar alle 5 Minuten ab.',
      metrics: [],
      measuredLabel: '',
      source: forecast.source,
      chartOptions: null
    };
  }

  const upcoming = frames.filter(frame => frame.forecast && frame.at > now);
  const peak = upcoming.reduce((max, frame) => Math.max(max, frame.mmPerHour), 0);
  const raining = current >= threshold;
  const expectedAt = parseLocal(forecast.expectedRainAt);
  const dryFrom = raining ? upcoming.find(frame => frame.mmPerHour < threshold)?.at ?? null : null;
  const turnAt = raining ? dryFrom : expectedAt === null ? null : Math.max(expectedAt, now);

  return {
    summary: summarize(raining, current, expectedAt, dryFrom, peak, upcoming.length > 0, now),
    metrics: [
      {
        icon: raining ? 'rainy' : 'umbrella',
        label: 'Jetzt',
        value: raining ? `${formatMm(current)} mm/h` : 'trocken',
        hint: raining ? rainIntensityLabel(current) : `${formatMm(current)} mm/h`
      },
      {
        icon: 'schedule',
        label: raining ? 'Trocken ab' : 'Regen ab',
        value: turnAt === null ? 'nicht in 2 h' : formatClock(turnAt),
        hint: turnAt === null ? 'laut Vorhersage' : relativeMinutes(turnAt, now)
      },
      {
        icon: 'water_drop',
        label: 'Spitze 2 h',
        value: `${formatMm(peak)} mm/h`,
        hint: rainIntensityLabel(peak)
      }
    ],
    measuredLabel: `Messung ${formatClock(measuredAt)} Uhr`,
    source: forecast.source,
    chartOptions: buildChartOptions(frames, threshold)
  };
}

function summarize(raining: boolean, current: number, expectedAt: number | null, dryFrom: number | null,
                   peak: number, hasForecast: boolean, now: number): string {
  if (raining) {
    const intensity = `Es regnet am Haus (${formatMm(current)} mm/h, ${rainIntensityLabel(current)}).`;
    if (!hasForecast) {
      return intensity;
    }
    return dryFrom === null
      ? `${intensity} Laut Vorhersage hält der Regen die nächsten zwei Stunden an.`
      : `${intensity} Voraussichtlich trocken ab ${formatClock(dryFrom)} Uhr.`;
  }
  if (expectedAt !== null) {
    const when = expectedAt <= now ? 'jetzt' : `ab ca. ${formatClock(expectedAt)} Uhr`;
    return `Regen ${when} erwartet, bis zu ${formatMm(peak)} mm/h (${rainIntensityLabel(peak)}).`;
  }
  return 'Kein Regen in den nächsten zwei Stunden erwartet.';
}

function buildChartOptions(frames: TimedFrame[], threshold: number): Record<string, unknown> {
  const measured = frames.filter(frame => !frame.forecast);
  const lastMeasured = measured[measured.length - 1];
  // Die Vorhersage setzt am letzten Messpunkt an, sonst klafft zwischen beiden Linien eine Luecke.
  const forecast = [
    ...(lastMeasured ? [lastMeasured] : []),
    ...frames.filter(frame => frame.forecast && (!lastMeasured || frame.at > lastMeasured.at))
  ];
  const maxValue = frames.reduce((max, frame) => Math.max(max, frame.mmPerHour), threshold);
  const first = frames[0].at;
  const last = frames[frames.length - 1].at;

  return {
    grid: { left: 48, right: 16, top: 40, bottom: 32 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value: number) => `${formatMm(value)} mm/h`
    },
    legend: { top: 0, textStyle: { color: '#94a3b8', fontSize: 11 } },
    xAxis: {
      type: 'time',
      min: first,
      max: last,
      axisLabel: { color: '#94a3b8', fontSize: 11, formatter: (value: number) => formatClock(value) }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: Math.max(MIN_AXIS_MM_PER_HOUR, Math.ceil(maxValue * 1.2 * 10) / 10),
      name: 'mm/h',
      nameTextStyle: { color: '#94a3b8', fontSize: 11 },
      axisLabel: { color: '#94a3b8', formatter: (value: number) => formatMm(value) },
      splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
    },
    series: [
      {
        name: 'Gemessen',
        type: 'line',
        step: 'end',
        showSymbol: false,
        data: measured.map(frame => [frame.at, frame.mmPerHour]),
        lineStyle: { width: 2.5, color: MEASURED_COLOR },
        itemStyle: { color: MEASURED_COLOR },
        areaStyle: { color: 'rgba(37, 99, 235, 0.22)' }
      },
      {
        name: 'Vorhersage',
        type: 'line',
        step: 'end',
        showSymbol: false,
        data: forecast.map(frame => [frame.at, frame.mmPerHour]),
        lineStyle: { width: 2, color: FORECAST_COLOR, type: 'dashed' },
        itemStyle: { color: FORECAST_COLOR },
        areaStyle: { color: 'rgba(96, 165, 250, 0.14)' }
      },
      {
        name: 'Regenschwelle',
        type: 'line',
        showSymbol: false,
        silent: true,
        data: [[first, threshold], [last, threshold]],
        lineStyle: { width: 1, color: THRESHOLD_COLOR, type: 'dotted' },
        itemStyle: { color: THRESHOLD_COLOR },
        tooltip: { show: false }
      }
    ]
  };
}

function relativeMinutes(time: number, now: number): string {
  const minutes = Math.max(0, Math.round((time - now) / 60000));
  return minutes === 0 ? 'jetzt' : `in ${minutes} Min.`;
}

/** Backend liefert lokale Haushaltszeit ohne Zone, Browser und Tablet laufen in derselben Zone. */
function parseLocal(value: string | null | undefined): number | null {
  if (!value) {
    return null;
  }
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? null : time;
}

function formatClock(time: number): string {
  const date = new Date(time);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function formatMm(value: number): string {
  return value.toLocaleString('de-DE', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}
