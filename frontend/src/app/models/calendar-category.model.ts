/** Eine gepflegte Kalender-Kategorie, wie die Admin-Seite sie verwaltet. */
export interface CalendarCategory {
  id: number;
  /** Stabiler Schluessel; Flows filtern darauf. Nicht aenderbar. */
  key: string;
  name: string;
  /** Hex-Farbe, z. B. "#64b5f6". */
  color: string;
  /** Material-Symbol-Name; leer/null = kein Icon. */
  icon: string | null;
  sortOrder: number;
  active: boolean;
}

/** Anlege-/Aenderungsdaten; der Schluessel wird serverseitig vergeben. */
export interface CalendarCategoryRequest {
  name: string;
  color: string;
  icon: string | null;
  sortOrder: number;
  active: boolean;
}

/** Die Kategorie, wie sie an einem Termin eingebettet mitkommt. */
export interface CalendarCategoryRef {
  id: number;
  key: string;
  name: string;
  color: string;
  icon: string | null;
}
