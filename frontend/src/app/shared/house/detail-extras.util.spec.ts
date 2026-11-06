import { TractivePet, TractiveWalk } from '../../models/tractive.model';
import { PetSupply } from '../../models/pet-supply.model';
import { walkDistance, walkDuration, walkTimeRange } from '../walk-format.util';
import {
  NO_WASTE_TEXT, cameraIdOf, lockBatteryLine, motionLine, petLines, smartlockIdOf, trackerIdOf, wasteDayText, wasteLines,
} from './detail-extras.util';

describe('detail-extras.util', () => {
  const today = new Date(2026, 10, 9);
  const pet = (over: Partial<TractivePet> = {}): TractivePet => ({ trackerId: 't1', name: 'Toni', zone: 'Zuhause', ...over });

  it('nennt den Text fuer leere Abfuhr', () => {
    expect(NO_WASTE_TEXT).toBe('Keine Abfuhr in den nächsten Tagen');
  });

  describe('wasteDayText', () => {
    it('sagt heute, morgen und sonst Wochentag mit Datum', () => {
      expect(wasteDayText('2026-11-09', today)).toBe('heute');
      expect(wasteDayText('2026-11-10', today)).toBe('morgen');
      expect(wasteDayText('2026-11-12', today)).toBe('Do 12.11.');
    });
  });

  describe('wasteLines', () => {
    it('behaelt je Bezeichnung den fruehesten kommenden Termin, sortiert nach Datum', () => {
      const lines = wasteLines([
        { date: '2026-11-08', label: 'Bio', daysUntil: 0 },
        { date: '2026-11-16', label: 'Bio', daysUntil: 7 },
        { date: '2026-11-12', label: 'Restmüll', daysUntil: 3 },
        { date: '2026-11-26', label: 'Restmüll', daysUntil: 17 },
      ], today);
      expect(lines.map(l => l.text)).toEqual(['Restmüll · Do 12.11.', 'Bio · Mo 16.11.']);
    });

    it('ist ohne Termine leer', () => {
      expect(wasteLines([], today)).toEqual([]);
    });
  });

  describe('petLines', () => {
    it('zeigt Akku mit Laden und keinen Spaziergang', () => {
      const lines = petLines({ pet: pet({ batteryPercent: 80, charging: true }), lastWalk: null }, undefined);
      expect(lines.map(l => l.text)).toEqual(['Akku 80 % · lädt', 'kein Spaziergang in 7 Tagen']);
    });

    it('zeigt ohne Daten Strich', () => {
      const lines = petLines(undefined, undefined);
      expect(lines.map(l => l.text)).toEqual(['Akku –', 'Spaziergang –']);
      expect(lines.every(l => l.stale)).toBeTrue();
    });

    it('behandelt 0 Prozent als echten Wert', () => {
      expect(petLines({ pet: pet({ batteryPercent: 0 }), lastWalk: null }, undefined)[0].text).toBe('Akku 0 %');
    });

    it('zeigt den letzten Spaziergang', () => {
      const walk: TractiveWalk = {
        start: '2026-11-09T07:00:00', end: '2026-11-09T07:45:00', durationMinutes: 45, distanceMeters: 2300,
      };
      const text = petLines({ pet: pet({ batteryPercent: 50 }), lastWalk: walk }, undefined)[1].text;
      expect(text).toBe(`Spaziergang ${walkTimeRange(walk)} · ${walkDuration(walk)} · ${walkDistance(walk)}`);
    });

    it('haengt Vorratszeilen mit deutschem Dezimalkomma an', () => {
      const supply: PetSupply = {
        key: 'toni_cans', name: 'Futter', unit: 'Dosen', amountRemaining: 12.5, targetAmount: 48, step: 0.5,
        perDay: 1, percent: 26, daysRemaining: 12,
      };
      const lines = petLines({ pet: pet({ batteryPercent: 50 }), lastWalk: null }, [supply]);
      expect(lines[2].text).toBe('Futter 12,5 Dosen · noch 12 Tage');
      expect(lines[2].stale).toBeFalse();
    });
  });

  describe('motionLine', () => {
    it('zeigt am selben Tag nur die Uhrzeit, sonst Datum dazu', () => {
      expect(motionLine('2026-11-09T14:05:00', new Date(2026, 10, 9, 18))!.text).toBe('letzte Bewegung 14:05');
      expect(motionLine('2026-11-09T14:05:00', new Date(2026, 10, 10, 8))!.text).toBe('letzte Bewegung 09.11. 14:05');
    });

    it('ist ohne Wert null', () => {
      expect(motionLine(null, today)).toBeNull();
      expect(motionLine(undefined, today)).toBeNull();
    });
  });

  describe('lockBatteryLine', () => {
    it('markiert schwachen Akku', () => {
      expect(lockBatteryLine({ smartlockId: 1, batteryCharge: 64, batteryCritical: true })!.text).toBe('Akku 64 % · schwach');
      expect(lockBatteryLine({ smartlockId: 1, batteryCharge: 64, batteryCritical: false })!.text).toBe('Akku 64 %');
    });

    it('ist ohne Ladestand null', () => {
      expect(lockBatteryLine({ smartlockId: 1, batteryCharge: null, batteryCritical: false })).toBeNull();
      expect(lockBatteryLine(null)).toBeNull();
    });
  });

  describe('Id-Ableitung', () => {
    it('liest Schloss-, Kamera- und Tracker-Id aus der Entity-Id', () => {
      expect(smartlockIdOf('lock.nuki_18241688572')).toBe(18241688572);
      expect(smartlockIdOf('lock.nuki_abc')).toBeNull();
      expect(cameraIdOf('binary_sensor.blink_511671_armed')).toBe('511671');
      expect(trackerIdOf('binary_sensor.tractive_pqbzjech_home')).toBe('pqbzjech');
    });
  });
});
