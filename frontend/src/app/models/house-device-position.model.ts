/** Gespeicherter Platz eines Geraets im Grundriss (Plan-Einheiten). */
export interface HouseDevicePosition {
  deviceId: string;
  x: number;
  y: number;
  /** Drehung eines Geraetekoerpers in Grad (0/90/180/270); fehlt/null = automatisch zur Wand. */
  rotation?: number | null;
}
