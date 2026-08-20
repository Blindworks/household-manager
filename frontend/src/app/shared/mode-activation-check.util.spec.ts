import { EntityState } from '../models/entity-state.model';
import { PowerConsumer } from '../models/power-consumer.model';
import {
  buildConsumerCheck,
  buildContactCheck,
  failedCheck,
  loadingCheck
} from './mode-activation-check.util';

describe('mode-activation-check.util', () => {

  const contact = (
    entityId: string,
    state: string,
    overrides: Partial<EntityState> = {}
  ): EntityState => ({
    entityId,
    domain: 'BINARY_SENSOR',
    source: 'ZIGBEE',
    sourceRef: entityId,
    friendlyName: entityId,
    displayName: entityId,
    state,
    attributes: { deviceClass: 'door' },
    lastChanged: '2026-08-20T10:00:00Z',
    lastUpdated: '2026-08-20T10:00:00Z',
    ...overrides
  });

  const consumer = (
    displayName: string,
    powerWatts: number | null
  ): PowerConsumer => ({
    entityId: `sensor.meross_${displayName.toLowerCase()}_power`,
    displayName,
    powerWatts,
    unavailable: powerWatts == null
  });

  describe('buildContactCheck', () => {
    it('meldet OK mit Sammelzeile, wenn alle Kontakte geschlossen sind', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'off', { displayName: 'Küche Kontakt' }),
        contact('binary_sensor.zigbee_bad_contact', 'off', { displayName: 'Bad Kontakt' })
      ]);
      expect(check.status).toBe('ok');
      expect(check.lines).toEqual(['Alle Fenster und Türen geschlossen (2 Kontakte).']);
    });

    it('nutzt bei genau einem Kontakt den Singular', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'off')
      ]);
      expect(check.lines).toEqual(['Alle Fenster und Türen geschlossen (1 Kontakt).']);
    });

    it('warnt je offenem Kontakt mit dessen Anzeigenamen', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'on', { displayName: 'Küche Kontakt' }),
        contact('binary_sensor.zigbee_bad_contact', 'off', { displayName: 'Bad Kontakt' })
      ]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Küche Kontakt ist offen.']);
    });

    it('wertet unavailable als "Zustand unbekannt", nicht als geschlossen', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_kueche_contact', 'unavailable', { displayName: 'Küche Kontakt' })
      ]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Küche Kontakt: Zustand unbekannt.']);
    });

    it('ignoriert Entitäten ohne deviceClass door', () => {
      const check = buildContactCheck([
        contact('binary_sensor.zigbee_flur_occupancy', 'on', {
          displayName: 'Flur Bewegung',
          attributes: { deviceClass: 'motion' }
        }),
        contact('binary_sensor.zigbee_kueche_contact', 'off')
      ]);
      expect(check.status).toBe('ok');
      expect(check.lines).toEqual(['Alle Fenster und Türen geschlossen (1 Kontakt).']);
    });

    it('warnt bei leerer Kontaktliste, statt grün zu melden', () => {
      const check = buildContactCheck([]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Keine Fenster-/Türkontakte gefunden.']);
    });
  });

  describe('buildConsumerCheck', () => {
    it('meldet OK, wenn kein Verbraucher die Schwelle erreicht', () => {
      const check = buildConsumerCheck([consumer('Kühlschrank', 49.9)]);
      expect(check.status).toBe('ok');
      expect(check.lines).toEqual(['Keine Großverbraucher aktiv.']);
    });

    it('warnt ab 50 W mit Name und gerundeter Wattzahl', () => {
      const check = buildConsumerCheck([
        consumer('Waschmaschine', 1234.5),
        consumer('Kühlschrank', 30)
      ]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Waschmaschine: 1235 W']);
    });

    it('warnt bei exakt 50 W (Schwelle ist inklusiv)', () => {
      const check = buildConsumerCheck([consumer('Waschmaschine', 50)]);
      expect(check.status).toBe('warning');
      expect(check.lines).toEqual(['Waschmaschine: 50 W']);
    });

    it('ignoriert Verbraucher ohne Messwert (powerWatts null)', () => {
      const check = buildConsumerCheck([consumer('Offline-Steckdose', null)]);
      expect(check.status).toBe('ok');
    });

    it('meldet bei leerer Verbraucherliste OK', () => {
      expect(buildConsumerCheck([]).status).toBe('ok');
    });
  });

  describe('Fabriken', () => {
    it('loadingCheck hat Status loading ohne Zeilen', () => {
      expect(loadingCheck()).toEqual({ status: 'loading', lines: [] });
    });

    it('failedCheck ist eine Warnung mit Fehlertext', () => {
      expect(failedCheck()).toEqual({ status: 'warning', lines: ['Prüfung fehlgeschlagen.'] });
    });
  });
});
