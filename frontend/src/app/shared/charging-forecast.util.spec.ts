import { ChargingForecastSummary } from '../models/charging.model';
import { HEATMAP_DAY_LABELS, forecastLine, freeWindow, heatmapLevel } from './charging-forecast.util';

describe('charging-forecast.util', () => {
  describe('forecastLine', () => {
    it('liefert null ohne Summary', () => {
      expect(forecastLine(undefined)).toBeNull();
    });

    it('nennt bei zu wenig Daten die beobachteten Tage', () => {
      const s: ChargingForecastSummary = { status: 'INSUFFICIENT_DATA', observedDays: 5 };
      expect(forecastLine(s)).toBe('noch zu wenig Daten (5 Tage)');
    });

    it('kombiniert Freizeit und Wahrscheinlichkeit', () => {
      const s: ChargingForecastSummary = {
        status: 'OK', freeProbability30min: 72, expectedFreeAt: '2026-11-02T17:40:00'
      };
      expect(forecastLine(s)).toBe('frei ca. 17:40 · 72 % in 30 min');
    });

    it('zeigt nur die Wahrscheinlichkeit ohne erwartete Freizeit', () => {
      const s: ChargingForecastSummary = { status: 'OK', freeProbability30min: 72 };
      expect(forecastLine(s)).toBe('72 % in 30 min');
    });
  });

  describe('freeWindow', () => {
    it('formatiert Median und spaetes Quantil', () => {
      expect(freeWindow('2026-11-02T17:40:00', '2026-11-02T18:00:00')).toBe('frei ca. 17:40, meist bis 18:00');
    });

    it('verkraftet Zeitstrings ohne Sekunden', () => {
      expect(freeWindow('2026-11-02T17:40', '2026-11-02T18:00')).toBe('frei ca. 17:40, meist bis 18:00');
    });

    it('liefert null ohne Median', () => {
      expect(freeWindow(undefined, '2026-11-02T18:00:00')).toBeNull();
    });

    it('laesst ohne spaetes Quantil den Zusatz weg', () => {
      expect(freeWindow('2026-11-02T17:40:00')).toBe('frei ca. 17:40');
    });
  });

  describe('heatmapLevel', () => {
    it('ordnet Grenzen zu (untere Grenze gehoert zur hoeheren Stufe)', () => {
      expect(heatmapLevel(null)).toBe('none');
      expect(heatmapLevel(0)).toBe('none');
      expect(heatmapLevel(0.19)).toBe('none');
      expect(heatmapLevel(0.2)).toBe('low');
      expect(heatmapLevel(0.39)).toBe('low');
      expect(heatmapLevel(0.4)).toBe('mid');
      expect(heatmapLevel(0.6)).toBe('high');
      expect(heatmapLevel(0.79)).toBe('high');
      expect(heatmapLevel(0.8)).toBe('full');
      expect(heatmapLevel(1)).toBe('full');
    });
  });

  it('hat sieben Tageslabels', () => {
    expect(HEATMAP_DAY_LABELS).toEqual(['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So/Ft']);
  });
});
