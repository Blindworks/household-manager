import { BlinkCamera } from '../../models/blink.model';
import { EntityState } from '../../models/entity-state.model';
import { ThermostatMode } from '../../models/thermostat.model';
import { DetailExtras, LightExtras, NO_WASTE_TEXT, cameraIdOf, lockBatteryLine, motionLine, petLines,
  smartlockIdOf, wasteLines } from './detail-extras.util';
import { BubbleValue, EntityMap, MISSING, buttonActionText, deviceIcon, fresh, humidity, isOn, isReadable,
  livePower, lockText, onOff, parseLocalTimestamp, temperature } from './house-bubbles.util';
import { HouseDevice } from './house-devices';

export type HistorySource =
  | { type: 'temperature'; entityId: string }
  | { type: 'thermostat'; thermostatId: number }
  | { type: 'power'; entityId: string };

export type DetailControl =
  | {
      type: 'target'; thermostatId: number; target: number | null; min: 5; max: 30; step: 0.5;
      mode: ThermostatMode | null;
    }
  | {
      type: 'switch'; entityId: string; on: boolean | null; confirmOff: boolean; dimmable: boolean;
      /** Nur gesetzt, wenn die Lampe Farbe oder Farbtemperatur kann. */
      color: LightExtras | null;
      /** Nur bei Maschinen: Lauf-Helfer, den der Dienst beim Ausschalten frisch prueft. */
      runningEntityId?: string;
    }
  | { type: 'lock'; smartlockId: number; locked: boolean | null }
  /** camera: undefined = noch nicht geladen, null = nicht erreichbar. */
  | { type: 'camera'; cameraId: string; camera: BlinkCamera | null | undefined };

export interface DeviceDetail {
  deviceId: string;
  title: string;
  icon: string;
  headline: BubbleValue;
  lines: BubbleValue[];
  history: HistorySource | null;
  control: DetailControl | null;
}

type DetailBody = Pick<DeviceDetail, 'headline' | 'lines' | 'history' | 'control'>;

/** Jedes Geraet mit Anker oeffnet das Feld; bubbleTarget bleibt nur Rueckfall ohne Anker. */
export function opensDetail(_kind: HouseDevice['kind']): boolean {
  return true;
}

/** 'sensor.thermostat_8_target' → 8 */
export function thermostatIdOf(entityId: string): number | null {
  const match = /\.thermostat_(\d+)_/.exec(entityId);
  return match ? Number(match[1]) : null;
}

function sinceLine(entity: EntityState | undefined): BubbleValue[] {
  if (!isReadable(entity)) {
    return [];
  }
  const changed = new Date(parseLocalTimestamp(entity.lastChanged));
  if (Number.isNaN(changed.getTime())) {
    return [];
  }
  const p = (n: number) => String(n).padStart(2, '0');
  return [fresh(`seit ${p(changed.getHours())}:${p(changed.getMinutes())}`)];
}

function targetValue(entities: EntityMap, entityId: string): number | null {
  const entity = entities.get(entityId);
  if (!isReadable(entity) || entity.state.trim() === '') {
    return null;
  }
  const value = Number(entity.state);
  return Number.isFinite(value) ? value : null;
}

function switchState(entities: EntityMap, entityId: string): boolean | null {
  const state = entities.get(entityId)?.state;
  return state === 'on' ? true : state === 'off' ? false : null;
}

function powerLines(entities: EntityMap, switchEntityId: string, powerEntityId: string | undefined, nowMs: number)
  : BubbleValue[] {
  if (!powerEntityId || !isOn(entities, switchEntityId)) {
    return [];
  }
  const power = livePower(entities, powerEntityId, nowMs);
  return power ? [power] : [];
}

function colorExtras(light: LightExtras | null | undefined): LightExtras | null {
  return light && (light.supportsColor || light.supportsColorTemp) ? light : null;
}

function doorBody(device: Extract<HouseDevice, { kind: 'door' }>, entities: EntityMap, extras: DetailExtras)
  : DetailBody {
  const lock = entities.get(device.lockEntityId);
  const contact = entities.get(device.contactEntityId);
  const smartlockId = extras.lock?.smartlockId ?? smartlockIdOf(device.lockEntityId);
  const battery = lockBatteryLine(extras.lock);
  return {
    headline: !isReadable(lock) ? MISSING : fresh(lockText(lock.state)),
    lines: [
      ...(isReadable(contact) ? [fresh(contact.state === 'on' ? 'offen' : 'zu'), ...sinceLine(contact)] : []),
      ...(battery ? [battery] : [])
    ],
    history: null,
    control: extras.lock === null || smartlockId === null ? null : {
      type: 'lock', smartlockId, locked: !isReadable(lock) ? null : lock.state === 'locked'
    }
  };
}

function cameraBody(device: Extract<HouseDevice, { kind: 'camera' }>, nowMs: number, extras: DetailExtras)
  : DetailBody {
  const cameraId = cameraIdOf(device.armedEntityId);
  const camera = extras.camera;
  const motion = camera ? motionLine(camera.lastMotionAt, new Date(nowMs)) : null;
  return {
    headline: camera ? fresh(camera.armed ? 'aktiv' : 'deaktiviert') : MISSING,
    lines: motion ? [motion] : [],
    history: null,
    control: cameraId === null ? null : { type: 'camera', cameraId, camera }
  };
}

