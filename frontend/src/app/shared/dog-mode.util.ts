import { CurrentTemperatureReading } from '../models/temperature.model';
import { ModeEntity } from '../models/mode.model';
import { buildClimateView, isReadingStale } from './temperature-comfort.util';

/**
 * Dog-Mode-Screen des Wandtablets (Muster Tesla-Dog-Mode): solange „Toni allein" an ist,
 * ersetzt ein Vollbild mit Uhr und Temperatur das Dashboard. Einzige Definition, wann
 * der Screen steht und welche Werte er zeigt.
 */
export const DOG_MODE_ENTITY_ID = 'input_boolean.manual_toni_allein';

/**
 * Wohnzimmer = Amazon-Luftqualitaetsmonitor (Hardware-Seriennummer). Bewusst die
 * sensorId statt des Anzeigenamens: die Temperatur-API liefert den Custom-Namen, ein
 * Umbenennen braeche eine Namenspruefung still. Vergleich ohne Gross-/Kleinschreibung,
 * weil die Entity-ID die Seriennummer kleingeschrieben fuehrt.
 */
export const DOG_MODE_INDOOR_SENSOR_ID = 'alexa:gaj2300425330047';

export interface DogModeIndoor {
  /** Ganze Grad, z. B. "22°". */
  label: string;
  stale: boolean;
}

export interface DogModeClimate {
  indoor: DogModeIndoor | null;
  /** Ganze Grad, z. B. "14°", oder null ohne jeden Aussenwert. */
  outdoorLabel: string | null;
}

export const EMPTY_DOG_MODE_CLIMATE: DogModeClimate = { indoor: null, outdoorLabel: null };

/** Aktiv nur bei explizitem "on" — fehlt der Modus in der Liste, wird nicht geraten. */
export function isDogModeActive(modes: readonly ModeEntity[]): boolean {
  return modes.some(mode => mode.entityId === DOG_MODE_ENTITY_ID && mode.state === 'on');
}

export function buildDogModeClimate(readings: CurrentTemperatureReading[], nowMs: number): DogModeClimate {
  const indoorReading = readings.find(
    reading => reading.sensorId.toLowerCase() === DOG_MODE_INDOOR_SENSOR_ID);
  const indoor = indoorReading
    ? { label: wholeDegrees(indoorReading.temperature), stale: isReadingStale(indoorReading, nowMs) }
    : null;

  const outdoor = findOutdoorReading(readings, nowMs);
  return { indoor, outdoorLabel: outdoor ? wholeDegrees(outdoor.temperature) : null };
}

/** Realer Aussenfuehler (Erkennung wie in der Klima-Kachel), sonst der DWD-Wert. */
function findOutdoorReading(
  readings: CurrentTemperatureReading[],
  nowMs: number
): CurrentTemperatureReading | undefined {
  const primaryOutdoorId = buildClimateView(readings, nowMs).outdoor[0]?.sensorId;
  return readings.find(reading => reading.sensorId === primaryOutdoorId)
    ?? readings.find(reading => reading.source === 'WEATHER');
}

function wholeDegrees(celsius: number): string {
  return `${Math.round(celsius)}°`;
}
