/** Haus-Modus der Dashboard-Modus-Leiste (INPUT_BOOLEAN mit Modus-Marker). */
export interface ModeEntity {
  entityId: string;
  displayName: string;
  /** Material-Symbols-Name. */
  icon: string;
  /** "on" oder "off". */
  state: string;
  /**
   * True, wenn das Backend fuer diesen Modus gerade ein Zeitfenster offen sieht. Das
   * Tablet-Dashboard zeigt ihn dann direkt als Knopf neben der eingeklappten Leiste.
   */
  quickAccess: boolean;
}
