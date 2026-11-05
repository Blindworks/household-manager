import { EntityState } from '../../models/entity-state.model';
import { STALE_THRESHOLD_MS } from '../temperature-comfort.util';
import { HOUSE_DEVICES, HouseDevice, devicesInArea } from './house-devices';
import { OUTDOOR_AREAS, roomById } from './house-plan';

export type BubbleFlag = 'window_open' | 'heating' | 'appliance_running' | 'appliance_finished' | 'motion';

/** `text` ist '–' bei fehlendem, `unavailable`- oder `unknown`-Wert. */
export interface BubbleValue { text: string; stale: boolean }

export interface RoomBubble {
  areaId: string;
  label: string;
  temperature: BubbleValue;
  humidity: BubbleValue | null;
  flags: BubbleFlag[];
}

export interface DeviceBubble {
  deviceId: string;
  areaId: string;
  icon: string;
  label: string;
  lines: BubbleValue[];
  target: string;
  highlight: boolean;
}

type EntityMap = ReadonlyMap<string, EntityState>;

const MISSING: BubbleValue = { text: '–', stale: true };
const UNREADABLE_STATES = new Set(['unavailable', 'unknown', '']);
const WEEKDAYS = ['So.', 'Mo.', 'Di.', 'Mi.', 'Do.', 'Fr.', 'Sa.'];
const DAY_MS = 24 * 60 * 60 * 1000;

/** LocalDateTime ohne Zone (lokale Haushaltszeit) als Epoch-Millisekunden. */
export function parseLocalTimestamp(value: string): number {
  return new Date(value).getTime();
}

function isReadable(entity: EntityState | undefined): entity is EntityState {
  return !!entity && !UNREADABLE_STATES.has(entity.state);
}

function isOn(entities: EntityMap, entityId: string | undefined): boolean {
  return !!entityId && entities.get(entityId)?.state === 'on';
}

function formatNumber(raw: string, fractionDigits: number): string | null {
  const value = Number(raw);
  if (raw.trim() === '' || !Number.isFinite(value)) {
    return null;
  }
  return value.toFixed(fractionDigits).replace('.', ',');
}

function isStale(entity: EntityState, nowMs: number): boolean {
  return nowMs - parseLocalTimestamp(entity.lastUpdated) > STALE_THRESHOLD_MS;
}

/** Messwert mit Einheit; zeitliche Veraltung nur, wenn `ageSensitive` (Klima, Thermostat). */
function measured(entities: EntityMap, entityId: string | undefined, nowMs: number,
                  fractionDigits: number, suffix: string): BubbleValue {
  const entity = entityId ? entities.get(entityId) : undefined;
  if (!isReadable(entity)) {
    return MISSING;
  }
  const formatted = formatNumber(entity.state, fractionDigits);
  if (formatted === null) {
    return MISSING;
  }
  return { text: formatted + suffix, stale: isStale(entity, nowMs) };
}

const temperature = (e: EntityMap, id: string | undefined, now: number) => measured(e, id, now, 1, '°');
const humidity = (e: EntityMap, id: string | undefined, now: number) => measured(e, id, now, 0, ' %');

function fresh(text: string): BubbleValue {
  return { text, stale: false };
}

function readableNumber(entities: EntityMap, entityId: string | undefined): boolean {
  const entity = entityId ? entities.get(entityId) : undefined;
  return isReadable(entity) && formatNumber(entity.state, 1) !== null;
}

export function buildRoomBubble(areaId: string, entities: EntityMap, nowMs: number): RoomBubble {
  const devices = devicesInArea(areaId);
  const label = roomById(areaId)?.label ?? OUTDOOR_AREAS.find(a => a.id === areaId)?.label ?? areaId;

  const sensor = devices.find((d): d is Extract<HouseDevice, { kind: 'climate' }> =>
    d.kind === 'climate' && d.roomSensor);
  const thermostats = devices.filter((d): d is Extract<HouseDevice, { kind: 'thermostat' }> => d.kind === 'thermostat');

  let temperatureValue: BubbleValue;
  if (sensor && readableNumber(entities, sensor.temperature)) {
    temperatureValue = temperature(entities, sensor.temperature, nowMs);
  } else {
    const fallback = thermostats.find(t => readableNumber(entities, t.temperature));
    temperatureValue = temperature(entities, (fallback ?? sensor ?? thermostats[0])?.temperature, nowMs);
  }
  const humidityValue = sensor?.humidity ? humidity(entities, sensor.humidity, nowMs) : null;

  return { areaId, label, temperature: temperatureValue, humidity: humidityValue, flags: collectFlags(devices, entities) };
}

