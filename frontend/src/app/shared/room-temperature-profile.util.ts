import { FloorId, roomById } from './house/house-plan';
import { HOUSE_DEVICES } from './house/house-devices';
import { OUTDOOR_SENSOR_ENTITY_IDS, TemperatureColorPiece } from './temperature-comfort.util';

/**
 * Raumabhaengige Zieltemperaturen der Tablet-Temperaturansicht.
 *
 * Ein Bad soll waermer sein als ein Wohnraum, ein Schlafzimmer kuehler und ein
 * Keller ganz kalt. Die Linie wird deshalb nicht nach festen Waermegrenzen
 * gefaerbt, sondern nach dem Abstand zum Zielbereich ihres Raums: gruen im
 * Ziel, blau darunter, gelb bis rot darueber. Einzige Definition der Profile
 * und ihrer Zuordnung.
 */
export type RoomTemperatureProfileId = 'bath' | 'living' | 'bedroom' | 'hallway' | 'basement';

export interface RoomTemperatureProfile {
  id: RoomTemperatureProfileId;
  label: string;
  /** Untergrenze des Zielbereichs in °C (inklusive). */
  min: number;
  /** Obergrenze des Zielbereichs in °C (inklusive). */
  max: number;
}

export const ROOM_TEMPERATURE_PROFILES: Readonly<Record<RoomTemperatureProfileId, RoomTemperatureProfile>> = {
  bath: { id: 'bath', label: 'Bad', min: 22, max: 24 },
  living: { id: 'living', label: 'Wohnraum', min: 20, max: 23 },
  hallway: { id: 'hallway', label: 'Flur', min: 18, max: 21 },
  bedroom: { id: 'bedroom', label: 'Schlafzimmer', min: 16, max: 19 },
  basement: { id: 'basement', label: 'Keller', min: 10, max: 16 }
};

/** Raeume mit eigenem Profil; alle uebrigen Raeume folgen ihrer Etage. */
const ROOM_PROFILE_OVERRIDES: Readonly<Record<string, RoomTemperatureProfileId>> = {
  bad: 'bath',
  gaeste_wc: 'bath',
  schlafzimmer: 'bedroom',
  flur: 'hallway',
  treppenhaus: 'hallway'
};

const FLOOR_PROFILES: Readonly<Record<FloorId, RoomTemperatureProfileId>> = {
  basement: 'basement',
  ground: 'living',
  upper: 'living'
};

/** Ab so viel Grad ausserhalb des Ziels wird die Farbe kraeftiger. */
const MILD_DEVIATION = 2;
/** Ab so viel Grad ueber dem Ziel gilt es als deutlich zu warm. */
const STRONG_DEVIATION = 4;

/** Stufe relativ zum Zielbereich: -2 deutlich zu kalt … 0 im Ziel … 3 deutlich zu warm. */
export type RoomTemperatureLevel = -2 | -1 | 0 | 1 | 2 | 3;

const LEVEL_COLORS: Readonly<Record<RoomTemperatureLevel, string>> = {
  [-2]: '#60a5fa',
  [-1]: '#7dd3fc',
  0: '#4ade80',
  1: '#facc15',
  2: '#fb923c',
  3: '#f87171'
};

const LEVEL_LABELS: Readonly<Record<RoomTemperatureLevel, string>> = {
  [-2]: 'zu kalt',
  [-1]: 'kühl',
  0: 'im Ziel',
  1: 'etwas warm',
  2: 'warm',
  3: 'zu warm'
};

/** Temperatur-Entity-ID → Raum-Id, aus der Geraeteliste der 3D-Ansicht. */
function roomIdByTemperatureEntity(): Map<string, string> {
  const map = new Map<string, string>();
  for (const device of HOUSE_DEVICES) {
    if (device.kind === 'climate' && device.roomSensor && device.temperature) {
      map.set(device.temperature, device.area);
    }
  }
  return map;
}

const ROOM_BY_ENTITY = roomIdByTemperatureEntity();

/** Profil eines Raums (Raum-Id aus `house-plan.ts`); null fuer unbekannte Raeume. */
export function profileForRoom(roomId: string): RoomTemperatureProfile | null {
  const override = ROOM_PROFILE_OVERRIDES[roomId];
  if (override) {
    return ROOM_TEMPERATURE_PROFILES[override];
  }
  const room = roomById(roomId);
  return room ? ROOM_TEMPERATURE_PROFILES[FLOOR_PROFILES[room.floor]] : null;
}

/**
 * Profil eines Temperatursensors, erkannt ueber die Entity-ID (nie ueber den
 * umbenennbaren Namen). Aussenfuehler und Sensoren ohne Raumzuordnung liefern
 * null — sie behalten die bisherige Waermefaerbung, statt einen Raum zu raten.
 */
export function profileForTemperatureEntity(entityId: string | null | undefined): RoomTemperatureProfile | null {
  if (!entityId || OUTDOOR_SENSOR_ENTITY_IDS.includes(entityId)) {
    return null;
  }
  const roomId = ROOM_BY_ENTITY.get(entityId);
  return roomId ? profileForRoom(roomId) : null;
}

export function roomTemperatureLevel(celsius: number, profile: RoomTemperatureProfile): RoomTemperatureLevel {
  if (celsius < profile.min - MILD_DEVIATION) {
    return -2;
  }
  if (celsius < profile.min) {
    return -1;
  }
  if (celsius <= profile.max) {
    return 0;
  }
  if (celsius <= profile.max + MILD_DEVIATION) {
    return 1;
  }
  if (celsius <= profile.max + STRONG_DEVIATION) {
    return 2;
  }
  return 3;
}

export function roomTemperatureColor(celsius: number, profile: RoomTemperatureProfile): string {
  return LEVEL_COLORS[roomTemperatureLevel(celsius, profile)];
}

export function roomTemperatureLabel(celsius: number, profile: RoomTemperatureProfile): string {
  return LEVEL_LABELS[roomTemperatureLevel(celsius, profile)];
}

/** Zielbereich als Text, z. B. "Ziel 22–24 °C". */
export function profileTargetLabel(profile: RoomTemperatureProfile): string {
  return `Ziel ${profile.min}–${profile.max} °C`;
}

/** visualMap-Stuecke aus denselben Grenzen wie {@link roomTemperatureLevel}. */
export function roomTemperatureColorPieces(profile: RoomTemperatureProfile): TemperatureColorPiece[] {
  const { min, max } = profile;
  return [
    { lt: min - MILD_DEVIATION, color: LEVEL_COLORS[-2] },
    { gte: min - MILD_DEVIATION, lt: min, color: LEVEL_COLORS[-1] },
    { gte: min, lte: max, color: LEVEL_COLORS[0] },
    { gt: max, lte: max + MILD_DEVIATION, color: LEVEL_COLORS[1] },
    { gt: max + MILD_DEVIATION, lte: max + STRONG_DEVIATION, color: LEVEL_COLORS[2] },
    { gt: max + STRONG_DEVIATION, color: LEVEL_COLORS[3] }
  ];
}
