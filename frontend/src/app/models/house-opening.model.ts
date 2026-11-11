import { FloorId } from '../shared/house/house-plan';

/** Fenster oder Tuer an einer Wand des Digitalen Zwillings (Plan-Einheiten). */
export type OpeningKind = 'WINDOW' | 'DOOR';
export type OpeningSize = 'SMALL' | 'NORMAL' | 'WIDE' | 'TERRACE';
/** START = Anschlag an (x1, y1), END = an (x2, y2). */
export type OpeningHinge = 'START' | 'END';
/** POSITIVE = oeffnet nach +y (waagerechte Wand) bzw. +x (senkrechte Wand). */
export type OpeningSwing = 'POSITIVE' | 'NEGATIVE';

export interface HouseOpeningRequest {
  floor: FloorId;
  kind: OpeningKind;
  size: OpeningSize;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  hinge: OpeningHinge;
  swing: OpeningSwing;
  deviceId: string | null;
}

export interface HouseOpening extends HouseOpeningRequest {
  id: number;
}
