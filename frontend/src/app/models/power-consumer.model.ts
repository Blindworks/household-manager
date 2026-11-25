/**
 * Ein Stromverbraucher für die Verbraucher-Kachel: Power-Sensor einer
 * Steckdose (Meross, Shelly, ...) mit aktueller Leistung.
 */
export interface PowerConsumer {
  entityId: string;
  displayName: string;
  /** Aktuelle Leistung in Watt; null, wenn der Sensor nicht erreichbar ist. */
  powerWatts: number | null;
  unavailable: boolean;
  /**
   * Die Steckdose ist nicht erreichbar (Meross offline); ihr letzter Wert waere
   * veraltet. Fehlt das Feld (aeltere Antwort), gilt sie als erreichbar.
   */
  unreachable?: boolean;
}

/** Auswählbarer Zeitraum des Verbrauchsgraphen. */
export type PowerRange = 'DAY' | 'WEEK' | 'MONTH';

/** Ein Punkt des Leistungsverlaufs; value null = Messlücke. */
export interface PowerHistoryPoint {
  /** ISO-Zeitstempel. */
  time: string;
  value: number | null;
}

/** Leistungsverlauf eines Verbrauchers. */
export interface PowerHistory {
  entityId: string;
  displayName: string;
  points: PowerHistoryPoint[];
}