function wasteBody(extras: DetailExtras, nowMs: number): DetailBody {
  if (extras.waste === undefined) {
    return { headline: MISSING, lines: [], history: null, control: null };
  }
  const [first, ...rest] = wasteLines(extras.waste, new Date(nowMs));
  return { headline: first ?? fresh(NO_WASTE_TEXT), lines: rest, history: null, control: null };
}

function detailBody(device: HouseDevice, entities: EntityMap, nowMs: number, extras: DetailExtras): DetailBody {
  switch (device.kind) {
    case 'climate': {
      const lines = device.humidity ? [humidity(entities, device.humidity, nowMs)] : [];
      return {
        headline: device.temperature ? temperature(entities, device.temperature, nowMs) : MISSING,
        lines,
        history: device.temperature ? { type: 'temperature', entityId: device.temperature } : null,
        control: null
      };
    }
    case 'thermostat': {
      const thermostatId = thermostatIdOf(device.target);
      const target = temperature(entities, device.target, nowMs);
      const heating = entities.get(device.heating);
      const room = extras.roomValue;
      return {
        headline: temperature(entities, device.temperature, nowMs),
        lines: [
          target.text === '–' ? target : { ...target, text: `Soll ${target.text.replace(',0°', '°')}` },
          !isReadable(heating) ? MISSING : fresh(heating.state === 'on' ? 'heizt' : 'heizt nicht'),
          ...(room ? [{ ...room, text: `Raum ${room.text}` }] : [])
        ],
        history: thermostatId === null ? null : { type: 'thermostat', thermostatId },
        control: thermostatId === null ? null : {
          type: 'target', thermostatId, target: targetValue(entities, device.target), min: 5, max: 30, step: 0.5,
          mode: extras.thermostatMode ?? null
        }
      };
    }
    case 'switch':
      return {
        headline: onOff(entities, device.entityId),
        lines: powerLines(entities, device.entityId, device.powerEntityId, nowMs),
        history: device.powerEntityId ? { type: 'power', entityId: device.powerEntityId } : null,
        control: {
          type: 'switch', entityId: device.entityId, on: switchState(entities, device.entityId),
          confirmOff: entities.get(device.entityId)?.confirmRequired === true,
          dimmable: extras.light?.dimmable === true, color: colorExtras(extras.light)
        }
      };
    case 'appliance': {
      const running = isOn(entities, device.runningEntityId);
      const finished = isOn(entities, device.finishedEntityId);
      const switchEntity = entities.get(device.switchEntityId);
      return {
        headline: running ? fresh('läuft') : finished ? fresh('fertig')
          : isReadable(switchEntity) ? fresh('aus') : MISSING,
        lines: powerLines(entities, device.switchEntityId, device.powerEntityId, nowMs),
        history: { type: 'power', entityId: device.powerEntityId },
        control: {
          type: 'switch', entityId: device.switchEntityId, on: switchState(entities, device.switchEntityId),
          confirmOff: running || switchEntity?.confirmRequired === true, dimmable: false, color: null,
          // confirmOff stammt aus dem bis zu 30 s alten Stand; beim Ausschalten prueft der Dienst den Helfer frisch.
          runningEntityId: device.runningEntityId
        }
      };
    }
    case 'contact': {
      const entity = entities.get(device.entityId);
      return {
        headline: !isReadable(entity) ? MISSING : fresh(entity.state === 'on' ? 'offen' : 'zu'),
        lines: sinceLine(entity), history: null, control: null
      };
    }
    case 'motion': {
      const entity = entities.get(device.entityId);
      return {
        headline: !isReadable(entity) ? MISSING : fresh(entity.state === 'on' ? 'Bewegung' : 'ruhig'),
        lines: sinceLine(entity), history: null, control: null
      };
    }
    case 'button': {
      const latest = device.entityIds.map(id => entities.get(id)).filter(isReadable)
        .sort((a, b) => parseLocalTimestamp(b.lastUpdated) - parseLocalTimestamp(a.lastUpdated))[0];
      return {
        headline: latest ? fresh(buttonActionText(latest.state)) : MISSING,
        lines: sinceLine(latest), history: null, control: null
      };
    }
    case 'door':
      return doorBody(device, entities, extras);
    case 'camera':
      return cameraBody(device, nowMs, extras);
    case 'waste':
      return wasteBody(extras, nowMs);
    case 'pet': {
      const home = entities.get(device.homeEntityId);
      return {
        headline: !isReadable(home) ? MISSING : fresh(home.state === 'on' ? 'zu Hause' : 'unterwegs'),
        lines: petLines(extras.pet, extras.supplies), history: null, control: null
      };
    }
    default:
      return { headline: MISSING, lines: [], history: null, control: null };
  }
}

export function buildDeviceDetail(device: HouseDevice, entities: EntityMap, nowMs: number,
                                  extras: DetailExtras = {}): DeviceDetail {
  return {
    deviceId: device.id, title: device.label, icon: deviceIcon(device),
    ...detailBody(device, entities, nowMs, extras)
  };
}
