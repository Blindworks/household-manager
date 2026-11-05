export type FloorId = 'basement' | 'ground' | 'upper';

export const FLOORS: readonly { id: FloorId; label: string; level: number }[] = [
  { id: 'basement', label: 'Keller', level: -1 },
  { id: 'ground', label: 'EG', level: 0 },
  { id: 'upper', label: 'OG', level: 1 },
];

/** Rechteck in Skizzen-Einheiten (siehe UNITS_PER_METER). */
export interface PlanRect { x1: number; y1: number; x2: number; y2: number }

/** `cutouts` sind Flächen, die ein anderer Raum derselben Etage innerhalb dieses Raums belegt. */
export interface HouseRoom { id: string; label: string; floor: FloorId; rects: PlanRect[]; cutouts?: PlanRect[] }

export interface OutdoorArea { id: 'garden' | 'terrace' | 'front'; label: string; rect: PlanRect }

const rect = (x1: number, y1: number, x2: number, y2: number): PlanRect => ({ x1, y1, x2, y2 });

export const HOUSE_ROOMS: readonly HouseRoom[] = [
  // Erdgeschoss
  { id: 'esszimmer', label: 'Esszimmer', floor: 'ground', rects: [rect(20, 90, 210, 220)] },
  { id: 'kueche', label: 'Küche', floor: 'ground', rects: [rect(20, 220, 210, 350)] },
  { id: 'laufband', label: 'Laufband', floor: 'ground', rects: [rect(20, 350, 210, 440)] },
  { id: 'wohnzimmer', label: 'Wohnzimmer', floor: 'ground', rects: [rect(210, 90, 420, 250)] },
  { id: 'flur', label: 'Flur', floor: 'ground', rects: [rect(210, 250, 420, 440)], cutouts: [rect(210, 370, 290, 440)] },
  { id: 'gaeste_wc', label: 'Gäste-WC', floor: 'ground', rects: [rect(210, 370, 290, 440)] },
  // Obergeschoss
  { id: 'schlafzimmer', label: 'Schlafzimmer', floor: 'upper', rects: [rect(20, 90, 420, 220)] },
  { id: 'buero', label: 'Büro', floor: 'upper', rects: [rect(20, 220, 210, 340)] },
  { id: 'bad', label: 'Bad', floor: 'upper', rects: [rect(20, 340, 210, 440)] },
  { id: 'treppenhaus', label: 'Treppenhaus', floor: 'upper', rects: [rect(210, 220, 420, 440)] },
  // Keller
  { id: 'vorratskeller', label: 'Vorratskeller', floor: 'basement', rects: [rect(20, 90, 210, 220)] },
  { id: 'waschkeller', label: 'Waschkeller', floor: 'basement', rects: [rect(20, 220, 210, 440)] },
  { id: 'paincave', label: 'Paincave', floor: 'basement', rects: [rect(210, 90, 420, 250)] },
  { id: 'kellereingang', label: 'Kellereingang', floor: 'basement', rects: [rect(210, 250, 420, 440)] },
];

export const OUTDOOR_AREAS: readonly OutdoorArea[] = [
  { id: 'garden', label: 'Garten', rect: rect(10, 10, 430, 85) },
  { id: 'terrace', label: 'Terrasse', rect: rect(230, 56, 410, 88) },
  { id: 'front', label: 'Vorgarten', rect: rect(20, 445, 420, 480) },
];

export const HOUSE_BOUNDS: PlanRect = { x1: 20, y1: 90, x2: 420, y2: 440 };
export const UNITS_PER_METER = 40;
export const STOREY_HEIGHT_M = 2.6;

export function roomById(id: string): HouseRoom | undefined {
  return HOUSE_ROOMS.find(room => room.id === id);
}
