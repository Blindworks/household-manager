import { RainRadarForecast } from '../models/rain-radar.model';
import { buildRainRadarDetail, rainIntensityLabel } from './rain-radar-detail.util';

describe('buildRainRadarDetail', () => {
  const now = new Date('2026-11-17T15:00:00').getTime();

  function forecast(current: number, values: [string, number, boolean][],
                    expectedRainAt: string | null = null): RainRadarForecast {
    return {
      measuredAt: '2026-11-17T14:55:00',
      mmPerHour: current,
      raining: current >= 0.3,
      expectedRainAt,
      peakNextHourMmPerHour: null,
      thresholdMmPerHour: 0.3,
      source: 'DWD-Radar via Bright Sky',
      frames: values.map(([time, mmPerHour, isForecast]) => ({
        time: `2026-11-17T${time}:00`, mmPerHour, forecast: isForecast
      }))
    };
  }

  it('sagt ehrlich, wenn noch keine Daten da sind', () => {
    const detail = buildRainRadarDetail({ ...forecast(0, []), measuredAt: null, mmPerHour: null }, now);

    expect(detail.chartOptions).toBeNull();
    expect(detail.metrics).toEqual([]);
    expect(detail.summary).toContain('Noch keine Radardaten');
  });

  it('meldet trockene zwei Stunden', () => {
    const detail = buildRainRadarDetail(forecast(0, [['14:55', 0, false], ['15:05', 0.1, true]]), now);

    expect(detail.summary).toBe('Kein Regen in den nächsten zwei Stunden erwartet.');
    expect(detail.metrics.map(m => m.value)).toEqual(['trocken', 'nicht in 2 h', '0,1 mm/h']);
    expect(detail.measuredLabel).toBe('Messung 14:55 Uhr');
  });

  it('nennt kommenden Regen mit Uhrzeit, Minuten und Spitze', () => {
    const detail = buildRainRadarDetail(forecast(0, [
      ['14:55', 0, false], ['15:10', 0, true], ['15:20', 1.4, true], ['15:25', 3.1, true]
    ], '2026-11-17T15:20:00'), now);

    expect(detail.summary).toBe('Regen ab ca. 15:20 Uhr erwartet, bis zu 3,1 mm/h (mäßig).');
    expect(detail.metrics[1]).toEqual(jasmine.objectContaining({ label: 'Regen ab', value: '15:20', hint: 'in 20 Min.' }));
    expect(detail.metrics[2].value).toBe('3,1 mm/h');
  });

  it('nennt bei Regen, ab wann es trocken wird', () => {
    const detail = buildRainRadarDetail(forecast(1.2, [
      ['14:55', 1.2, false], ['15:05', 0.8, true], ['15:30', 0.1, true]
    ]), now);

    expect(detail.summary).toBe('Es regnet am Haus (1,2 mm/h, leicht). Voraussichtlich trocken ab 15:30 Uhr.');
    expect(detail.metrics[1]).toEqual(jasmine.objectContaining({ label: 'Trocken ab', value: '15:30' }));
  });

  it('ignoriert Vorhersagebilder, die schon in der Vergangenheit liegen', () => {
    const detail = buildRainRadarDetail(forecast(0, [['14:55', 0, false], ['14:58', 5, true]]), now);

    expect(detail.metrics[2].value).toBe('0,0 mm/h');
  });

  it('laesst die Vorhersage-Linie am letzten Messpunkt ansetzen', () => {
    const detail = buildRainRadarDetail(forecast(0, [['14:50', 0, false], ['14:55', 0.2, false], ['15:05', 1, true]]), now);
    const series = detail.chartOptions!['series'] as { name: string; data: [number, number][] }[];

    expect(series[0].data.length).toBe(2);
    expect(series[1].data.map(point => point[1])).toEqual([0.2, 1]);
  });
});

describe('rainIntensityLabel', () => {
  it('ordnet nach den DWD-Stufen ein', () => {
    expect(rainIntensityLabel(0.05)).toBe('trocken');
    expect(rainIntensityLabel(1)).toBe('leicht');
    expect(rainIntensityLabel(5)).toBe('mäßig');
    expect(rainIntensityLabel(12)).toBe('stark');
  });
});
