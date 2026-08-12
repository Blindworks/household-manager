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
