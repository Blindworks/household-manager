/** Ein Abholtermin, wie ihn das Backend liefert. */
export interface WasteCollectionEvent {
  /** ISO-Datum, z. B. "2026-07-17". */
  date: string;
  /** Bezeichnung aus dem Kalender, z. B. "Biotonne". */
  label: string;
  /** 0 = heute, 1 = morgen. */
  daysUntil: number;
}

/** Konfiguration des Muellabfuhr-Kalenders. */
export interface WasteCollectionSettings {
  enabled: boolean;
  icsUrl: string;
  lookaheadDays: number;
  /** Format "HH:mm". */
  reminderTime: string;
  reminderEnabled: boolean;
  reminderAlexaSerials: string[];
}

/** Status des Kalenderabrufs. */
export interface WasteCollectionPollingStatus {
  schedule: string;
  lastPollTime: string | null;
  lastError: string | null;
  knownEventCount: number;
}
