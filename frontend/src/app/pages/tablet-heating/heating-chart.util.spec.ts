import { ThermostatHistoryPoint } from '../../models/thermostat.model';
import { buildSparklineOptions, heatingSegments } from './heating-chart.util';

const point = (time: string, current: number | null, heating: boolean): ThermostatHistoryPoint =>
  ({ time, currentTemp: current, targetTemp: 21, heating });

describe('heating-chart.util', () => {
  describe('heatingSegments', () => {
    it('fasst zusammenhaengende Heizphasen zusammen', () => {
      const segments = heatingSegments([
        point('2026-10-09T10:00:00', 20, false),
        point('2026-10-09T11:00:00', 20, true),
        point('2026-10-09T12:00:00', 20, true),
        point('2026-10-09T13:00:00', 20, false),
        point('2026-10-09T14:00:00', 20, true)
      ]);
      expect(segments).toEqual([
        ['2026-10-09T11:00:00', '2026-10-09T13:00:00'],
        ['2026-10-09T14:00:00', '2026-10-09T14:00:00']
      ]);
    });

    it('liefert nichts ohne Heizphase', () => {
      expect(heatingSegments([point('2026-10-09T10:00:00', 20, false)])).toEqual([]);
    });
  });

  describe('buildSparklineOptions', () => {
    it('liefert null, wenn kein einziger Messwert vorliegt', () => {
      expect(buildSparklineOptions([])).toBeNull();
      expect(buildSparklineOptions([point('2026-10-09T10:00:00', null, false)])).toBeNull();
    });

    it('bricht die Ist-Linie an Luecken statt sie zu verbinden', () => {
      const options = buildSparklineOptions([
        point('2026-10-09T10:00:00', 20, false),
        point('2026-10-09T11:00:00', null, false)
      ]) as { series: { name: string; connectNulls: boolean }[] };
      const ist = options.series.find(s => s.name === 'Ist');
      expect(ist?.connectNulls).toBeFalse();
    });
  });
});
