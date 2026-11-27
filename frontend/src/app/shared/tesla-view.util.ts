import { TeslaCommand, TeslaVehicle } from '../models/tesla.model';

/**
 * Einzige Aufbereitung des Tesla-Stands für die Dashboard-Kachel (Texte, Farbstufe,
 * Knöpfe). Reine Funktionen, kein Angular.
 */

export type TeslaBatteryTone = 'ok' | 'mid' | 'low';

export interface TeslaButton {
  command: TeslaCommand;
  label: string;
  icon: string;
  /** Zustand „an“ (Klima läuft, Klappe offen, Auto offen): Knopf hervorgehoben */
  active: boolean;
  /** Vor dem Senden nachfragen (nur Entriegeln) */
  confirm: boolean;
}

export interface TeslaStatusItem {
  icon: string;
  text: string;
}

/** Unter 20 % rot, unter 35 % gelb, sonst grün. */
export function teslaBatteryTone(level: number | undefined): TeslaBatteryTone {
  if (level === undefined) {
    return 'ok';
  }
  if (level < 20) {
    return 'low';
  }
  return level < 35 ? 'mid' : 'ok';
}

export function teslaRangeLabel(rangeKm: number | undefined): string {
  return rangeKm === undefined ? '–' : `${rangeKm.toLocaleString('de-DE', { maximumFractionDigits: 0 })} km`;
}

/** Die drei Zeilen unter dem Akkubalken: Ort, Ladeplan, Ladezustand (fehlende Angaben fallen weg). */
export function teslaStatusItems(vehicle: TeslaVehicle): TeslaStatusItem[] {
  const items: TeslaStatusItem[] = [];
  const location = locationLabel(vehicle);
  if (location) {
    items.push(location);
  }
  items.push(scheduleLabel(vehicle));
  const charging = chargingLabel(vehicle);
  if (charging) {
    items.push(charging);
  }
  return items;
}

function locationLabel(vehicle: TeslaVehicle): TeslaStatusItem | null {
  if (vehicle.shiftState === 'D' || vehicle.shiftState === 'R') {
    return { icon: 'directions_car', text: 'Fährt' };
  }
  if (vehicle.atHome === true) {
    return { icon: 'home', text: 'Zuhause' };
  }
  if (vehicle.atHome === false) {
    return { icon: 'location_on', text: 'Unterwegs' };
  }
  return null;
}

function scheduleLabel(vehicle: TeslaVehicle): TeslaStatusItem {
  if (vehicle.scheduledChargingPending && vehicle.scheduledChargeAt) {
    return { icon: 'schedule', text: `Laden ab ${timeOf(vehicle.scheduledChargeAt)}` };
  }
  return { icon: 'event_busy', text: 'Kein Ladeplan' };
}

function chargingLabel(vehicle: TeslaVehicle): TeslaStatusItem | null {
  switch (vehicle.chargingState) {
    case 'Charging': {
      const parts = ['Lädt'];
      if (vehicle.chargerPowerKw) {
        parts.push(`${vehicle.chargerPowerKw} kW`);
      }
      if (vehicle.minutesToFullCharge) {
        parts.push(`voll in ${durationLabel(vehicle.minutesToFullCharge)}`);
      }
      return { icon: 'bolt', text: parts.join(' · ') };
    }
    case 'Starting':
      return { icon: 'bolt', text: 'Laden startet' };
    case 'Complete':
      return { icon: 'battery_full', text: 'Geladen' };
    case 'Stopped':
      return { icon: 'power', text: 'Laden pausiert' };
    case 'NoPower':
      return { icon: 'power_off', text: 'Kein Strom am Kabel' };
    case 'Disconnected':
      return { icon: 'power_off', text: 'Lädt nicht' };
    case undefined:
      return null;
    default:
      return { icon: 'power', text: vehicle.chargingState };
  }
}

/**
 * Die vier Knöpfe der Kachel. Jeder schaltet in die Gegenrichtung des bekannten Zustands;
 * ist der Schlosszustand unbekannt, wird Verriegeln angeboten (nie ein ungefragtes Entriegeln).
 */
export function teslaButtons(vehicle: TeslaVehicle): TeslaButton[] {
  const locked = vehicle.locked === true;
  return [
    locked
      ? { command: 'UNLOCK', label: 'Entriegeln', icon: 'lock', active: false, confirm: true }
      : { command: 'LOCK', label: 'Verriegeln', icon: 'lock_open', active: true, confirm: false },
    vehicle.chargePortOpen
      ? { command: 'CLOSE_CHARGE_PORT', label: 'Klappe zu', icon: 'ev_station', active: true, confirm: false }
      : { command: 'OPEN_CHARGE_PORT', label: 'Ladeklappe', icon: 'ev_station', active: false, confirm: false },
    vehicle.climateOn
      ? { command: 'STOP_CLIMATE', label: 'Klima aus', icon: 'mode_fan', active: true, confirm: false }
      : { command: 'START_CLIMATE', label: 'Klima', icon: 'mode_fan', active: false, confirm: false },
    vehicle.defrostOn
      ? { command: 'STOP_DEFROST', label: 'Enteisen aus', icon: 'ac_unit', active: true, confirm: false }
      : { command: 'START_DEFROST', label: 'Enteisen', icon: 'ac_unit', active: false, confirm: false }
  ];
}

/** Gilt der Befehl im aktuellen Stand noch? (Regel confirmToggle: nie in die Gegenrichtung schalten.) */
export function teslaCommandStillApplies(vehicle: TeslaVehicle | undefined, command: TeslaCommand): boolean {
  return !!vehicle && teslaButtons(vehicle).some(button => button.command === command);
}

/** „Stand 07:12“, wenn der Zeitstempel älter als 15 Minuten ist, sonst null. */
export function teslaStaleLabel(measuredAt: string | undefined, now: Date): string | null {
  if (!measuredAt) {
    return null;
  }
  const time = new Date(measuredAt);
  if (Number.isNaN(time.getTime()) || now.getTime() - time.getTime() < 15 * 60_000) {
    return null;
  }
  const sameDay = time.toDateString() === now.toDateString();
  const date = sameDay ? '' : `${time.getDate()}.${time.getMonth() + 1}. `;
  return `Stand ${date}${timeOf(measuredAt)}`;
}

/** Grobe Lackfarbe für die Zeichnung; unbekannt = hellgrau. */
export function teslaPaint(exteriorColor: string | undefined): string {
  const color = (exteriorColor ?? '').toLowerCase();
  if (color.includes('black') || color.includes('obsidian')) {
    return '#1c1d20';
  }
  if (color.includes('blue')) {
    return '#2b4f8f';
  }
  if (color.includes('red')) {
    return '#a3141f';
  }
  if (color.includes('grey') || color.includes('gray') || color.includes('graphite') || color.includes('stealth')) {
    return '#5d6066';
  }
  if (color.includes('silver') || color.includes('quicksilver')) {
    return '#a9adb3';
  }
  return '#e9eaec';
}

function timeOf(iso: string): string {
  const date = new Date(iso);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function durationLabel(minutes: number): string {
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}
