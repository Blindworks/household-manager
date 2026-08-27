/** Kamera laut GET /api/v1/blink/cameras (cameraId = stabile Blink-Hardware-Id). */
export interface BlinkCamera {
  cameraId: string;
  name: string;
  type: string;
  armed: boolean;
  battery: string | null;
  syncName: string;
  syncArmed: boolean;
}

/** Clip-Metadaten aus dem Local-Storage-Manifest der Kamera. */
export interface BlinkClip {
  clipId: string;
  createdAt: string;
  sizeBytes: number | null;
}
