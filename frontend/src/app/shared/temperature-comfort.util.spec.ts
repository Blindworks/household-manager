import { comfortRating, buildClimateView, buildSensorDetail } from './temperature-comfort.util';
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

  it('fuehrt die Sensor-Id mit, damit Zeilen den Detaildialog oeffnen koennen', () => {
    const view = buildClimateView([
      reading({ sensorId: 'zigbee:9', name: 'Temperatur Aqara Garten' }),
      reading({ sensorId: 'zigbee:1', name: 'Wohnzimmer' })
    ], now);

    expect(view.outdoor[0].sensorId).toBe('zigbee:9');
    expect(view.rows[0].sensorId).toBe('zigbee:1');
  });
});

describe('buildSensorDetail', () => {
  const now = new Date('2026-07-15T12:00:00Z').getTime();

  function reading(partial: Partial<CurrentTemperatureReading>): CurrentTemperatureReading {
    return {
      sensorId: 's', name: 'Raum', source: 'ZIGBEE',
      temperature: 21, measuredAt: '2026-07-15T11:59:00Z', ...partial
    };
  }

  it('liefert Temperatur, Feuchte und Komfort des gewaehlten Sensors', () => {
    const detail = buildSensorDetail([
      reading({ sensorId: 'zigbee:1', name: 'Wohnzimmer', temperature: 21.4, humidity: 48.4 }),
      reading({ sensorId: 'zigbee:2', name: 'Bad' })
    ], 'zigbee:1', now);

    expect(detail).not.toBeNull();
    expect(detail!.name).toBe('Wohnzimmer');
    expect(detail!.temperatureLabel).toBe('21,4 °C');
    expect(detail!.humidityLabel).toBe('48 %');
    expect(detail!.statusLabel).toBe('angenehm');
    expect(detail!.tone).toBe('comfortable');
    expect(detail!.stale).toBe(false);
  });

  it('liefert keine Feuchte-Angabe, wenn der Sensor keine meldet', () => {
    const detail = buildSensorDetail([reading({ sensorId: 'zigbee:1' })], 'zigbee:1', now);
    expect(detail!.humidityLabel).toBeNull();
  });

  it('markiert eine veraltete Messung', () => {
    const detail = buildSensorDetail(
      [reading({ sensorId: 'zigbee:1', measuredAt: '2026-07-15T09:00:00Z' })],
      'zigbee:1',
      now
    );

    expect(detail!.stale).toBe(true);
    expect(detail!.statusLabel).toBe('veraltet');
    expect(detail!.tone).toBe('stale');
  });

  it('liefert null, wenn der Sensor nicht mehr meldet', () => {
    expect(buildSensorDetail([reading({ sensorId: 'zigbee:1' })], 'zigbee:9', now)).toBeNull();
  });
});
