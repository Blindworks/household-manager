import { HOUSE_ROOMS, OUTDOOR_AREAS } from './house-plan';
import { HOUSE_DEVICES, HouseDevice } from './house-devices';

const ENTITY_ID = /^(sensor|binary_sensor|switch|lock|event|input_boolean)\./;

function entityIdsOf(device: HouseDevice): string[] {
  return Object.values(device).flat().filter((v): v is string => typeof v === 'string' && ENTITY_ID.test(v));
}

describe('HOUSE_DEVICES', () => {
  it('jede area ist ein Raum aus HOUSE_ROOMS oder eine Außenfläche', () => {
    const areas = new Set<string>([...HOUSE_ROOMS.map(r => r.id), ...OUTDOOR_AREAS.map(a => a.id)]);
    for (const device of HOUSE_DEVICES) {
      expect(areas.has(device.area)).withContext(device.id).toBeTrue();
    }
  });

  it('Geräte-Ids sind eindeutig', () => {
    const ids = HOUSE_DEVICES.map(d => d.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('keine Entity-ID kommt in zwei Geräten vor', () => {
    const all = HOUSE_DEVICES.flatMap(entityIdsOf);
    const duplicates = all.filter((id, i) => all.indexOf(id) !== i);
    expect(duplicates).toEqual([]);
  });

  it('jeder Raum außer gaeste_wc und vorratskeller hat mindestens ein Gerät', () => {
    for (const room of HOUSE_ROOMS.filter(r => r.id !== 'gaeste_wc' && r.id !== 'vorratskeller')) {
      expect(HOUSE_DEVICES.some(d => d.area === room.id)).withContext(room.id).toBeTrue();
    }
  });

  it('je Raum höchstens ein climate-Gerät mit roomSensor: true', () => {
    const areas = new Set(HOUSE_DEVICES.map(d => d.area));
    for (const area of areas) {
      const count = HOUSE_DEVICES.filter(d => d.area === area && d.kind === 'climate' && d.roomSensor).length;
      expect(count).withContext(area).toBeLessThanOrEqual(1);
    }
  });

  it('Balkontür und Router sind nicht zugeordnet', () => {
    const all = HOUSE_DEVICES.flatMap(entityIdsOf);
    expect(all).not.toContain('binary_sensor.dirigera_f3992ae92ca54fb6b276c2c4d5f832c9_1_contact');
    expect(all).not.toContain('switch.tapo_80223493d58341bf26cdbbf42d330231243f3a4d');
  });
});
