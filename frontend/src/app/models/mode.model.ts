/** Haus-Modus der Dashboard-Modus-Leiste (INPUT_BOOLEAN mit Modus-Marker). */
export interface ModeEntity {
  entityId: string;
  displayName: string;
  /** Material-Symbols-Name. */
  icon: string;
  /** "on" oder "off". */
  state: string;
}
