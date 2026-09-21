import { nodeStatus, switchedAtText, NodeStatusSources } from './node-status.util';
import { CanvasNode } from './flow-graph.mapper';
import { EntityState } from '../../models/entity-state.model';
import { SmartDevice } from '../../models/smart-device.model';

describe('node-status.util', () => {
  // 21.09.2026 20:00 lokal
  const NOW = new Date(2026, 8, 21, 20, 0, 0).getTime();

  function entity(partial: Partial<EntityState>): EntityState {
    return {
      entityId: 'binary_sensor.x', domain: 'BINARY_SENSOR', source: 'ZIGBEE', sourceRef: 'x',
      friendlyName: 'X', displayName: 'X', state: 'off', attributes: {},
      lastChanged: '2026-09-21T19:52:19', lastUpdated: '2026-09-21T19:53:16',
      ...partial
    };
  }

  function device(partial: Partial<SmartDevice>): SmartDevice {
    return {
      id: 22, deviceType: 'TAPO', externalDeviceId: 'ABC', deviceName: 'Flur', model: 'L530',
      ipAddress: null, isOnline: true, isPoweredOn: false, capabilities: [], metadata: null,
      confirmRequired: false, createdAt: '', updatedAt: '',
      ...partial
    };
  }

  function node(type: string, config: Record<string, unknown>): CanvasNode {
    return { id: 'n', type, x: 0, y: 0, config };
  }

  function sources(entities: EntityState[] = [], devices: SmartDevice[] = []): NodeStatusSources {
    return { entities, devices };
  }

  describe('switchedAtText', () => {
    it('shows the time with seconds when the change happened today', () => {
      expect(switchedAtText('2026-09-21T19:52:19', NOW)).toBe('seit 19:52:19');
    });

    it('prefixes the date when the change happened on another day', () => {
      expect(switchedAtText('2026-08-21T16:36:57', NOW)).toBe('seit 21.08., 16:36:57');
    });

    it('returns null for an unreadable timestamp', () => {
      expect(switchedAtText('kaputt', NOW)).toBeNull();
    });
  });

  describe('nodeStatus for entity nodes', () => {
    it('shows display name, state and switch time of the referenced entity', () => {
      const e = entity({ entityId: 'binary_sensor.zigbee_motion_flur_occupancy', displayName: 'Motion Flur Bewegung', state: 'off' });
      const status = nodeStatus(node('entity-state-trigger', { entityId: e.entityId }), sources([e]), NOW);

      expect(status).toEqual({ name: 'Motion Flur Bewegung', state: 'off', since: 'seit 19:52:19', tone: 'off' });
    });

    it('marks on and unavailable with their own tone, everything else neutral', () => {
      const on = entity({ entityId: 'a', state: 'on' });
      const unavailable = entity({ entityId: 'b', state: 'unavailable' });
      const sun = entity({ entityId: 'c', state: 'dusk' });
      const src = sources([on, unavailable, sun]);

      expect(nodeStatus(node('entity-condition', { entityId: 'a' }), src, NOW)?.tone).toBe('on');
      expect(nodeStatus(node('entity-condition', { entityId: 'b' }), src, NOW)?.tone).toBe('unavailable');
      expect(nodeStatus(node('entity-condition', { entityId: 'c' }), src, NOW)?.tone).toBe('neutral');
    });

    it('reports an unknown entity instead of hiding a dead reference', () => {
      const status = nodeStatus(node('helper-set', { entityId: 'input_boolean.manual_geloescht' }), sources(), NOW);

      expect(status).toEqual({ name: 'input_boolean.manual_geloescht', state: 'unbekannt', since: null, tone: 'unavailable' });
    });
  });

  describe('nodeStatus for device nodes', () => {
    it('takes state and switch time from the mirrored switch entity', () => {
      const d = device({ id: 22, deviceType: 'TAPO', externalDeviceId: 'ABC', deviceName: 'Flur' });
      const mirror = entity({ entityId: 'switch.tapo_abc', domain: 'SWITCH', source: 'TAPO', sourceRef: 'ABC', state: 'on', lastChanged: '2026-09-21T19:50:08' });

      const status = nodeStatus(node('light-set', { deviceId: 22 }), sources([mirror], [d]), NOW);

      expect(status).toEqual({ name: 'Flur', state: 'on', since: 'seit 19:50:08', tone: 'on' });
    });

    it('ignores a non-switch entity of the same device as mirror (e.g. its power sensor)', () => {
      const d = device({ id: 22, deviceType: 'TAPO', externalDeviceId: 'ABC', deviceName: 'Flur', isOnline: true, isPoweredOn: true });
      const powerSensor = entity({ entityId: 'sensor.tapo_abc_power', domain: 'SENSOR', source: 'TAPO', sourceRef: 'ABC', state: '12.5' });

      const status = nodeStatus(node('light-set', { deviceId: 22 }), sources([powerSensor], [d]), NOW);

      expect(status).toEqual({ name: 'Flur', state: 'on', since: null, tone: 'on' });
    });

    it('accepts the deviceId as string and falls back to the device flags without a mirror', () => {
      const d = device({ id: 29, deviceType: 'KASA', externalDeviceId: 'DEF', deviceName: 'Treppenhaus', isOnline: false, isPoweredOn: false });

      const status = nodeStatus(node('switch-device', { deviceId: '29' }), sources([], [d]), NOW);

      expect(status).toEqual({ name: 'Treppenhaus', state: 'offline', since: null, tone: 'unavailable' });
    });

    it('derives on/off from the device flags when online and no mirror exists', () => {
      const d = device({ id: 5, externalDeviceId: 'GHI', deviceName: 'Fernseher', isOnline: true, isPoweredOn: true });

      expect(nodeStatus(node('switch-device', { deviceId: 5 }), sources([], [d]), NOW))
        .toEqual({ name: 'Fernseher', state: 'on', since: null, tone: 'on' });
    });

    it('reports an unknown device', () => {
      expect(nodeStatus(node('switch-device', { deviceId: 50 }), sources(), NOW))
        .toEqual({ name: 'Gerät 50', state: 'unbekannt', since: null, tone: 'unavailable' });
    });
  });

  it('returns null for nodes without entity or device reference', () => {
    expect(nodeStatus(node('time-condition', { from: 'sunset-30', to: 'sunrise' }), sources(), NOW)).toBeNull();
    expect(nodeStatus(node('delay', { seconds: 5 }), sources(), NOW)).toBeNull();
    expect(nodeStatus(node('entity-state-trigger', {}), sources(), NOW)).toBeNull();
  });
});