function collectFlags(devices: HouseDevice[], entities: EntityMap): BubbleFlag[] {
  const flags = new Set<BubbleFlag>();
  for (const device of devices) {
    switch (device.kind) {
      case 'contact':
        // Der Gaszaehler-Reedkontakt ist kein Fenster.
        if (!device.entityId.includes('sensor_gas') && isOn(entities, device.entityId)) {
          flags.add('window_open');
        }
        break;
      case 'thermostat':
        if (isOn(entities, device.heating)) {
          flags.add('heating');
        }
        break;
      case 'motion':
        if (isOn(entities, device.entityId)) {
          flags.add('motion');
        }
        break;
      case 'appliance':
        if (isOn(entities, device.runningEntityId)) {
          flags.add('appliance_running');
        }
        if (isOn(entities, device.finishedEntityId)) {
          flags.add('appliance_finished');
        }
        break;
      default:
        break;
    }
  }
  return [...flags];
}

export function bubbleTarget(kind: HouseDevice['kind'], tabletMode: boolean): string {
  if (tabletMode) {
    switch (kind) {
      case 'climate': return '/tablet/temperatures';
      case 'thermostat': return '/tablet/heating';
      case 'camera': return '/tablet/cameras';
      case 'pet': return '/tablet/toni';
      default: return '/';
    }
  }
  switch (kind) {
    case 'climate': return '/temperatures';
    case 'thermostat': return '/tablet/heating';
    case 'switch':
    case 'appliance': return '/devices';
    case 'waste': return '/waste-collection';
    case 'camera': return '/cameras';
    case 'pet': return '/pets';
    case 'contact':
    case 'motion':
    case 'button':
    case 'door': return '/entities';
  }
}

const ICONS: Record<HouseDevice['kind'], string> = {
  climate: 'thermostat', thermostat: 'mode_heat', contact: 'sensor_window', motion: 'directions_walk',
  button: 'radio_button_checked', switch: 'power', appliance: 'local_laundry_service', door: 'door_front',
  camera: 'videocam', waste: 'delete', pet: 'pets'
};

function onOff(entities: EntityMap, entityId: string): BubbleValue {
  const entity = entities.get(entityId);
  if (!isReadable(entity)) {
    return MISSING;
  }
  return fresh(entity.state === 'on' ? 'an' : 'aus');
}

