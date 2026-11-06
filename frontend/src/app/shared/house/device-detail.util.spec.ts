import { EntityState } from '../../models/entity-state.model';
import { HOUSE_DEVICES, HouseDevice } from './house-devices';
import { buildDeviceDetail, opensDetail, thermostatIdOf } from './device-detail.util';

const NOW = new Date(2026, 10, 5, 10, 0).getTime();
const MIN = 60_000;

function iso(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function entity(entityId: string, state: string, ageMin = 1, extra: Partial<EntityState> = {}): EntityState {
  return {
    entityId, domain: 'SENSOR', source: 'X', sourceRef: 'x', friendlyName: entityId, displayName: entityId,
    state, attributes: {}, lastChanged: iso(NOW - ageMin * MIN), lastUpdated: iso(NOW - ageMin * MIN), ...extra
  };
}

function mapOf(...entities: EntityState[]): Map<string, EntityState> {
  return new Map(entities.map(e => [e.entityId, e]));
}

function find<K extends HouseDevice['kind']>(kind: K, predicate: (d: Extract<HouseDevice, { kind: K }>) => boolean)
  : Extract<HouseDevice, { kind: K }> {
  const found = HOUSE_DEVICES.find(d => d.kind === kind && predicate(d as Extract<HouseDevice, { kind: K }>));
  expect(found).withContext(`Geraet ${kind}`).toBeDefined();
  return found as Extract<HouseDevice, { kind: K }>;
}

describe('buildDeviceDetail', () => {
  it('Fühler: headline Temperatur, Zeile Feuchte, history temperature mit entityId der Temperatur, control null', () => {
    const d = find('climate', c => c.id === 'buero-climate');
    const detail = buildDeviceDetail(d, mapOf(entity(d.temperature!, '21.5'), entity(d.humidity!, '45')), NOW);
    expect(detail.headline).toEqual({ text: '21,5°', stale: false });
    expect(detail.lines).toEqual([{ text: '45 %', stale: false }]);
    expect(detail.history).toEqual({ type: 'temperature', entityId: d.temperature! });
    expect(detail.control).toBeNull();
    expect(detail.title).toBe(d.label);
  });

  it('Thermostat 8: Ist, Soll, heizt, control target und history thermostat', () => {
    const d = find('thermostat', t => t.id === 'wohnzimmer-thermostat-8');
    const entities = mapOf(entity(d.temperature, '20.1'), entity(d.target, '20.0'), entity(d.heating, 'on'));
    const detail = buildDeviceDetail(d, entities, NOW);
    expect(detail.headline.text).toBe('20,1°');
    expect(detail.lines.map(l => l.text)).toEqual(['Soll 20°', 'heizt']);
    expect(detail.control).toEqual({ type: 'target', thermostatId: 8, target: 20, min: 5, max: 30, step: 0.5, mode: null });
    expect(detail.history).toEqual({ type: 'thermostat', thermostatId: 8 });
    const off = buildDeviceDetail(d,
      mapOf(entity(d.temperature, '20.1'), entity(d.target, '20.0'), entity(d.heating, 'off')), NOW);
    expect(off.lines[1].text).toBe('heizt nicht');
  });

  it('Switch an mit frischer Leistung: headline an, Zeile Watt, control, history power', () => {
    const d = find('switch', s => !!s.powerEntityId);
    const detail = buildDeviceDetail(d, mapOf(entity(d.entityId, 'on'), entity(d.powerEntityId!, '221')), NOW);
    expect(detail.headline.text).toBe('an');
    expect(detail.lines.map(l => l.text)).toEqual(['221 W']);
    expect(detail.control).toEqual({ type: 'switch', entityId: d.entityId, on: true, confirmOff: false, dimmable: false, color: null });
    expect(detail.history).toEqual({ type: 'power', entityId: d.powerEntityId! });
  });

  it('Switch ohne powerEntityId: history null', () => {
    const d = find('switch', s => !s.powerEntityId);
    expect(buildDeviceDetail(d, mapOf(entity(d.entityId, 'off')), NOW).history).toBeNull();
  });

  it('Switch confirmRequired: confirmOff true', () => {
    const d = find('switch', s => !!s.powerEntityId);
    const detail = buildDeviceDetail(d, mapOf(entity(d.entityId, 'on', 1, { confirmRequired: true })), NOW);
    expect(detail.control).toEqual(jasmine.objectContaining({ confirmOff: true }));
  });

  it('Appliance läuft: confirmOff true auch ohne confirmRequired; fertig/aus folgt confirmRequired', () => {
    const d = find('appliance', a => a.id === 'waschkeller-appliance-waschmaschine');
    const sw = entity(d.switchEntityId, 'on');
    const running = buildDeviceDetail(d, mapOf(sw, entity(d.runningEntityId, 'on')), NOW);
    expect(running.headline.text).toBe('läuft');
    expect(running.control).toEqual(jasmine.objectContaining({ entityId: d.switchEntityId, confirmOff: true }));
    const done = buildDeviceDetail(d, mapOf(sw, entity(d.runningEntityId, 'off'), entity(d.finishedEntityId, 'on')), NOW);
    expect(done.headline.text).toBe('fertig');
    expect(done.control).toEqual(jasmine.objectContaining({ confirmOff: false }));
    const guarded = buildDeviceDetail(d, mapOf(entity(d.switchEntityId, 'on', 1, { confirmRequired: true })), NOW);
    expect(guarded.headline.text).toBe('aus');
    expect(guarded.control).toEqual(jasmine.objectContaining({ confirmOff: true }));
  });

  it('Appliance: Schalter trägt den Lauf-Helfer für die frische Prüfung beim Ausschalten', () => {
    const d = find('appliance', a => a.id === 'waschkeller-appliance-waschmaschine');
    const detail = buildDeviceDetail(d, mapOf(entity(d.switchEntityId, 'on')), NOW);
    expect(detail.control).toEqual(jasmine.objectContaining({ runningEntityId: d.runningEntityId }));
  });

  it('Kontakt, Bewegung, Taster: Zustand, seit HH:mm, kein Verlauf, kein Control', () => {
    const c = find('contact', x => x.area === 'bad');
    const contact = buildDeviceDetail(c, mapOf(entity(c.entityId, 'on', 60)), NOW);
    expect(contact.headline.text).toBe('offen');
    expect(contact.lines).toEqual([{ text: 'seit 09:00', stale: false }]);
    expect(contact.history).toBeNull();
    expect(contact.control).toBeNull();
    expect(buildDeviceDetail(c, mapOf(entity(c.entityId, 'off', 60)), NOW).headline.text).toBe('zu');

    const m = find('motion', x => x.id === 'kueche-motion');
    expect(buildDeviceDetail(m, mapOf(entity(m.entityId, 'on', 5)), NOW).headline.text).toBe('Bewegung');
    expect(buildDeviceDetail(m, mapOf(entity(m.entityId, 'off', 5)), NOW).headline.text).toBe('ruhig');

    const b = find('button', x => x.id === 'buero-buttons');
    const button = buildDeviceDetail(b, mapOf(entity(b.entityIds[0], 'single', 10)), NOW);
    expect(button.headline.text).toBe('einfach');
    expect(button.lines[0].text).toBe('seit 09:50');
    expect(button.history).toBeNull();
    expect(button.control).toBeNull();
  });

  it('unavailable Switch: headline – stale, control on null', () => {
    const d = find('switch', s => !!s.powerEntityId);
    const detail = buildDeviceDetail(d, mapOf(entity(d.entityId, 'unavailable')), NOW);
    expect(detail.headline).toEqual({ text: '–', stale: true });
    expect(detail.control).toEqual(jasmine.objectContaining({ on: null }));
  });

  it('fehlende Entität wirft nicht, headline –', () => {
    for (const d of HOUSE_DEVICES.filter(x => opensDetail(x.kind))) {
      expect(buildDeviceDetail(d, new Map(), NOW).headline.text).withContext(d.id).toBe('–');
    }
  });
});

describe('opensDetail', () => {
  it('ist für jede Art in HOUSE_DEVICES wahr', () => {
    for (const d of HOUSE_DEVICES) {
      expect(opensDetail(d.kind)).withContext(d.id).toBeTrue();
    }
  });
});

describe('buildDeviceDetail mit Zusatzdaten', () => {
  const doorDevice = () => find('door', d => d.id === 'front-door');
  const lockExtras = { smartlockId: 18241688572, batteryCharge: 64, batteryCritical: false };

  it('Tür: Kopf Schlosstext, Zeilen zu und Akku, Bedienelement lock, kein Verlauf', () => {
    const d = doorDevice();
    const detail = buildDeviceDetail(d, mapOf(entity(d.lockEntityId, 'unlocked'), entity(d.contactEntityId, 'off')),
      NOW, { lock: lockExtras });
    expect(detail.headline.text).toBe('entriegelt');
    const texts = detail.lines.map(l => l.text);
    expect(texts).toContain('zu');
    expect(texts).toContain('Akku 64 %');
    expect(detail.control).toEqual({ type: 'lock', smartlockId: 18241688572, locked: false });
    expect(detail.history).toBeNull();
  });

  it('Tür: locked ⇒ true, unavailable ⇒ null, ohne smartlockId kein Bedienelement', () => {
    const d = doorDevice();
    const locked = buildDeviceDetail(d, mapOf(entity(d.lockEntityId, 'locked')), NOW, { lock: lockExtras });
    expect(locked.control).toEqual(jasmine.objectContaining({ locked: true }));
    const dead = buildDeviceDetail(d, mapOf(entity(d.lockEntityId, 'unavailable')), NOW, { lock: lockExtras });
    expect(dead.control).toEqual(jasmine.objectContaining({ locked: null }));
    const noLock = buildDeviceDetail(d, mapOf(entity(d.lockEntityId, 'locked')), NOW, { lock: null });
    expect(noLock.control).toBeNull();
  });

  it('Kamera: Kopf aktiv/deaktiviert/–, control.camera undefined, null oder Wert', () => {
    const d = find('camera', c => c.id === 'wohnzimmer-camera');
    const cameraId = 'binary_sensor.blink_511671_armed';
    expect(cameraId).toBe(d.armedEntityId);
    const none = buildDeviceDetail(d, new Map(), NOW);
    expect(none.headline.text).toBe('–');
    expect(none.control).toEqual({ type: 'camera', cameraId: '511671', camera: undefined });
    const gone = buildDeviceDetail(d, new Map(), NOW, { camera: null });
    expect(gone.headline.text).toBe('–');
    expect((gone.control as { camera: unknown }).camera).toBeNull();
    const cam = { id: '511671', name: 'K', armed: true, lastMotionAt: new Date(NOW).toISOString() } as never;
    const on = buildDeviceDetail(d, new Map(), NOW, { camera: cam });
    expect(on.headline.text).toBe('aktiv');
    expect(on.lines.length).toBe(1);
    const off = buildDeviceDetail(d, new Map(), NOW, { camera: { ...(cam as object), armed: false } as never });
    expect(off.headline.text).toBe('deaktiviert');
  });

  it('Mülltonne: ohne Termine Kopf Keine Abfuhr, ohne extras –, mit Terminen Kopf + Zeilen', () => {
    const d = find('waste', w => w.id === 'front-waste');
    expect(buildDeviceDetail(d, new Map(), NOW, { waste: [] }).headline.text)
      .toBe('Keine Abfuhr in den nächsten Tagen');
    expect(buildDeviceDetail(d, new Map(), NOW).headline.text).toBe('–');
    const events = [
      { date: '2026-11-06', label: 'Restmüll' }, { date: '2026-11-07', label: 'Papier' }
    ] as never;
    const detail = buildDeviceDetail(d, new Map(), NOW, { waste: events });
    expect(detail.headline.text).toBe('Restmüll · morgen');
    expect(detail.lines.map(l => l.text)).toEqual(['Papier · Sa 07.11.']);
    expect(detail.control).toBeNull();
  });

  it('Toni: Kopf zu Hause / unterwegs / –', () => {
    const d = find('pet', p => p.id === 'front-pet-toni');
    expect(buildDeviceDetail(d, mapOf(entity(d.homeEntityId, 'on')), NOW).headline.text).toBe('zu Hause');
    expect(buildDeviceDetail(d, mapOf(entity(d.homeEntityId, 'off')), NOW).headline.text).toBe('unterwegs');
    expect(buildDeviceDetail(d, new Map(), NOW).headline.text).toBe('–');
    expect(buildDeviceDetail(d, new Map(), NOW).lines.length).toBeGreaterThan(0);
  });

  it('Thermostat: Raumzeile zuletzt, mode aus extras', () => {
    const d = find('thermostat', t => thermostatIdOf(t.target) !== null);
    const detail = buildDeviceDetail(d, new Map(), NOW,
      { roomValue: { text: '19,8°', stale: false }, thermostatMode: 'MANUAL' });
    expect(detail.lines[detail.lines.length - 1]).toEqual({ text: 'Raum 19,8°', stale: false });
    expect(detail.control).toEqual(jasmine.objectContaining({ type: 'target', mode: 'MANUAL' }));
    const bare = buildDeviceDetail(d, new Map(), NOW);
    expect(bare.control).toEqual(jasmine.objectContaining({ mode: null }));
  });

  it('Schalter: color nur bei Farbfähigkeit, dimmable aus extras', () => {
    const d = find('switch', s => !!s.powerEntityId);
    const base = {
      dimmable: true, brightness: 50, supportsColor: true, supportsColorTemp: false,
      hue: 10, saturation: 20, colorTemp: null, colorTempRange: { min: 2500, max: 6500 }
    };
    const colored = buildDeviceDetail(d, new Map(), NOW, { light: base });
    const control = colored.control as { dimmable: boolean; color: unknown };
    expect(control.dimmable).toBeTrue();
    expect(control.color).toEqual(base);
    const plain = buildDeviceDetail(d, new Map(), NOW,
      { light: { ...base, supportsColor: false, supportsColorTemp: false } });
    expect((plain.control as { color: unknown }).color).toBeNull();
    expect((buildDeviceDetail(d, new Map(), NOW).control as { dimmable: boolean }).dimmable).toBeFalse();
  });
});

describe('thermostatIdOf', () => {
  it('liest die Id aus der Entity-Id', () => {
    expect(thermostatIdOf('sensor.thermostat_8_temperature')).toBe(8);
    expect(thermostatIdOf('binary_sensor.thermostat_12_heating')).toBe(12);
    expect(thermostatIdOf('switch.x')).toBeNull();
  });
});
