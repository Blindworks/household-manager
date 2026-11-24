import { WeatherForecastHour, WeatherOverview, WeatherWarning } from '../models/weather.model';
import { warnSeverity, WarnSeverity, weatherMaterialSymbol, weatherSymbol } from './weather-icon.util';

export interface WeatherReportMetric {
  readonly icon: string;
  readonly label: string;
  readonly value: string;
}

export interface WeatherReportDay {
  readonly name: string;
  readonly icon: string;
  readonly label: string;
  readonly min: string;
  readonly max: string;
  readonly precipitation: string;
}

export interface WeatherReportWarning {
  readonly severity: WarnSeverity;
  readonly title: string;
  readonly period: string;
  readonly text: string | null;
  readonly instruction: string | null;
}

/** Inhalt des Wetterbericht-Dialogs im Dashboard. */
export interface WeatherReport {
  readonly stationId: string;
  readonly summary: string;
  readonly metrics: readonly WeatherReportMetric[];
  readonly days: readonly WeatherReportDay[];
  readonly warnings: readonly WeatherReportWarning[];
  /** ECharts-Optionen; `null` ohne Stundenvorhersage. */
  readonly chartOptions: Record<string, unknown> | null;
}

const TEMPERATURE_COLOR = '#f59e0b';
const RAIN_COLOR = '#0ea5e9';
const AXIS_COLOR = '#64748b';

/** Einzige Aufbereitung des Wetterberichts aus `GET /v1/weather/overview`. */
export function buildWeatherReport(overview: WeatherOverview, now: Date): WeatherReport {
  return {
    stationId: overview.stationId,
    summary: summarize(overview, now),
    metrics: buildMetrics(overview),
    days: (overview.dailyForecast ?? []).map(day => ({
      name: dayName(day.date, now),
      icon: weatherMaterialSymbol(day.icon),
      label: labelOf(day.icon),
      min: formatDegrees(day.temperatureMin),
      max: formatDegrees(day.temperatureMax),
      precipitation: day.precipitation === null ? '' : `${formatDecimal(day.precipitation)} mm`
    })),
    warnings: overview.warnings.map(buildWarning),
    chartOptions: buildChartOptions(overview.hourlyForecast)
  };
}

function summarize(overview: WeatherOverview, now: Date): string {
  const label = labelOf(overview.current?.icon);
  return [
    ...(label ? [`Aktuell: ${label}.`] : []),
    overview.nextRain
      ? `Nächster Niederschlag ${dayName(overview.nextRain.slice(0, 10), now).toLowerCase()} ab ${overview.nextRain.slice(11, 16)} Uhr.`
      : 'Kein Niederschlag in den nächsten 24 Stunden erwartet.'
  ].join(' ');
}

function buildMetrics(overview: WeatherOverview): WeatherReportMetric[] {
  const current = overview.current;
  const metrics: WeatherReportMetric[] = [];
  const add = (icon: string, label: string, value: number | null | undefined, unit: string, digits: number): void => {
    if (value !== null && value !== undefined) {
      metrics.push({ icon, label, value: `${formatDecimal(value, digits)} ${unit}` });
    }
  };
  add('thermostat', 'Temperatur', current?.temperature, '°C', 1);
  add('humidity_percentage', 'Luftfeuchte', current?.humidity, '%', 0);
  add('air', 'Wind heute', current?.windSpeed, 'km/h', 0);
  add('speed', 'Luftdruck', current?.pressure, 'hPa', 0);
  return metrics;
}

function buildWarning(warning: WeatherWarning): WeatherReportWarning {
  const from = warning.start ? formatDateTime(warning.start) : null;
  const to = warning.end ? formatDateTime(warning.end) : null;
  return {
    severity: warnSeverity(warning.level),
    title: warning.headline ?? warning.event ?? 'Wetterwarnung',
    period: [from && `ab ${from}`, to && `bis ${to}`].filter(Boolean).join(' '),
    text: warning.description,
    instruction: warning.instruction
  };
}

function buildChartOptions(hours: WeatherForecastHour[]): Record<string, unknown> | null {
  if (!hours.length) {
    return null;
  }
  const maxRain = Math.max(0, ...hours.map(hour => hour.precipitation ?? 0));
  return {
    grid: { left: 44, right: 44, top: 40, bottom: 28 },
    tooltip: { trigger: 'axis' },
    legend: { top: 0, textStyle: { color: AXIS_COLOR, fontSize: 11 } },
    xAxis: {
      type: 'category',
      data: hours.map(hour => hour.time.slice(11, 16)),
      axisLabel: { color: AXIS_COLOR, fontSize: 11 }
    },
    yAxis: [
      {
        type: 'value',
        scale: true,
        name: '°C',
        nameTextStyle: { color: AXIS_COLOR, fontSize: 11 },
        axisLabel: { color: AXIS_COLOR },
        splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } }
      },
      {
        type: 'value',
        min: 0,
        // Mindesthoehe, damit Nieselreste nicht wie ein Wolkenbruch aussehen.
        max: Math.max(1, Math.ceil(maxRain)),
        name: 'mm',
        nameTextStyle: { color: AXIS_COLOR, fontSize: 11 },
        axisLabel: { color: AXIS_COLOR },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: 'Temperatur',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: hours.map(hour => hour.temperature),
        lineStyle: { width: 2.5, color: TEMPERATURE_COLOR },
        itemStyle: { color: TEMPERATURE_COLOR },
        tooltip: { valueFormatter: (value: number | null) => formatWithUnit(value, '°C') }
      },
      {
        name: 'Niederschlag',
        type: 'bar',
        yAxisIndex: 1,
        data: hours.map(hour => hour.precipitation),
        itemStyle: { color: RAIN_COLOR },
        tooltip: { valueFormatter: (value: number | null) => formatWithUnit(value, 'mm') }
      }
    ]
  };
}

/** Text zum DWD-Code; unbekannte Codes bleiben leer, statt „Unbekannt“ zu behaupten. */
function labelOf(icon: number | null | undefined): string {
  const label = weatherSymbol(icon).label;
  return label === weatherSymbol(null).label ? '' : label;
}

function dayName(date: string, now: Date): string {
  const [year, month, day] = date.split('-').map(Number);
  const target = new Date(year, month - 1, day);
  const isSameDay = (offset: number): boolean =>
    target.getTime() === new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset).getTime();
  if (isSameDay(0)) {
    return 'Heute';
  }
  return isSameDay(1) ? 'Morgen' : target.toLocaleDateString('de-DE', { weekday: 'long' });
}

function formatDateTime(value: string): string {
  return `${value.slice(8, 10)}.${value.slice(5, 7)}. ${value.slice(11, 16)} Uhr`;
}

function formatDegrees(value: number | null): string {
  return value === null ? '–' : `${formatDecimal(value, 0)}°`;
}

function formatWithUnit(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? '–' : `${formatDecimal(value)} ${unit}`;
}

function formatDecimal(value: number, digits = 1): string {
  // `|| 0` macht aus gerundetem -0 eine 0.
  const rounded = Number(value.toFixed(digits)) || 0;
  return rounded.toLocaleString('de-DE', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
    useGrouping: false
  });
}
