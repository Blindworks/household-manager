import { NukiLock } from '../models/nuki.model';
import { TractivePet } from '../models/tractive.model';
import { HubInsight } from './hub-insight.model';

/** Unter dieser Marke erscheint der Hinweis — der Indikator wird gelb. */
const WARN_BELOW_PERCENT = 40;
/** Unter dieser Marke ist der Akku kritisch — der Indikator wird rot. */
const CRITICAL_BELOW_PERCENT = 20;

/**
 * Baut die Hub-Karte fuer schwache Tracker-Akkus, z. B.
 * "Tracker-Akku von Toni: 32 %".
 *
 * <p>Ladende Tracker werden uebersprungen (auf der Ladeschale ist das Problem
 * bereits geloest, die Karte waere Dauer-Rauschen), ein fehlender Akkustand
 * ebenso — geraten wird nicht (Muster `atHome`).
 *
 * @returns `null`, wenn kein Tracker unter der Schwelle liegt — dann erscheint
 * im Hub kein Eintrag.
 */
export function buildTrackerBatteryInsight(pets: TractivePet[]): HubInsight | null {
  const lowBattery = pets.filter(pet =>
    pet.batteryPercent != null
    && pet.batteryPercent < WARN_BELOW_PERCENT
    && pet.charging !== true);
  if (lowBattery.length === 0) {
    return null;
  }
  const lowestPercent = Math.min(...lowBattery.map(pet => pet.batteryPercent!));
  return {
    icon: 'battery_alert',
    tone: lowestPercent < CRITICAL_BELOW_PERCENT ? 'error' : 'tertiary',
    title: 'Hundetracker',
    // "von <Name>" statt Genitiv-s, damit Namen wie "Klaus" keinen kaputten Genitiv erzeugen.
    text: lowBattery.map(pet => `Tracker-Akku von ${pet.name}: ${pet.batteryPercent} %`).join(' · ')
  };
}

/** Unter dieser Marke erscheint der Hinweis zum Schlossakku — der Indikator wird gelb. */
export const LOCK_WARN_BELOW_PERCENT = 30;
/**
 * Unter dieser Marke ist der Schlossakku kritisch — der Indikator wird rot. Dieselbe
 * Schwelle steht im Push-Flow auf `sensor.nuki_<id>_battery` und ist dort mitzuziehen.
 */
export const LOCK_CRITICAL_BELOW_PERCENT = 20;

/**
 * Baut die Hub-Karte fuer schwache Schlossakkus, z. B. "Akku von Haustür: 27 %".
 * Meldet das Schloss `batteryCritical` ohne Prozentwert, steht die Karte rot mit
 * "Akku kritisch". Ohne jeden Akkuwert wird nichts geraten.
 *
 * @returns `null`, wenn kein Schloss unter der Schwelle liegt.
 */
export function buildLockBatteryInsight(locks: NukiLock[]): HubInsight | null {
  const low = locks.filter(isLockBatteryLow);
  if (low.length === 0) {
    return null;
  }
  const critical = low.some(lock => lock.batteryCritical
    || (lock.batteryCharge != null && lock.batteryCharge < LOCK_CRITICAL_BELOW_PERCENT));
  return {
    icon: 'battery_alert',
    tone: critical ? 'error' : 'tertiary',
    title: 'Türschloss',
    text: low.map(lockBatteryText).join(' · ')
  };
}

function isLockBatteryLow(lock: NukiLock): boolean {
  if (lock.batteryCharge != null) {
    return lock.batteryCharge < LOCK_WARN_BELOW_PERCENT;
  }
  return lock.batteryCritical;
}

function lockBatteryText(lock: NukiLock): string {
  return lock.batteryCharge != null
    ? `Akku von ${lock.name}: ${lock.batteryCharge} % – bald wechseln`
    : `Akku von ${lock.name} kritisch – bald wechseln`;
}
