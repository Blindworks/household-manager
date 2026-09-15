import {
  bridgeEventText,
  healthBadge,
  permitJoinRemainingSeconds,
  silentText,
  sortDevicesForDisplay
} from './zigbee-device-view.util';
import { ZigbeeBridgeEvent, ZigbeeDevice, ZigbeeDeviceHealthStatus } from '../models/zigbee.model';

describe('zigbee-device-view.util', () => {
  const device = (name: string, status: ZigbeeDeviceHealthStatus, overrides: Partial<ZigbeeDevice> = {}): ZigbeeDevice => ({
    id: 1, friendlyName: name, ieeeAddress: '0x1', knownToBridge: true, type: 'EndDevice',
    powerSource: 'Battery', battery: true, interviewCompleted: true, supported: true,
    model: null, vendor: null, description: null, lastBatteryPercent: null, lastLinkQuality: null,
    lastSeen: null, entities: [],
    health: { status, basis: null, lastHeardAt: null, silentSeconds: null },
    ...overrides
  });

  describe('sortDevicesForDisplay', () => {
    it('stellt kranke Geraete vor aktive, innerhalb alphabetisch', () => {
      const sorted = sortDevicesForDisplay([
        device('Zeta', 'ACTIVE'),
        device('Beta', 'SILENT'),
        device('Alpha', 'ACTIVE'),
        device('Gamma', 'OFFLINE'),
        device('delta', 'UNKNOWN'),
        device('Epsilon', 'INTERVIEW')
      ]);

      expect(sorted.map(d => d.friendlyName)).toEqual(['Beta', 'delta', 'Epsilon', 'Gamma', 'Alpha', 'Zeta']);
    });

    it('laesst die Eingabe unveraendert', () => {
      const input = [device('B', 'ACTIVE'), device('A', 'ACTIVE')];
      sortDevicesForDisplay(input);
      expect(input.map(d => d.friendlyName)).toEqual(['B', 'A']);
    });
  });

  describe('healthBadge', () => {
    it('kennt jeden Status', () => {
      expect(healthBadge('ACTIVE')).toEqual({ label: 'Aktiv', cssClass: 'badge--active' });
      expect(healthBadge('SILENT')).toEqual({ label: 'Verstummt', cssClass: 'badge--silent' });
      expect(healthBadge('OFFLINE')).toEqual({ label: 'Offline', cssClass: 'badge--offline' });
      expect(healthBadge('UNKNOWN')).toEqual({ label: 'Unbekannt', cssClass: 'badge--neutral' });
      expect(healthBadge('INTERVIEW')).toEqual({ label: 'Interview', cssClass: 'badge--neutral' });
    });
  });

  describe('silentText', () => {
    it('formatiert Minuten, Stunden und Tage', () => {
      expect(silentText(null)).toBe('nie gehört');
      expect(silentText(30)).toBe('zuletzt gehört vor weniger als einer Minute');
      expect(silentText(90)).toBe('zuletzt gehört vor 1 Minute');
      expect(silentText(5 * 60)).toBe('zuletzt gehört vor 5 Minuten');
      expect(silentText(3 * 3600 + 20 * 60)).toBe('zuletzt gehört vor 3 Std. 20 Min.');
      expect(silentText(21 * 86400 + 3600)).toBe('zuletzt gehört vor 21 Tagen');
      expect(silentText(86400)).toBe('zuletzt gehört vor 1 Tag');
    });
  });

  describe('permitJoinRemainingSeconds', () => {
    const now = Date.parse('2026-09-15T10:00:00Z');

    it('rechnet aus dem Server-Ende, nie negativ', () => {
      expect(permitJoinRemainingSeconds('2026-09-15T10:03:20Z', now)).toBe(200);
      expect(permitJoinRemainingSeconds('2026-09-15T09:59:00Z', now)).toBe(0);
      expect(permitJoinRemainingSeconds(null, now)).toBe(0);
      expect(permitJoinRemainingSeconds('kaputt', now)).toBe(0);
    });
  });

  describe('bridgeEventText', () => {
    const event = (overrides: Partial<ZigbeeBridgeEvent>): ZigbeeBridgeEvent => ({
      type: 'device_joined', friendlyName: '0x00158d00aaaaaaaa', ieeeAddress: '0x00158d00aaaaaaaa',
      status: null, model: null, vendor: null, receivedAt: '2026-09-15T10:00:00Z', ...overrides
    });

    it('beschreibt jeden Ereignistyp', () => {
      expect(bridgeEventText(event({}))).toBe('0x00158d00aaaaaaaa beigetreten');
      expect(bridgeEventText(event({ type: 'device_interview', status: 'started' }))).toBe('0x00158d00aaaaaaaa: Interview läuft …');
      expect(bridgeEventText(event({ type: 'device_interview', status: 'successful', model: 'SNZB-03', vendor: 'SONOFF' })))
        .toBe('0x00158d00aaaaaaaa: Interview erfolgreich (SNZB-03, SONOFF)');
      expect(bridgeEventText(event({ type: 'device_interview', status: 'failed' }))).toBe('0x00158d00aaaaaaaa: Interview fehlgeschlagen');
      expect(bridgeEventText(event({ type: 'device_announce', friendlyName: 'Motion Büro' }))).toBe('Motion Büro hat sich gemeldet');
      expect(bridgeEventText(event({ type: 'device_leave', friendlyName: 'Motion Büro' }))).toBe('Motion Büro hat das Netz verlassen');
    });

    it('faellt ohne Namen auf die IEEE-Adresse zurueck', () => {
      expect(bridgeEventText(event({ friendlyName: null }))).toBe('0x00158d00aaaaaaaa beigetreten');
      expect(bridgeEventText(event({ friendlyName: null, ieeeAddress: null }))).toBe('Unbekanntes Gerät beigetreten');
    });
  });
});
