/** Ein betroffener Raum der Lueftungsempfehlung. */
export interface VentilationRoom {
  name: string;
  temperature: number;
}

/**
 * Bewertung des Backends. `recommended === null` heisst "keine Aussage moeglich"
 * (kein frischer Aussenwert) — dann zeigt der Hub keine Karte, statt eine
 * falsche Entwarnung zu geben.
 */
export interface VentilationAssessment {
  recommended: boolean | null;
  outdoorTemperature: number | null;
  rooms: VentilationRoom[];
  /** ISO-Zeitstempel der Bewertung. */
  evaluatedAt: string;
}
