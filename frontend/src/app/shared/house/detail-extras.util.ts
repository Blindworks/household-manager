import { BlinkCamera } from '../../models/blink.model';
import { PetSupply } from '../../models/pet-supply.model';
import { ThermostatMode } from '../../models/thermostat.model';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
import { WasteCollectionEvent } from '../../models/waste-collection.model';
import { walkDistance, walkDuration, walkTimeRange } from '../walk-format.util';
import { BubbleValue, MISSING, fresh } from './house-bubbles.util';
import { ColorTempRange } from './light-color.util';

export interface LightExtras {
  dimmable: boolean; brightness: number | null;
  supportsColor: boolean; supportsColorTemp: boolean;
  hue: number | null; saturation: number | null; colorTemp: number | null;
  colorTempRange: ColorTempRange;
}
export interface LockExtras { smartlockId: number; batteryCharge: number | null; batteryCritical: boolean }
export interface PetExtras { pet: TractivePet | null; lastWalk: TractiveWalk | null }

/** Ein fehlender Schluessel heisst: noch nie erfolgreich geladen. */
export interface DetailExtras {
  light?: LightExtras | null;              // null = kein Smart-Device zur Entity
  roomValue?: BubbleValue | null;
  thermostatMode?: ThermostatMode | null;
  lock?: LockExtras | null;                // null = Schloss nicht in der Liste
  camera?: BlinkCamera | null;             // null = Kamera nicht in der Liste
  pet?: PetExtras;
  supplies?: PetSupply[];
  waste?: WasteCollectionEvent[];
}

export const NO_WASTE_TEXT = 'Keine Abfuhr in den nächsten Tagen';

const WEEKDAYS = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** 'YYYY-MM-DD' als lokales Datum, nicht als UTC. */
function parseLocalDate(isoDate: string): Date {
  const [year, month, day] = isoDate.split('-').map(Number);
  return new Date(year, month - 1, day);
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

function dayDiff(target: Date, today: Date): number {
  return Math.round((Date.UTC(target.getFullYear(), target.getMonth(), target.getDate())
    - Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())) / 86_400_000);
}

export function wasteDayText(isoDate: string, today: Date): string {
  const date = parseLocalDate(isoDate);
  const diff = dayDiff(date, today);
  if (diff === 0) {
    return 'heute';
  }
  if (diff === 1) {
    return 'morgen';
  }
  return `${WEEKDAYS[date.getDay()]} ${pad(date.getDate())}.${pad(date.getMonth() + 1)}.`;
}

export function wasteLines(events: WasteCollectionEvent[], today: Date): BubbleValue[] {
  const todayStart = startOfDay(today);
  const earliestByLabel = new Map<string, WasteCollectionEvent>();
  for (const event of events) {
    if (parseLocalDate(event.date).getTime() < todayStart) {
      continue;
    }
    const known = earliestByLabel.get(event.label);
    if (!known || event.date < known.date) {
      earliestByLabel.set(event.label, event);
    }
  }
  return [...earliestByLabel.values()]
    .sort((a, b) => a.date.localeCompare(b.date))
    .map(event => fresh(`${event.label} · ${wasteDayText(event.date, today)}`));
}

function germanNumber(value: number): string {
  return String(value).replace('.', ',');
}

function batteryLine(pet: TractivePet | null | undefined): BubbleValue {
  if (!pet || pet.batteryPercent === undefined || pet.batteryPercent === null) {
    return { ...MISSING, text: 'Akku –' };
  }
  return fresh(`Akku ${pet.batteryPercent} %${pet.charging ? ' · lädt' : ''}`);
}

function walkLine(extras: PetExtras | undefined): BubbleValue {
  if (!extras?.pet) {
    return { ...MISSING, text: 'Spaziergang –' };
  }
  const walk = extras.lastWalk;
  if (!walk) {
    return fresh('kein Spaziergang in 7 Tagen');
  }
  return fresh(`Spaziergang ${walkTimeRange(walk)} · ${walkDuration(walk)} · ${walkDistance(walk)}`);
}

export function petLines(pet: PetExtras | undefined, supplies: PetSupply[] | undefined): BubbleValue[] {
  const supplyLines = (supplies ?? []).map(supply =>
    fresh(`${supply.name} ${germanNumber(supply.amountRemaining)} ${supply.unit} · noch ${supply.daysRemaining} Tage`));
  return [batteryLine(pet?.pet), walkLine(pet), ...supplyLines];
}

export function motionLine(lastMotionAt: string | null | undefined, now: Date): BubbleValue | null {
  if (!lastMotionAt) {
    return null;
  }
  const at = new Date(lastMotionAt);
  if (Number.isNaN(at.getTime())) {
    return null;
  }
  const time = `${pad(at.getHours())}:${pad(at.getMinutes())}`;
  const sameDay = startOfDay(at) === startOfDay(now);
  const date = sameDay ? '' : `${pad(at.getDate())}.${pad(at.getMonth() + 1)}. `;
  return fresh(`letzte Bewegung ${date}${time}`);
}

export function lockBatteryLine(lock: LockExtras | null | undefined): BubbleValue | null {
  if (!lock || lock.batteryCharge === null || lock.batteryCharge === undefined) {
    return null;
  }
  return fresh(`Akku ${lock.batteryCharge} %${lock.batteryCritical ? ' · schwach' : ''}`);
}

function idAfterPrefix(entityId: string, prefix: string, suffix: string): string | null {
  if (!entityId.startsWith(prefix) || !entityId.endsWith(suffix)) {
    return null;
  }
  const id = entityId.slice(prefix.length, entityId.length - suffix.length);
  return id === '' ? null : id;
}

export function smartlockIdOf(entityId: string): number | null {
  const id = idAfterPrefix(entityId, 'lock.nuki_', '');
  return id !== null && /^\d+$/.test(id) ? Number(id) : null;
}

export function cameraIdOf(entityId: string): string | null {
  return idAfterPrefix(entityId, 'binary_sensor.blink_', '_armed');
}

export function trackerIdOf(entityId: string): string | null {
  return idAfterPrefix(entityId, 'binary_sensor.tractive_', '_home');
}
