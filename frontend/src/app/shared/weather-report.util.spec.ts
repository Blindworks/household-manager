import { WeatherOverview } from '../models/weather.model';
import { buildWeatherReport } from './weather-report.util';

describe('buildWeatherReport', () => {
  const now = new Date(2026, 10, 24, 6, 40);
  const overview = (overrides: Partial<WeatherOverview> = {}): WeatherOverview => ({
    stationId: '10637', current: null, hourlyForecast: [], warnings: [], nextRain: null, ...overrides
  });

  it('benennt die Tage und rundet ohne minus null', () => {
    const day = { temperatureMin: -0.3, temperatureMax: 1.7, precipitation: 0.4, windGust: null, icon: 2 };
    const report = buildWeatherReport(overview({
      dailyForecast: [
        { ...day, date: '2026-11-24' },
        { ...day, date: '2026-11-25' },
        { ...day, date: '2026-11-26', temperatureMax: null, precipitation: null, icon: 99 }
      ]
    }), now);

    expect(report.days.map(d => d.name)).toEqual(['Heute', 'Morgen', 'Donnerstag']);
    expect(report.days[0]).toEqual(jasmine.objectContaining({ min: '0°', max: '2°', precipitation: '0,4 mm' }));
    expect(report.days[2]).toEqual(jasmine.objectContaining({ max: '–', precipitation: '', label: '' }));
  });

  it('nennt den naechsten Niederschlag', () => {
    expect(buildWeatherReport(overview({ nextRain: '2026-11-25T03:00:00' }), now).summary)
      .toBe('Nächster Niederschlag morgen ab 03:00 Uhr.');
    expect(buildWeatherReport(overview(), now).summary).toContain('Kein Niederschlag');
  });

  it('kommt ohne aktuelle Werte, Stunden und Tage aus', () => {
    const report = buildWeatherReport(overview(), now);

    expect(report.metrics).toEqual([]);
    expect(report.days).toEqual([]);
    expect(report.chartOptions).toBeNull();
  });

  it('bereitet Warnungen mit Zeitraum auf', () => {
    const report = buildWeatherReport(overview({
      warnings: [{
        warnId: 1, event: 'FROST', level: 2, headline: null, description: 'Leichter Frost', instruction: null,
        start: '2026-11-24T18:00:00', end: '2026-11-25T09:00:00'
      }]
    }), now);

    expect(report.warnings[0]).toEqual({
      severity: 'moderate', title: 'FROST', period: 'ab 24.11. 18:00 Uhr bis 25.11. 09:00 Uhr',
      text: 'Leichter Frost', instruction: null
    });
  });
});