function localDayStart(ms: number): number {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

function wasteLines(entities: EntityMap, entityId: string, nowMs: number): { lines: BubbleValue[]; highlight: boolean } {
  const entity = entities.get(entityId);
  if (!isReadable(entity) || !/^\d{4}-\d{2}-\d{2}$/.test(entity.state)) {
    return { lines: [MISSING], highlight: false };
  }
  const [y, m, d] = entity.state.split('-').map(Number);
  const collection = new Date(y, m - 1, d);
  const days = Math.round((collection.getTime() - localDayStart(nowMs)) / DAY_MS);
  const lines: BubbleValue[] = [];
  let highlight = false;
  if (days <= 0) {
    lines.push(fresh('Heute Abfuhr'));
    highlight = true;
  } else if (days === 1) {
    lines.push(fresh('Morgen Abfuhr'));
    highlight = true;
  } else {
    const dd = String(d).padStart(2, '0');
    const mm = String(m).padStart(2, '0');
    lines.push(fresh(`${WEEKDAYS[collection.getDay()]} ${dd}.${mm}.`));
  }
  const label = entity.attributes?.['label'];
  if (typeof label === 'string' && label.trim() !== '') {
    lines.push(fresh(label));
  }
  return { lines, highlight };
}

function lockText(state: string): string {
  switch (state) {
    case 'locked': return 'verriegelt';
    case 'unlocked': return 'entriegelt';
    case 'unlatched': return 'geöffnet';
    case 'jammed': return 'klemmt';
    default: return state;
  }
}

function lastButtonAction(entities: EntityMap, entityIds: string[]): BubbleValue {
  const readable = entityIds.map(id => entities.get(id)).filter(isReadable)
    .sort((a, b) => parseLocalTimestamp(b.lastUpdated) - parseLocalTimestamp(a.lastUpdated));
  return readable.length > 0 ? fresh(readable[0].state) : MISSING;
}

function bubbleFor(device: HouseDevice, entities: EntityMap, nowMs: number, tabletMode: boolean): DeviceBubble {
  let lines: BubbleValue[] = [];
  let highlight = false;
  let icon = ICONS[device.kind];

  switch (device.kind) {
    case 'climate':
      if (device.temperature) {
        lines.push(temperature(entities, device.temperature, nowMs));
      }
      if (device.humidity) {
        lines.push(humidity(entities, device.humidity, nowMs));
      }
      if (device.iaq) {
        const isFineDust = device.iaq.includes('pm25');
        const value = measured(entities, device.iaq, nowMs, isFineDust ? 1 : 0, '');
        lines.push(value.text === '–' ? value
          : { ...value, text: `${isFineDust ? 'PM2.5' : 'IAQ'} ${value.text}` });
      }
      break;
    case 'thermostat': {
      lines.push(temperature(entities, device.temperature, nowMs));
      const target = temperature(entities, device.target, nowMs);
      lines.push(target.text === '–' ? target : { ...target, text: `Soll ${target.text.replace(',0°', '°')}` });
      highlight = isOn(entities, device.heating);
      break;
    }
    case 'contact': {
      const entity = entities.get(device.entityId);
      lines.push(!isReadable(entity) ? MISSING : fresh(entity.state === 'on' ? 'offen' : 'zu'));
      highlight = entity?.state === 'on';
      break;
    }
    case 'motion': {
      const entity = entities.get(device.entityId);
      lines.push(!isReadable(entity) ? MISSING : fresh(entity.state === 'on' ? 'Bewegung' : 'ruhig'));
      highlight = entity?.state === 'on';
      break;
    }
    case 'button':
      lines.push(lastButtonAction(entities, device.entityIds));
      break;
    case 'switch': {
      const state = onOff(entities, device.entityId);
      lines.push(state);
      if (device.powerEntityId && isOn(entities, device.entityId)) {
        const power = measured(entities, device.powerEntityId, nowMs, 0, ' W');
        if (power.text !== '–') {
          lines.push({ text: power.text, stale: false });
        }
      }
      break;
    }
    case 'appliance': {
      const running = isOn(entities, device.runningEntityId);
      const finished = isOn(entities, device.finishedEntityId);
      lines.push(fresh(running ? 'läuft' : finished ? 'fertig' : 'aus'));
      highlight = running || finished;
      icon = device.label.includes('Spül') ? 'dishwasher' : 'local_laundry_service';
      break;
    }
    case 'door': {
      const lock = entities.get(device.lockEntityId);
      lines.push(!isReadable(lock) ? MISSING : fresh(lockText(lock.state)));
      const open = isOn(entities, device.contactEntityId);
      if (open) {
        lines.push(fresh('Tür offen'));
      }
      highlight = open;
      break;
    }
    case 'camera': {
      const entity = entities.get(device.armedEntityId);
      lines.push(!isReadable(entity) ? MISSING : fresh(entity.state === 'on' ? 'aktiviert' : 'deaktiviert'));
      break;
    }
    case 'waste': {
      const waste = wasteLines(entities, device.entityId, nowMs);
      lines = waste.lines;
      highlight = waste.highlight;
      break;
    }
    case 'pet': {
      const entity = entities.get(device.homeEntityId);
      lines.push(!isReadable(entity) ? MISSING : fresh(entity.state === 'on' ? 'zu Hause' : 'unterwegs'));
      break;
    }
  }

  return {
    deviceId: device.id, areaId: device.area, icon, label: device.label, lines,
    target: bubbleTarget(device.kind, tabletMode), highlight
  };
}

export function buildDeviceBubbles(areaId: string, entities: EntityMap, nowMs: number, tabletMode: boolean): DeviceBubble[] {
  return devicesInArea(areaId).map(device => bubbleFor(device, entities, nowMs, tabletMode));
}

/** Alle Geraete der Bereiche 'front' und 'garden' (v1: Muelltonne, Toni, Haustuer, Garten). */
export function buildOutdoorBubbles(entities: EntityMap, nowMs: number, tabletMode: boolean): DeviceBubble[] {
  return HOUSE_DEVICES
    .filter(device => device.area === 'front' || device.area === 'garden')
    .map(device => bubbleFor(device, entities, nowMs, tabletMode));
}
