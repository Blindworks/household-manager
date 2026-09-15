/**
 * Schnellzugriff-Eintrag: ein Helfer, der im Tablet-Dashboard direkt als Knopf steht —
 * waehrend eines Zeitfensters oder, ohne Fenster (beide Zeiten `null`), immer.
 *
 * Die Zeiten sind ISO-Uhrzeiten. Das Backend liefert "20:00:00", ein
 * `<input type="time">` sendet "20:00" — beides ist gueltig.
 */
export interface ModeQuickAccess {
  id: number;
  entityId: string;
  /** Anzeigename des Helfers; null, wenn es zu der Entity-ID keinen Helfer (mehr) gibt. */
  displayName: string | null;
  /** null = kein Zeitfenster, der Helfer steht immer im Schnellzugriff. */
  fromTime: string | null;
  toTime: string | null;
  active: boolean;
}

export interface ModeQuickAccessRequest {
  entityId: string;
  /** Beide null = immer anzeigen; halb gesetzt weist das Backend mit 400 ab. */
  fromTime: string | null;
  toTime: string | null;
  active: boolean;
}
