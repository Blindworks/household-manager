import { Thermostat } from '../models/thermostat.model';
import {
  arcFraction,
  clampTarget,
  formatTemp,
  gridColumns,
  stepTarget,
  thermostatCaption,
  thermostatTone
} from './thermostat-view.util';

const base: Thermostat = {
  id: 1, name: 'Test', online: true, currentTemp: 20, targetTemp: 21,
  mode: 'MANUAL', heating: false, batteryLow: false, lastUpdated: null
};

describe('thermostat-view.util', () => {
  describe('clampTarget', () => {
    it('rundet auf 0,5 Grad', () => {
      expect(clampTarget(21.3)).toBe(21.5);
      expect(clampTarget(21.2)).toBe(21);
    });

    it('klemmt auf 5 bis 30 Grad', () => {
      expect(clampTarget(2)).toBe(5);
      expect(clampTarget(35)).toBe(30);
    });
  });

  describe('stepTarget', () => {
    it('geht in 0,5-Schritten', () => {
      expect(stepTarget(21, 1)).toBe(21.5);
      expect(stepTarget(21, -1)).toBe(20.5);
    });

    it('bleibt an den Grenzen stehen', () => {
      expect(stepTarget(30, 1)).toBe(30);
      expect(stepTarget(5, -1)).toBe(5);
    });
  });

  describe('thermostatTone', () => {
    it('offline schlaegt alles', () => {
      expect(thermostatTone({ ...base, online: false, heating: true, mode: 'OFF' })).toBe('offline');
    });

    it('ordnet die uebrigen vier Zustaende zu', () => {
      expect(thermostatTone({ ...base, heating: true })).toBe('heating');
      expect(thermostatTone({ ...base })).toBe('idle');
      expect(thermostatTone({ ...base, mode: 'FROST_PROTECTION' })).toBe('frost');
      expect(thermostatTone({ ...base, mode: 'OFF' })).toBe('off');
    });

    it('Frostschutz und Aus zeigen kein Heizen an, auch wenn das Ventil offen ist', () => {
      expect(thermostatTone({ ...base, mode: 'FROST_PROTECTION', heating: true })).toBe('frost');
      expect(thermostatTone({ ...base, mode: 'OFF', heating: true })).toBe('off');
    });
  });

  describe('thermostatCaption', () => {
    it('meldet "Heizt nicht" statt "Ziel erreicht", solange der Istwert unter dem Soll liegt', () => {
      expect(thermostatCaption({ ...base, currentTemp: 18.4, targetTemp: 19.5 })).toBe('Heizt nicht');
    });

    it('meldet "Ziel erreicht" ab Istwert gleich Soll', () => {
      expect(thermostatCaption({ ...base, currentTemp: 20, targetTemp: 20 })).toBe('Ziel erreicht');
      expect(thermostatCaption({ ...base, currentTemp: 20.9, targetTemp: 19.5 })).toBe('Ziel erreicht');
    });

    it('behauptet ohne Werte kein erreichtes Ziel', () => {
      expect(thermostatCaption({ ...base, currentTemp: null })).toBe('Heizt nicht');
      expect(thermostatCaption({ ...base, targetTemp: null })).toBe('Heizt nicht');
    });

    it('beschriftet die uebrigen Zustaende unveraendert', () => {
      expect(thermostatCaption({ ...base, heating: true })).toBe('Heizt');
      expect(thermostatCaption({ ...base, online: false })).toBe('Keine Daten');
      expect(thermostatCaption({ ...base, mode: 'OFF' })).toBe('Heizung aus');
      expect(thermostatCaption({ ...base, mode: 'FROST_PROTECTION' })).toBe('Frostschutz');
    });
  });

  describe('arcFraction', () => {
    it('liefert den Anteil von 5 bis 30 Grad', () => {
      expect(arcFraction(5)).toBe(0);
      expect(arcFraction(30)).toBe(1);
      expect(arcFraction(17.5)).toBe(0.5);
    });

    it('klemmt ausserhalb und liefert null ohne Wert', () => {
      expect(arcFraction(40)).toBe(1);
      expect(arcFraction(0)).toBe(0);
      expect(arcFraction(null)).toBeNull();
    });
  });

  describe('formatTemp', () => {
    it('nutzt das Dezimalkomma', () => {
      expect(formatTemp(20.5)).toBe('20,5');
      expect(formatTemp(21)).toBe('21,0');
    });

    it('zeigt bei null einen Strich statt einer geratenen Zahl', () => {
      expect(formatTemp(null)).toBe('–');
    });
  });

  describe('gridColumns', () => {
    it('zwei Spalten bis vier Thermostate, danach drei', () => {
      expect(gridColumns(1)).toBe(2);
      expect(gridColumns(4)).toBe(2);
      expect(gridColumns(5)).toBe(3);
    });
  });
});
