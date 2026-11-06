/**
 * So lange zeigt das Feld nach einem erfolgreichen Schreiben weiter den lokalen
 * Sollwert, obwohl das Thermostat noch den alten meldet (der Hub meldet verzoegert).
 * Danach gilt wieder der Serverwert - die Anzeige klebt nie dauerhaft an einem Wert,
 * den das Geraet nicht hat.
 */
export const TARGET_GRACE_MS = 60_000;
export const UNREACHABLE = 'Gerät nicht erreichbar';
export const LAMP_OFF = 'Lampe ist aus';
export const NOT_APPLIED = 'Nicht übernommen';
export const CAMERA_UNREACHABLE = 'Kamera nicht erreichbar';
export const SNAPSHOT_FAILED = 'Schnappschuss fehlgeschlagen';
