/**
 * Zeitfenster, in dem ein Haus-Modus im Tablet-Dashboard direkt als Knopf steht.
 *
 * Die Zeiten sind ISO-Uhrzeiten. Das Backend liefert "20:00:00", ein
 * `<input type="time">` sendet "20:00" — beides ist gueltig.
 */
export interface ModeQuickAccess {
  id: number;
  entityId: string;
  /** Anzeigename des Modus; null, wenn es zu der Entity-ID keinen Modus (mehr) gibt. */
  displayName: string | null;
  fromTime: string;
  toTime: string;
  active: boolean;
}

export interface ModeQuickAccessRequest {
  entityId: string;
  fromTime: string;
  toTime: string;
  active: boolean;
}
