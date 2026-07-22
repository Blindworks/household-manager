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
}
