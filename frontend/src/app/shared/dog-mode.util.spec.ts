import { CurrentTemperatureReading } from '../models/temperature.model';
import { ModeEntity } from '../models/mode.model';
import { DOG_MODE_ENTITY_ID, buildDogModeClimate, isDogModeActive } from './dog-mode.util';

describe('dog-mode.util', () => {
  const now = new Date('2026-09-27T18:00:00').getTime();
  const minutesAgo = (minutes: number): string => new Date(now - minutes * 60_000).toISOString();

  const mode = (entityId: string, state: string): ModeEntity =>
    ({ entityId, displayName: entityId, icon: 'pets', state });

  const reading = (overrides: Partial<CurrentTemperatureReading>): CurrentTemperatureReading => ({
    sensorId: 'zigbee:1',
    entityId: 'sensor.zigbee_buero_temperature',
    name: 'Büro',
    source: 'ZIGBEE',
    temperature: 21,
    measuredAt: minutesAgo(5),
    ...overrides
  });

  describe('isDogModeActive', () => {
    it('ist aktiv, wenn Toni allein an ist', () => {
      expect(isDogModeActive([mode('input_boolean.manual_nachtmodus', 'off'), mode(DOG_MODE_ENTITY_ID, 'on')]))
        .toBeTrue();
    });

    it('ist inaktiv, wenn Toni allein aus ist', () => {
      expect(isDogModeActive([mode(DOG_MODE_ENTITY_ID, 'off')])).toBeFalse();
    });

    it('ist inaktiv, wenn Toni allein nicht in der Liste steht — nie geraten', () => {
      expect(isDogModeActive([])).toBeFalse();
      expect(isDogModeActive([mode('input_boolean.manual_nachtmodus', 'on')])).toBeFalse();
    });
  });

  describe('buildDogModeClimate', () => {
    it('findet den Wohnzimmer-Monitor ueber die Entity-ID, unabhaengig vom Namen', () => {
      const climate = buildDogModeClimate([
        reading({ sensorId: 'zigbee:7', name: 'Wohnzimmer', temperature: 30 }),
        reading({ sensorId: 'alexa:GAJ2300425330047', entityId: 'sensor.alexa_gaj2300425330047_temperature', name: 'Irgendein Name', source: 'ALEXA', temperature: 22.4 })
      ], now);

      expect(climate.indoor).toEqual({ label: '22°', stale: false });
    });

    it('rundet auf ganze Grad', () => {
      const climate = buildDogModeClimate(
        [reading({ sensorId: 'alexa:GAJ2300425330047', entityId: 'sensor.alexa_gaj2300425330047_temperature', source: 'ALEXA', temperature: 22.6 })], now);

      expect(climate.indoor?.label).toBe('23°');
    });

    it('markiert einen Innenwert aelter als 60 Minuten als veraltet', () => {
      const climate = buildDogModeClimate([reading({
        sensorId: 'alexa:GAJ2300425330047', entityId: 'sensor.alexa_gaj2300425330047_temperature', source: 'ALEXA', measuredAt: minutesAgo(61)
      })], now);

      expect(climate.indoor?.stale).toBeTrue();
    });

    it('liefert indoor null ohne Wohnzimmer-Messung', () => {
      expect(buildDogModeClimate([reading({})], now).indoor).toBeNull();
    });

    it('nimmt den realen Aussenfuehler vor dem DWD-Wert', () => {
      const climate = buildDogModeClimate([
        reading({ sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER', temperature: 12 }),
        reading({ sensorId: 'zigbee:3', entityId: 'sensor.zigbee_temperatur_aqara_garten_temperature', name: 'Garten', temperature: 14.4 })
      ], now);

      expect(climate.outdoorLabel).toBe('14°');
    });

    it('faellt ohne realen Aussenfuehler auf den DWD-Wert zurueck', () => {
      const climate = buildDogModeClimate(
        [reading({ sensorId: 'weather:outdoor', name: 'Außen', source: 'WEATHER', temperature: 11.6 })], now);

      expect(climate.outdoorLabel).toBe('12°');
    });

    it('liefert outdoorLabel null ohne jeden Aussenwert', () => {
      expect(buildDogModeClimate([reading({})], now).outdoorLabel).toBeNull();
    });
  });
});
