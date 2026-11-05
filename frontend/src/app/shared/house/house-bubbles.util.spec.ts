import { EntityState } from '../../models/entity-state.model';
import { TABLET_VIEWS } from '../tablet-views';
import { HOUSE_DEVICES } from './house-devices';
import {
  bubbleTarget, buildDeviceBubbles, buildOutdoorBubbles, buildRoomBubble, parseLocalTimestamp
} from './house-bubbles.util';

const NOW = new Date(2026, 10, 5, 10, 0).getTime();
const MIN = 60_000;

function iso(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function entity(entityId: string, state: string, ageMin = 1, attributes: Record<string, unknown> = {}): EntityState {
  return {
    entityId, domain: 'SENSOR', source: 'X', sourceRef: 'x', friendlyName: entityId, displayName: entityId,
    state, attributes, lastChanged: iso(NOW - ageMin * MIN), lastUpdated: iso(NOW - ageMin * MIN)
  };
}

function mapOf(...entities: EntityState[]): Map<string, EntityState> {
  return new Map(entities.map(e => [e.entityId, e]));
}

const ALEXA = 'sensor.alexa_gaj2300425330047';
const PC_SWITCH = 'switch.meross_2112159340612090863548e1e98183ba';
const PC_POWER = 'sensor.meross_2112159340612090863548e1e98183ba_power';

describe('house-bubbles.util', () => {
  it('parseLocalTimestamp liest Zeit ohne Zone als Lokalzeit', () => {
    expect(parseLocalTimestamp('2026-11-05T09:30:58')).toBe(new Date(2026, 10, 5, 9, 30, 58).getTime());
  });

  describe('Raum-Bubble', () => {
    it('nimmt den Raumfühler, nicht das Thermostat', () => {
      const bubble = buildRoomBubble('wohnzimmer', mapOf(
        entity(`${ALEXA}_temperature`, '19.5'),
        entity(`${ALEXA}_humidity`, '45'),
        entity('sensor.thermostat_8_temperature', '20.1')), NOW);
      expect(bubble.temperature.text).toBe('19,5°');
      expect(bubble.humidity?.text).toBe('45 %');
      expect(bubble.label).toBe('Wohnzimmer');
    });

    it('fällt ohne lesbaren Raumfühler auf das erste Thermostat zurück', () => {
      const bubble = buildRoomBubble('wohnzimmer', mapOf(
        entity(`${ALEXA}_temperature`, 'unavailable'),
        entity('sensor.thermostat_8_temperature', '20.1')), NOW);
      expect(bubble.temperature.text).toBe('20,1°');
    });

    it('gilt ab 60 min als veraltet', () => {
      const old = buildRoomBubble('wohnzimmer', mapOf(entity(`${ALEXA}_temperature`, '19.5', 61)), NOW);
      const fresh = buildRoomBubble('wohnzimmer', mapOf(entity(`${ALEXA}_temperature`, '19.5', 59)), NOW);
      expect(old.temperature.stale).toBeTrue();
      expect(fresh.temperature.stale).toBeFalse();
    });

    it('zeigt bei unavailable „–“ und gilt als veraltet', () => {
      const bubble = buildRoomBubble('paincave', mapOf(
        entity('sensor.zigbee_temperatur_buero_temperature', 'unavailable'),
        entity('sensor.zigbee_temperatur_buero_humidity', 'unavailable')), NOW);
      expect(bubble.temperature).toEqual({ text: '–', stale: true });
      expect(bubble.humidity).toEqual({ text: '–', stale: true });
    });

    it('wirft bei fehlender Entität nicht', () => {
      expect(buildRoomBubble('wohnzimmer', new Map(), NOW).temperature.text).toBe('–');
    });

    it('setzt Flags nur bei Abweichung', () => {
      expect(buildRoomBubble('bad', mapOf(entity('binary_sensor.zigbee_fenster_badezimmer_contact', 'on')), NOW).flags)
        .toContain('window_open');
      expect(buildRoomBubble('wohnzimmer', mapOf(entity('binary_sensor.thermostat_8_heating', 'on')), NOW).flags)
        .toContain('heating');
      expect(buildRoomBubble('flur', mapOf(entity('binary_sensor.zigbee_motion_flur_occupancy', 'on')), NOW).flags)
        .toContain('motion');
      expect(buildRoomBubble('waschkeller', mapOf(entity('input_boolean.manual_waschmaschine_laeuft', 'on')), NOW).flags)
        .toContain('appliance_running');
      expect(buildRoomBubble('waschkeller', mapOf(entity('input_boolean.manual_waschmaschine_fertig', 'on')), NOW).flags)
        .toContain('appliance_finished');
      expect(buildRoomBubble('buero', mapOf(entity(PC_POWER, '221'), entity(PC_SWITCH, 'on')), NOW).flags)
        .toEqual([]);
      expect(buildRoomBubble('bad', new Map(), NOW).flags).toEqual([]);
    });

    it('setzt bei Kontakt unavailable kein window_open', () => {
      expect(buildRoomBubble('bad', mapOf(entity('binary_sensor.zigbee_fenster_badezimmer_contact', 'unavailable')), NOW).flags)
        .toEqual([]);
    });
  });

  describe('Geräte-Bubbles', () => {
    it('liefert je Gerät eine Bubble', () => {
      const bubbles = buildDeviceBubbles('buero', mapOf(
        entity('sensor.thermostat_7_temperature', '19.5'),
        entity('sensor.thermostat_7_target', '20'),
        entity(PC_SWITCH, 'on'),
        entity(PC_POWER, '221')), NOW, false);
      expect(bubbles.length).toBe(HOUSE_DEVICES.filter(d => d.area === 'buero').length);
      expect(bubbles.length).toBe(8);

      const thermostat = bubbles.find(b => b.icon === 'mode_heat')!;
      expect(thermostat.lines.map(l => l.text)).toEqual(['19,5°', 'Soll 20°']);

      const pc = bubbles.find(b => b.label === 'PC')!;
      expect(pc.lines.map(l => l.text)).toEqual(['an', '221 W']);
    });

    it('zeigt Watt nur bei an', () => {
      const bubbles = buildDeviceBubbles('buero', mapOf(entity(PC_SWITCH, 'off'), entity(PC_POWER, '0')), NOW, false);
      expect(bubbles.find(b => b.label === 'PC')!.lines.map(l => l.text)).toEqual(['aus']);
    });

    it('hebt die laufende Waschmaschine hervor', () => {
      const bubbles = buildDeviceBubbles('waschkeller',
        mapOf(entity('input_boolean.manual_waschmaschine_laeuft', 'on')), NOW, false);
      const wm = bubbles.find(b => b.icon === 'local_laundry_service')!;
      expect(wm.highlight).toBeTrue();
      expect(wm.lines[0].text).toBe('läuft');
    });

    it('übernimmt das Ziel nach Modus', () => {
      const web = buildDeviceBubbles('bad', new Map(), NOW, false);
      const tablet = buildDeviceBubbles('bad', new Map(), NOW, true);
      expect(web.find(b => b.icon === 'sensor_window')!.target).toBe('/entities');
      expect(tablet.find(b => b.icon === 'sensor_window')!.target).toBe('/');
    });
  });

  describe('Außen', () => {
    it('Mülltonne: morgen ist hervorgehoben', () => {
      const bubbles = buildOutdoorBubbles(mapOf(
        entity('sensor.waste_calendar_next_collection', '2026-11-06', 1, { label: 'Biotonne' })), NOW, false);
      const waste = bubbles.find(b => b.icon === 'delete')!;
      expect(waste.lines[0].text).toBe('Morgen Abfuhr');
      expect(waste.lines[1].text).toBe('Biotonne');
      expect(waste.highlight).toBeTrue();
    });

    it('Mülltonne: später zeigt Wochentag und Datum ohne Hervorhebung', () => {
      const bubbles = buildOutdoorBubbles(mapOf(
        entity('sensor.waste_calendar_next_collection', '2026-11-10')), NOW, false);
      const waste = bubbles.find(b => b.icon === 'delete')!;
      expect(waste.lines[0].text).toBe('Di. 10.11.');
      expect(waste.highlight).toBeFalse();
    });

    it('enthält Geräte von Garten und Vor dem Haus', () => {
      const areas = new Set(buildOutdoorBubbles(new Map(), NOW, false).map(b => b.areaId));
      expect(areas).toEqual(new Set(['front', 'garden']));
    });
  });

  describe('bubbleTarget', () => {
    it('Ziele Website', () => {
      expect(bubbleTarget('climate', false)).toBe('/temperatures');
      expect(bubbleTarget('thermostat', false)).toBe('/tablet/heating');
      expect(bubbleTarget('switch', false)).toBe('/devices');
      expect(bubbleTarget('waste', false)).toBe('/waste-collection');
      expect(bubbleTarget('camera', false)).toBe('/cameras');
      expect(bubbleTarget('pet', false)).toBe('/pets');
      for (const kind of ['contact', 'motion', 'button', 'lock'] as const) {
        expect(bubbleTarget(kind, false)).toBe('/entities');
      }
    });

    it('Ziele Tablet führen nie auf Seiten ohne Shell', () => {
      const allowed = ['/', ...TABLET_VIEWS.map(v => v.route)];
      const kinds = ['climate', 'thermostat', 'contact', 'motion', 'button', 'switch', 'appliance', 'lock',
        'camera', 'waste', 'pet'] as const;
      for (const kind of kinds) {
        expect(allowed).withContext(kind).toContain(bubbleTarget(kind, true));
      }
      expect(bubbleTarget('climate', true)).toBe('/tablet/temperatures');
      expect(bubbleTarget('camera', true)).toBe('/tablet/cameras');
      expect(bubbleTarget('pet', true)).toBe('/tablet/toni');
      for (const kind of ['waste', 'switch', 'contact', 'motion', 'button', 'lock'] as const) {
        expect(bubbleTarget(kind, true)).toBe('/');
      }
    });
  });
});
