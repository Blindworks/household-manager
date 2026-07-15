import { comfortRating, buildClimateView } from './temperature-comfort.util';
import { CurrentTemperatureReading } from '../models/temperature.model';

describe('comfortRating', () => {
  it('bewertet unter 19 Grad als frisch', () => {
    expect(comfortRating(18.9)).toEqual({ label: 'frisch', tone: 'cool' });
  });

  it('bewertet 19 bis unter 23 Grad als angenehm', () => {
    expect(comfortRating(19).tone).toBe('comfortable');
    expect(comfortRating(22.9).tone).toBe('comfortable');
  });

  it('bewertet 23 bis 25 Grad als warm', () => {
    expect(comfortRating(23).tone).toBe('warm');
    expect(comfortRating(25).tone).toBe('warm');
  });

  it('bewertet ueber 25 Grad als heiss', () => {
    expect(comfortRating(25.1)).toEqual({ label: 'heiß', tone: 'hot' });
  });
});

describe('buildClimateView', () => {
  const now = new Date('2026-07-15T12:00:00Z').getTime();

  function reading(partial: Partial<CurrentTemperatureReading>): CurrentTemperatureReading {
    return {
      sensorId: 's', name: 'Raum', source: 'ZIGBEE',
      temperature: 21, measuredAt: '2026-07-15T11:59:00Z', ...partial
    };
  }

  it('trennt DWD-Aussen von Innensensoren', () => {
    const view = buildClimateView([
      reading({ sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER', temperature: 12.4 }),
      reading({ sensorId: 'zigbee:1', name: 'Wohnzimmer', temperature: 21.4 })
    ], now);

    expect(view.weatherLabel).toBe('12°');
    expect(view.outdoor.length).toBe(0);
    expect(view.rows.length).toBe(1);
    expect(view.rows[0].name).toBe('Wohnzimmer');
    expect(view.rows[0].valueLabel).toBe('21,4°');
    expect(view.rows[0].statusLabel).toBe('angenehm');
    expect(view.rows[0].stale).toBe(false);
  });

  it('zeigt -- fuer den DWD-Wert, wenn keine Wetterquelle vorliegt', () => {
    const view = buildClimateView([reading({ sensorId: 'zigbee:1' })], now);
    expect(view.weatherLabel).toBe('--');
  });

  it('fuehrt den Gartenfuehler als primaeren Aussenwert, nicht als Innenzeile', () => {
    const view = buildClimateView([
      reading({ sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER', temperature: 12 }),
      reading({ sensorId: 'zigbee:9', name: 'Temperatur Aqara Garten', temperature: 11.4 }),
      reading({ sensorId: 'zigbee:1', name: 'Wohnzimmer', temperature: 21.4 })
    ], now);

    expect(view.outdoor.length).toBe(1);
    expect(view.outdoor[0].name).toBe('Temperatur Aqara Garten');
    expect(view.outdoor[0].valueLabel).toBe('11,4°');
    expect(view.outdoor[0].stale).toBe(false);
    expect(view.weatherLabel).toBe('12°');
    expect(view.rows.map(r => r.name)).toEqual(['Wohnzimmer']);
  });

  it('erkennt den Aussenfuehler unabhaengig von Gross-/Kleinschreibung', () => {
    const view = buildClimateView(
      [reading({ sensorId: 'zigbee:9', name: 'garten' })],
      now,
      ['Garten']
    );
    expect(view.outdoor.length).toBe(1);
    expect(view.rows.length).toBe(0);
  });

  it('zeigt mehrere konfigurierte Aussenfuehler und verliert keinen', () => {
    const view = buildClimateView(
      [
        reading({ sensorId: 'zigbee:9', name: 'Garten', temperature: 11.4 }),
        reading({ sensorId: 'zigbee:8', name: 'Terrasse', temperature: 13.1 }),
        reading({ sensorId: 'zigbee:1', name: 'Wohnzimmer', temperature: 21.4 })
      ],
      now,
      ['Garten', 'Terrasse']
    );

    expect(view.outdoor.map(o => o.name)).toEqual(['Garten', 'Terrasse']);
    expect(view.rows.map(r => r.name)).toEqual(['Wohnzimmer']);
  });

  it('liefert keinen Aussenwert, wenn kein Aussenfuehler gemeldet hat', () => {
    const view = buildClimateView([reading({ sensorId: 'zigbee:1', name: 'Wohnzimmer' })], now);
    expect(view.outdoor.length).toBe(0);
  });

  it('markiert einen veralteten Gartenfuehler', () => {
    const view = buildClimateView([
      reading({ sensorId: 'zigbee:9', name: 'Temperatur Aqara Garten', measuredAt: '2026-07-15T09:00:00Z' })
    ], now);

    expect(view.outdoor[0].stale).toBe(true);
  });

  it('markiert veraltete Innen-Messungen', () => {
    const view = buildClimateView([
      reading({ sensorId: 'zigbee:1', name: 'Bad', measuredAt: '2026-07-15T09:00:00Z' })
    ], now);

    expect(view.rows[0].stale).toBe(true);
    expect(view.rows[0].statusLabel).toBe('veraltet');
    expect(view.rows[0].tone).toBe('stale');
  });
});
