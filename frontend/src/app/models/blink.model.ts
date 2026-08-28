/** Kamera laut GET /api/v1/blink/cameras (cameraId = stabile Blink-Hardware-Id). */
export interface BlinkCamera {
  cameraId: string;
  name: string;
  type: string;
  armed: boolean;
  battery: string | null;
  syncName: string;
  syncArmed: boolean;
  /** Letzte erkannte Bewegung (ISO-Zeitstempel) — null/fehlend, wenn keine bekannt. */
  lastMotionAt?: string | null;
  /** Clip der letzten Bewegung; zusammen mit clipUrl direkt abspielbar. */
  lastMotionClipId?: string | null;
}

/** Clip-Metadaten aus dem Local-Storage-Manifest der Kamera. */
export interface BlinkClip {
  clipId: string;
  createdAt: string;
  sizeBytes: number | null;
}
