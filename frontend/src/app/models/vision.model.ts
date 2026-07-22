/** Modelle der Gesichtserkennung (Personen, Erkennungen, Status). */
export interface VisionPerson {
  id: number;
  name: string;
  active: boolean;
  photoCount: number;
}

export interface VisionPhoto {
  id: number;
  personId: number;
  photoBase64: string;
}

export interface VisionRecognition {
  id: number;
  recognizedAt: string;
  personId: number | null;
  personName: string | null;
  confidence: number | null;
  unknownFaces: number;
  thumbnailBase64: string | null;
}

export interface VisionStatus {
  sidecarReachable: boolean;
  loggedIn: boolean;
  cameraFound: boolean;
  cameraName: string | null;
  lastPollAt: string | null;
}
