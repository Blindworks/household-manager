/**
 * Haus-Modus der Dashboard-Modus-Leiste (INPUT_BOOLEAN mit Modus-Marker) — und, ueber
 * `GET /v1/mode-quick-access/due`, ein beliebiger Helfer im Schnellzugriff.
 *
 * Das fruehere Feld `quickAccess` ist entfallen: faellige Schnellzugriffe liefert der
 * eigene Endpunkt (`ModeQuickAccessService.due`), damit Modi und Helfer dieselbe Quelle haben.
 */
export interface ModeEntity {
  entityId: string;
  displayName: string;
  /** Material-Symbols-Name. */
  icon: string;
  /** "on" oder "off". */
  state: string;
}
